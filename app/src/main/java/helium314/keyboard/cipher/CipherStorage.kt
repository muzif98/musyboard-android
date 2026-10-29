package helium314.keyboard.cipher

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream

/**
 * The system's files, already encrypted by [CipherKeystore].
 *
 * They live under `noBackupFilesDir`, not `filesDir`, and that is the most
 * important choice in this file. HeliBoard has `android:allowBackup="true"`
 * without exclusion rules, so everything in `filesDir` goes to cloud backup. Two
 * bad consequences:
 *
 *  - the master key is in Keystore and NOT exportable, so it is not restored. A
 *    restore on a new phone would bring back two unreadable files: a "corrupted"
 *    identity through no fault of the user;
 *  - the peer list is exactly the metadata this project exists not to give away.
 *    Encrypted or not, it has no reason to go through a third-party server.
 *
 * `noBackupFilesDir` guarantees this by construction, without touching upstream's
 * `fullBackupContent` / `dataExtractionRules`.
 *
 * ## Where they actually end up, measured
 *
 * The manifest has `defaultToDeviceProtectedStorage="true"`, from which one would
 * deduce that these files live in device-protected storage. **Not so for
 * `noBackupFilesDir`.** Verified on API 34 on a CLEAN install (uninstall,
 * reinstall, then read the path the function actually returns): the result is
 * `/data/user/0/<pkg>/no_backup/cipher/`, i.e. *credential encrypted*. The app's
 * device-protected tree (`/data/user_de/0/…`) exists in parallel and has no
 * `no_backup` at all.
 *
 * The mechanism was not reconstructed: the measured outcome is recorded, not the
 * explanation. Anyone changing the flag or the directory should measure again.
 *
 * The difference is in our favour: CE storage is encrypted with a key derived
 * from the user's credential, so at rest and before the first unlock the files
 * are not even readable. The master key protection (`setUnlockedDeviceRequired`)
 * adds to this. Practical consequence, already handled: before the first unlock
 * this directory is not accessible, another reason why [CipherIdentity] returns
 * `Locked` instead of trying.
 */
internal object CipherStorage {

    private const val DIR = "cipher"

    const val IDENTITY = "identity.bin"
    const val KEYRING = "keyring.bin"
    /** Current recipient per app. See [CipherRecipients]. */
    const val RECIPIENTS = "recipients.bin"
    /** Forgotten contacts, to recognise them if they come back. See [CipherLapidi]. */
    const val TOMBSTONES = "forgotten.bin"
    /** Last use of each contact, for list order. See [CipherUsage]. */
    const val USAGE = "usage.bin"

    /** Saved groups (decision K5). See [CipherGroups]. */
    const val GROUPS = "groups.bin"

    /**
     * The parts of a split message still to be delivered. See [CipherParti].
     *
     * The only file here without keys or permanent metadata: blobs **already
     * encrypted** for the recipient, about to go to the chat anyway. It still
     * goes through Keystore (in clear it would tell whoever looks at the disk that
     * you were writing to someone, and when) and disappears once the last part is
     * delivered.
     */
    const val PARTS = "parts.bin"

    /**
     * Tells "not there yet" from "there but unreadable". The first is the first
     * start, the second a fault: confusing them would regenerate the identity at
     * the first read error.
     */
    fun exists(context: Context, name: String): Boolean = file(context, name).isFile

    fun read(context: Context, name: String): ByteArray? =
        runCatching { file(context, name).readBytes() }.getOrNull()

    /**
     * Atomic write: temporary file, `fsync`, rename, `fsync` of the directory
     * (see [syncDir]). Otherwise an interruption halfway (battery, OOM killer)
     * would leave a truncated file, which after authenticated encryption is
     * indistinguishable from a tampered one: "corrupted identity" for a dead
     * battery.
     */
    fun write(context: Context, name: String, bytes: ByteArray): Boolean = runCatching {
        val target = file(context, name)
        val tmp = File(target.parentFile, "$name.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (tmp.renameTo(target)) {
            syncDir(target.parentFile)
            true
        } else {
            tmp.delete()
            false
        }
    }.getOrDefault(false)

    /**
     * `fsync` on the directory, the only thing that makes the rename durable.
     *
     * The `sync()` above covers the temporary file's CONTENT; the directory entry
     * moved by the rename is another matter and may stay in cache. The rename is
     * atomic but not durable: after a kernel crash or power loss the directory
     * can revert, with only the `.tmp` left. Identity and keyring live here.
     *
     * `Os.open` rather than `FileInputStream`: on Android opening a directory with
     * those classes always fails (`IoBridge` rejects with `EISDIR`), so open(2)
     * is the only way to get a directory descriptor.
     *
     * A failure here does NOT fail the write, hence its own `runCatching`: `fsync`
     * on a directory descriptor is not guaranteed on every filesystem Android
     * mounts, and returning `false` would tell the caller the file is missing
     * while it is complete and in place.
     */
    private fun syncDir(dir: File?) {
        if (dir == null) return
        runCatching {
            val fd = Os.open(dir.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        }
    }

    fun delete(context: Context, name: String) {
        runCatching { file(context, name).delete() }
    }

    private fun file(context: Context, name: String): File =
        File(context.noBackupFilesDir, DIR).apply { mkdirs() }.resolve(name)
}
