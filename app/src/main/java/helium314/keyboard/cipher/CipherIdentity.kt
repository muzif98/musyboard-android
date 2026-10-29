package helium314.keyboard.cipher

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import helium314.keyboard.compat.isDeviceLocked
import helium314.keyboard.compat.isUserLocked

/**
 * Result of initialisation. Not every case is an error ([Locked] is a normal
 * condition), and none becomes an exception: an exception in a keyboard is a
 * device you can no longer type on.
 */
sealed class CipherState {

    /** The core is initialised. From here on natives can be called. */
    object Ready : CipherState()

    /**
     * User not yet unlocked after reboot. The keyboard works (it is needed to
     * type the PIN), encryption does not: the master key requires an unlocked
     * device. Not a fault and not to be cached: call
     * [CipherIdentity.ensureReady] again later.
     */
    object Locked : CipherState()

    /** Encryption is not available on this device or in this build. */
    data class Unavailable(val reason: CipherReason) : CipherState()

    /**
     * The data is there but does not decrypt. **A state, not an error**: it needs
     * a user decision, like `LabelOutcome::Conflict` in the core. See
     * [CipherIdentity.resetIdentity] for what the answer implies.
     */
    data class Unreadable(val part: CipherPart) : CipherState()
}

/**
 * How a backup import went. Its own type rather than [CipherState]: the importer
 * must tell the user *why* it failed, and there are more causes than the rest of
 * the keyboard needs.
 *
 * [EsitoImport.Passphrase] also covers a tampered file: distinguishing them would
 * tell a guesser when they got it right. The other causes are decided before
 * decryption or after it succeeded, so separating them says nothing about the
 * passphrase.
 */
sealed class EsitoImport {
    object Riuscito : EsitoImport()
    /** Wrong passphrase or modified file. */
    object Passphrase : EsitoImport()
    /** Not one of our backups, or truncated. */
    object NonBackup : EsitoImport()
    /** Written by a newer version. */
    object Versione : EsitoImport()
    /** Opened, but the keyring inside does not read. */
    object Contenuto : EsitoImport()
    object Keystore : EsitoImport()
    object Storage : EsitoImport()
    /** Missing library or Android too old. */
    object NonDisponibile : EsitoImport()
    /** A core code not expected here: shown for diagnosis. */
    data class Interno(val codice: Int) : EsitoImport()

    companion object {
        fun daCodice(codice: Int): EsitoImport = when (codice) {
            CipherCore.CRYPTO -> Passphrase
            CipherCore.FORMAT, CipherCore.NOT_OUR_BLOB -> NonBackup
            CipherCore.UNSUPPORTED_VERSION -> Versione
            CipherCore.DECODE, CipherCore.KEYRING -> Contenuto
            else -> Interno(codice)
        }
    }
}

enum class CipherReason {
    /** The `.so` did not load: unexpected ABI, or a build without the core. */
    MISSING_LIBRARY,

    /** Below API 23 AndroidKeyStore cannot do AES. See [CipherKeystore]. */
    API_TOO_OLD,

    /** Keystore refused to generate or use the master key. */
    KEYSTORE,

    /** The file was not written: disk full, or storage unavailable. */
    STORAGE,

    /** The core rejected the secret or the keyring. */
    CORE,
}

enum class CipherPart { IDENTITY, KEYRING }

/**
 * Key lifecycle: generation, persistence, reload.
 *
 * It ties together three parts that do not know each other: the Rust core,
 * which does no I/O; Keystore, which protects; the filesystem, which stores.
 *
 * ## Lazy, not at startup
 *
 * [ensureReady] is called on first real use, not from `Application.onCreate`:
 * at startup the user may still be locked, and a keyboard generating keys while
 * the system waits for its window is a slow keyboard. The first call may cost a
 * few hundred milliseconds (key generation in Keystore, StrongBox if present);
 * later ones read a flag. **Do not call it from a drawing path.**
 *
 * ## What it deliberately does not do
 *
 * It never regenerates the identity by itself. If the secret is there but does
 * not decrypt, it returns [CipherState.Unreadable] and stops. Regenerating would
 * be the convenient and worst reaction: the user would get a new identity
 * without knowing, and every contact would see "key changed", the signal the
 * system uses for "someone is impersonating them". A local failure would become
 * indistinguishable from an attack. Replacement exists, but the user decides
 * it: [resetIdentity].
 */
object CipherIdentity {

    /**
     * Domain separation between the two files: a valid blob of one is never
     * accepted in place of the other.
     */
    private val AAD_IDENTITY = "keyboard-cipher/v1/storage/identity".toByteArray()
    private val AAD_KEYRING = "keyboard-cipher/v1/storage/keyring".toByteArray()

    @Volatile
    private var ready = false

    /**
     * Initialises the core if needed and reports how it went. Idempotent: after
     * the first success it is a field read.
     */
    fun ensureReady(context: Context): CipherState {
        if (ready) return CipherState.Ready
        val app = context.applicationContext
        return synchronized(this) { loadLocked(app) }
    }

    /**
     * Writes the current keyring to disk. Must be called after EVERY change
     * (pinning a new peer, label, key change confirmation, out-of-band
     * verification): the core keeps the keyring in memory and does not know a
     * disk exists.
     *
     * An unpersisted keyring means peers coming back "new" after a restart: TOFU
     * would re-pin them silently, reopening the MITM window at every reboot.
     */
    fun persistKeyring(context: Context): Boolean {
        if (!ready) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val app = context.applicationContext
        return synchronized(this) { persistLocked(app) }
    }

    /**
     * Discards identity and keyring and starts from scratch.
     *
     * Destructive and irreversible: the old identity cannot be recovered,
     * messages already received will no longer decrypt, and every contact will
     * see a key change. Call ONLY after explicit user confirmation on a screen
     * that says these three things.
     *
     * Exists to get out of [CipherState.Unreadable]: otherwise the only way would
     * be clearing the app's data.
     */
    fun resetIdentity(context: Context): CipherState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return CipherState.Unavailable(CipherReason.API_TOO_OLD)
        }
        val app = context.applicationContext
        return synchronized(this) {
            ready = false
            CipherStorage.delete(app, CipherStorage.IDENTITY)
            CipherStorage.delete(app, CipherStorage.KEYRING)
            CipherRecipients.forget(app)
            // Tombstones and usage statistics too. They are not keys and make
            // nothing readable, but they are two lists of WHO you talked to, the
            // metadata the whole project exists not to give away, and they stayed
            // on disk after a gesture called "delete everything".
            //
            // Tombstones in particular contain the NAME the user gave each
            // forgotten contact.
            CipherStorage.delete(app, CipherStorage.TOMBSTONES)
            CipherStorage.delete(app, CipherStorage.USAGE)
            // And saved groups: contact keys grouped by affinity, i.e. "who is
            // with whom", metadata richer than the contact list.
            CipherGroups.cancellaTutto(app)
            // The folder outgoing encrypted attachments go through. Not secret
            // (those files are encrypted), but it is the list of WHEN you sent
            // something.
            CipherFiles.svuotaCondivisione(app)
            // And the clipboard, if one of our blobs or freshly read plaintext is
            // still there: the most exposed surface of all, visible to any app
            // asking for the clipboard.
            CipherClipboard.svuota(app)
            // After the files: deleting the key first would leave two
            // undecryptable blobs on disk if file deletion failed.
            CipherKeystore.deleteKey()
            loadLocked(app)
        }
    }

    /**
     * Replaces the current identity with a backup's, and persists it.
     *
     * As destructive as [resetIdentity], with one important difference: the old
     * identity is discarded for a NEW one the user chose, not a random one. Still
     * behind a confirmation.
     *
     * Order: open the backup, then write to disk; done only if both succeed. If
     * the process died between the in-memory import and the write, the old
     * identity would come back on restart: unpleasant but consistent. The
     * opposite (new on disk, old in memory) would be worse.
     */
    fun importBackup(context: Context, blob: ByteArray, passphrase: ByteArray): EsitoImport {
        if (!CipherCore.available) return EsitoImport.NonDisponibile
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return EsitoImport.NonDisponibile
        val app = context.applicationContext
        return synchronized(this) { importLocked(app, blob, passphrase) }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun importLocked(
        context: Context,
        blob: ByteArray,
        passphrase: ByteArray,
    ): EsitoImport {
        val secret = ByteArray(SECRET_LEN)
        try {
            val codice = CipherCore.nativeImportBackup(blob, passphrase, secret)
            if (codice != CipherCore.OK) return EsitoImport.daCodice(codice)
            // WARNING: at this point the native session HAS ALREADY changed:
            // `nativeImportBackup` replaces identity and keyring before returning.
            // From here a failure is no longer "nothing happened": the new
            // identity is in memory and the old one on disk.
            //
            // Without the restore below the user would read "could not open the
            // file" (false) and keep encrypting with the imported identity until
            // the process died, then go back to the old one on restart.
            val wrapped = CipherKeystore.wrap(AAD_IDENTITY, secret, creaSeManca = true)
            if (wrapped == null) {
                annullaImport(context)
                return EsitoImport.Keystore
            }
            if (!CipherStorage.write(context, CipherStorage.IDENTITY, wrapped)) {
                annullaImport(context)
                return EsitoImport.Storage
            }
            ready = true
            // The keyring comes with the secret: without this line the contacts
            // would be in memory and none on disk.
            persistLocked(context)
            return EsitoImport.Riuscito
        } finally {
            secret.fill(0)
        }
    }

    /**
     * Puts the session back as it was, reloading it from disk.
     *
     * Used when the import succeeded in memory but could not be persisted:
     * leaving the two out of sync is worse than failing.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun annullaImport(context: Context) {
        ready = false
        loadLocked(context)
    }

    /**
     * Produces the backup blob. `null` if the session is not ready or anything
     * fails.
     */
    fun exportBackup(passphrase: ByteArray): ByteArray? {
        if (!ready) return null
        return synchronized(this) { CipherCore.nativeExportBackup(passphrase) }
    }

    private const val SECRET_LEN = 32

    private fun loadLocked(context: Context): CipherState {
        if (ready) return CipherState.Ready
        // The .so may be missing through nobody's fault (build without the core,
        // an ABI outside the four expected). Not a crash: a feature that is not
        // there.
        if (!CipherCore.available) return CipherState.Unavailable(CipherReason.MISSING_LIBRARY)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return CipherState.Unavailable(CipherReason.API_TOO_OLD)
        }
        // Before the first unlock the master key is unusable. Returning here
        // avoids treating a locked device as corrupted data.
        if (isUserLocked(context)) return CipherState.Locked
        return initLocked(context)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun initLocked(context: Context): CipherState {
        var secret: ByteArray? = null
        var keyring: ByteArray? = null
        try {
            val loaded = if (CipherStorage.exists(context, CipherStorage.IDENTITY)) {
                val blob = CipherStorage.read(context, CipherStorage.IDENTITY)
                    ?: return unreadableOrLocked(context, CipherPart.IDENTITY)
                CipherKeystore.unwrap(AAD_IDENTITY, blob)
                    ?: return unreadableOrLocked(context, CipherPart.IDENTITY)
            } else {
                when (val created = createIdentity(context)) {
                    is Created.Ok -> created.secret
                    is Created.Failed -> return CipherState.Unavailable(created.reason)
                }
            }
            secret = loaded

            keyring = if (CipherStorage.exists(context, CipherStorage.KEYRING)) {
                val blob = CipherStorage.read(context, CipherStorage.KEYRING)
                    ?: return unreadableOrLocked(context, CipherPart.KEYRING)
                // An unreadable keyring is NOT replaced by an empty one: that would
                // silently lose every pin and every "verified in person", a security
                // regression disguised as a clean restart.
                CipherKeystore.unwrap(AAD_KEYRING, blob)
                    ?: return unreadableOrLocked(context, CipherPart.KEYRING)
            } else {
                ByteArray(0)
            }

            if (CipherCore.nativeInit(secret, keyring) != CipherCore.OK) {
                return CipherState.Unavailable(CipherReason.CORE)
            }
            ready = true
            // After nativeInit, never before: the core keeps the recipient choice
            // in memory, and without this every service restart cleared it.
            CipherRecipients.restore(context)
            // Here and not before, for the same reason: they are blobs encrypted
            // with the Keystore key, unusable before this point. A queue left by a
            // process that died mid-message comes back by itself.
            CipherParti.ripristina(context)
            return CipherState.Ready
        } finally {
            // The core already copied what it needs; a copy remains on the heap
            // that GC does not zeroize.
            //
            // Applies to both: the keyring holds OUR private prekeys, which the
            // core keeps in `Zeroizing` precisely because forward secrecy exists
            // to make them disappear.
            secret?.fill(0)
            keyring?.fill(0)
        }
    }

    /**
     * Tells "corrupted data" from "locked device", which inside [CipherKeystore]
     * are the same `null`.
     *
     * Verified on an API 34 emulator with a PIN: with the screen locked, Keystore
     * refuses the key with `Error::Km(DEVICE_LOCKED)`, because it was generated
     * with `setUnlockedDeviceRequired`. Without this distinction that refusal
     * (transient, normal, gone at the next unlock) would be shown as "your
     * identity cannot be decrypted", whose only offered remedy is
     * [resetIdentity]: inviting the user to irreversibly destroy their identity
     * over something fixed by unlocking.
     *
     * Classified AFTER the failure, not before: a key generated on a device
     * without a screen lock has no such constraint and works while locked.
     */
    private fun unreadableOrLocked(context: Context, part: CipherPart): CipherState =
        if (isDeviceLocked(context)) CipherState.Locked else CipherState.Unreadable(part)

    private sealed class Created {
        class Ok(val secret: ByteArray) : Created()
        class Failed(val reason: CipherReason) : Created()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun createIdentity(context: Context): Created {
        val secret = CipherCore.nativeGenerateSecret()
            ?: return Created.Failed(CipherReason.CORE)
        // The ONLY place the master key can be born, together with backup import:
        // here the identity is really being created.
        val blob = CipherKeystore.wrap(AAD_IDENTITY, secret, creaSeManca = true)
        if (blob == null) {
            secret.fill(0)
            return Created.Failed(CipherReason.KEYSTORE)
        }
        // To disk BEFORE being used. A live but unpersisted identity would vanish
        // with the process, after the user already gave their card to someone,
        // who would then write to a key that no longer exists.
        if (!CipherStorage.write(context, CipherStorage.IDENTITY, blob)) {
            secret.fill(0)
            return Created.Failed(CipherReason.STORAGE)
        }
        return Created.Ok(secret)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun persistLocked(context: Context): Boolean {
        val keyring = CipherCore.nativeExportKeyring() ?: return false
        return try {
            val blob = CipherKeystore.wrap(AAD_KEYRING, keyring) ?: return false
            CipherStorage.write(context, CipherStorage.KEYRING, blob)
        } finally {
            // See [initLocked]: private prekeys are in here.
            keyring.fill(0)
        }
    }
}
