package helium314.keyboard.cipher

/**
 * Bridge to the Rust core (crate `keyboard-cipher-jni`).
 *
 * Boundary rules, not negotiable:
 *
 *  - secrets cross as `ByteArray`, never as `String`. A `java.lang.String` is
 *    immutable: it cannot be zeroized and stays on the heap until GC. The
 *    caller zeroizes the array as soon as it is done;
 *  - the private key crosses ONCE, at generation. Then it stays in Rust; the
 *    JVM only handles incoming plaintext and outgoing blobs;
 *  - a crypto failure is a single code. The core does not distinguish causes
 *    on purpose, and this layer must not reintroduce the distinction.
 *
 * The operations are X25519 plus an AEAD on short texts: microseconds. They
 * can be called from the main thread.
 */
object CipherCore {

    /**
     * `false` if the `.so` did not load. Every `native` call below must be
     * preceded by this check; in practice going through
     * [CipherIdentity.ensureReady] does it.
     *
     * Loading is wrapped because an `UnsatisfiedLinkError` while initialising
     * this `object` would not stay local: class initialisation would fail and
     * every later access would throw `NoClassDefFoundError`. In a keyboard that
     * is a device you can no longer type on, including the credentials to fix
     * it. A missing library must degrade to "feature unavailable".
     *
     * `Throwable` rather than `UnsatisfiedLinkError`: the breadth is the point.
     */
    @JvmField
    val available: Boolean = try {
        System.loadLibrary("keyboard_cipher_jni")
        true
    } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
        false
    }

    // Return codes. Must match `mod code` in jni/src/lib.rs: an ABI contract,
    // not a convention.
    const val OK = 0
    /** Not one of our blobs. A NORMAL outcome, not an error. */
    const val NOT_OUR_BLOB = 1
    const val FORMAT = 2
    const val UNSUPPORTED_VERSION = 3
    const val DECODE = 4
    /** Any crypto failure. No details, by construction. */
    const val CRYPTO = 5
    const val UNKNOWN_PEER = 6
    const val TIER_UNSUPPORTED = 7
    const val KEYRING = 8
    /** Session not initialised, JNI error, or panic caught in Rust. */
    const val INTERNAL = 9

    // Outcomes of [nativeBlobShape]. They describe the ENVELOPE: what the blob
    // declares about itself in clear, not whether it opened.
    const val SHAPE_UNKNOWN = 0
    const val SHAPE_MESSAGE = 1
    /** Used the chain: only here is "it opens once only" true. */
    const val SHAPE_MESSAGE_FS = 2
    const val SHAPE_GROUP = 3
    const val SHAPE_CARD = 4
    const val SHAPE_BURN = 5
    /** First message with forward secrecy on: its author cannot reopen it. */
    const val SHAPE_MESSAGE_EPHEMERAL = 6
    /** Epoch scheme (forward secrecy off): rereadable until burned. */
    const val SHAPE_MESSAGE_EPOCH = 7
    /**
     * We wrote it, but the recipient is no longer among the contacts. A NORMAL
     * outcome: without forward secrecy one's own message reopens, and this code
     * comes only when its recipient cannot be found.
     *
     * The core returns it **only** where reopening goes through identities
     * (baseline scheme, epoch bootstrap, attachments). Otherwise
     * [OWN_MESSAGE_KEY_GONE] is returned.
     */
    const val OWN_MESSAGE = 10

    /**
     * We wrote it, the recipient is still there, but the recipient-side key it
     * was encrypted to no longer exists.
     *
     * As NORMAL as [OWN_MESSAGE]; the difference is the notice: blaming the
     * address book would be false here. Happens when the recipient has forward
     * secrecy on (the message was sealed to their prekey of that time, which has
     * advanced) or when the conversation was burned.
     */
    const val OWN_MESSAGE_KEY_GONE = 11

    // Values of the `kind` field of [IncomingResult] after nativeHandleIncomingText.
    const val KIND_MESSAGE = 0
    const val KIND_IDENTITY_CARD = 1
    /** Encrypted attachment: see [nativeDecryptFile]. */
    const val KIND_FILE = 2
    /**
     * One of our messages, reopened. `senderKey`, `senderLabel` and
     * `senderFingerprint` are the **recipient's**: there is no sender to show,
     * and the UI must say "to" rather than "from".
     *
     * Only without forward secrecy: with the chain on, the key used to encrypt
     * no longer exists.
     */
    const val KIND_OWN_MESSAGE = 3
    /** One of our attachments, reopened. As [KIND_OWN_MESSAGE]: the peer is the recipient. */
    const val KIND_OWN_FILE = 4
    /**
     * The other side asked to burn the conversation, and we did: the keys that
     * read it no longer exist on this side. No text to show.
     */
    const val KIND_BURNED = 5

    /**
     * Our own card, reopened. **No contact was pinned**, and `senderKey` stays
     * null on purpose: filling it with our own key would invite treating it as a
     * peer.
     */
    const val KIND_OWN_IDENTITY_CARD = 6

    // Values of the `kind` field after nativeAssignLabel.
    const val LABEL_ASSIGNED = 0
    /** The label already belongs to another key: "safety number changed". */
    const val LABEL_CONFLICT = 1

    /**
     * Filled by the Rust side rather than constructed there: allocating objects
     * from JNI requires resolving class and constructor signature at runtime,
     * and every mistake becomes an exception thrown in the middle of a crypto
     * operation. Fields are `@JvmField` because JNI writes fields, not setters.
     *
     * Which fields are set depends on `kind`; the others stay null.
     */
    class IncomingResult {
        @JvmField var kind: Int = -1
        /**
         * 1 if the sender was verified out of band, i.e. the user compared the
         * code in person. The system's only anti-MITM signal: never fill it with
         * anything else.
         */
        @JvmField var verified: Int = 0
        /** Identity cards only: 1 if that key was already known. */
        @JvmField var alreadyPinned: Int = 0

        /**
         * How many people could read this message, sender included. `1` for a
         * one-to-one message. Lets the UI say that a group message has no
         * forward secrecy.
         */
        @JvmField var recipientCount: Int = 0

        /**
         * 1 if the message came from a **group**.
         *
         * A field of its own rather than a threshold on [recipientCount]: a blob
         * built with a single slot would otherwise pass as a one-to-one message
         * while having no forward secrecy.
         */
        @JvmField var isGroup: Int = 0
        /** [KIND_MESSAGE] only. Zeroize after use. */
        @JvmField var plaintext: ByteArray? = null
        /**
         * When the sender claims to have composed the message.
         *
         * Authenticated (it is inside the ciphertext) but NOT verifiable: nobody
         * can prove the sender's clock was right. Show it next to the message so
         * a blob reposted months later stands out. Do not use it for automatic
         * decisions.
         */
        @JvmField var sentAtUnix: Long = 0
        @JvmField var senderFingerprint: String? = null
        /** Name the user gave this key, null if never named. */
        @JvmField var senderLabel: String? = null
        /** Sender public key: used to label or select it. */
        @JvmField var senderKey: ByteArray? = null
        /**
         * [KIND_FILE] only. Name and type come from the sender: authenticated
         * (the AEAD said yes) but **not trustworthy**, because the sender may have
         * written anything. Whoever uses them to save must sanitise them (`../`,
         * path separators).
         */
        @JvmField var fileName: String? = null
        @JvmField var fileMime: String? = null
        /** [KIND_FILE] only. Zeroize after use, like the plaintext. */
        @JvmField var fileContent: ByteArray? = null
        /** [LABEL_CONFLICT] only: the key that already holds that name. */
        @JvmField var existingFingerprint: String? = null
        @JvmField var existingKey: ByteArray? = null
    }

    /**
     * Generates a new identity secret. The only moment the private key crosses
     * the boundary: encrypt it with Android Keystore, persist it, and zeroize
     * the array right after.
     */
    external fun nativeGenerateSecret(): ByteArray?

    /**
     * Initialises the session. `keyringBlob` may be empty on first start.
     * Returns one of the codes above.
     */
    external fun nativeInit(secret: ByteArray, keyringBlob: ByteArray): Int

    /** Identity card blob to insert into the field with `commitText`. */
    external fun nativeIdentityCard(): String?

    external fun nativeMyFingerprint(): String?

    /**
     * Single entry point for all four paths (clipboard, `ACTION_PROCESS_TEXT`,
     * share sheet, input field). The core does not know which one, and must not.
     *
     * @param appPackage from `EditorInfo.packageName` in the IME; in the activity
     *   from `callingActivity`. Empty if unknown, which disables implicit
     *   recipient selection: better to ask than to attribute the message to the
     *   wrong app.
     * @param result pre-allocated object filled by the Rust side.
     */
    external fun nativeHandleIncomingText(
        appPackage: String,
        text: String,
        nowUnix: Long,
        result: IncomingResult,
    ): Int

    /**
     * Exports identity and keyring encrypted with a passphrase.
     *
     * The passphrase is a `ByteArray`, not a `String`: zeroize it right after the
     * call.
     *
     * `null` if anything went wrong: one outcome for all cases, nothing useful to
     * distinguish.
     */
    external fun nativeExportBackup(passphrase: ByteArray): ByteArray?

    /**
     * Opens a backup and **replaces** the current identity and keyring.
     *
     * Destructive. Call only after explicit user confirmation, and persist the
     * result IMMEDIATELY: if the process dies first, the old identity is on disk
     * and the new one in memory.
     *
     * @param secretOut 32-byte array allocated by the caller; receives the secret
     *   to encrypt for storage. Zeroize it as soon as it is used.
     */
    external fun nativeImportBackup(
        blob: ByteArray,
        passphrase: ByteArray,
        secretOut: ByteArray,
    ): Int

    /**
     * The shape of the blob, without decrypting and without touching the keyring.
     *
     * Looks only at what travels in clear (version, kind, flag bits), so it is
     * **not an oracle**: it says neither whether a message opens nor why it did
     * not. Used after a failure, to pick a true sentence instead of listing
     * guesses. Like [nativeLooksLikeOurBlob], needs no initialised session.
     */
    external fun nativeBlobShape(text: String): Int

    /**
     * Tells whether the text *looks like* it contains one of our blobs. No
     * decryption, no keyring access, no side effects.
     *
     * NOT a check: `true` says neither that the blob is intact nor that it is for
     * us. Only for deciding whether to show a hint in the UI.
     *
     * The only entry point that does NOT require [nativeInit]: it only looks at
     * the shape of the text.
     */
    external fun nativeLooksLikeOurBlob(text: String): Boolean

    /**
     * Encrypts for several recipients (decision K).
     *
     * Keys are **concatenated** in one array, 32 bytes each: an array of arrays
     * through JNI costs a local reference per row and a loop to release them,
     * i.e. more `unsafe` code for the same data.
     *
     * The sender is added on the other side and must not be included here.
     *
     * `null` on failure (key never pinned, too many recipients, identity not
     * ready). [errorOut], if not `null`, is an `IntArray(1)` where the bridge
     * stores the reason when the result is `null`: one of the codes at the top
     * of this file. One of them, [UNKNOWN_PEER] (a member forgotten after the
     * group was saved), the user can fix alone.
     */
    external fun nativeEncryptGroup(
        peers: ByteArray,
        plaintext: ByteArray,
        nowUnix: Long,
        errorOut: IntArray?,
    ): String?

    /**
     * Returns the encrypted blob, or null if that app has no recipient.
     *
     * **With `forwardSecrecy` it is no longer read-only:** it generates a new
     * temporary key and stores it in the keyring. The caller must persist
     * immediately, otherwise the other side's reply will be encrypted to a key
     * that died with the process.
     *
     * @param forwardSecrecy with `true` the header key is single-use and yours
     *   does not travel in clear, so whoever obtained it later could not reopen
     *   messages already sent, and neither can you. Such a message **cannot be
     *   opened by an older version**, and the recipient must have the sender
     *   among their contacts: the choice is the caller's because the core does
     *   not know the recipient's version.
     */
    external fun nativeEncryptForApp(
        appPackage: String,
        plaintext: ByteArray,
        nowUnix: Long,
        forwardSecrecy: Boolean,
    ): String?

    /**
     * Burns the conversation with [peer] (decision J).
     *
     * Returns the blob to deliver to the other person, or null if it could not.
     * **When it returns, this side is already done**: the caller must persist
     * immediately, otherwise a dying process leaves the keys on disk.
     *
     * On the other side it is a **request**, not a command: their app must
     * honour it. Do not present it to the user as guaranteed deletion.
     */
    external fun nativeBurnConversation(peer: ByteArray, nowUnix: Long): String?

    external fun nativeSetCurrentPeer(appPackage: String, peer: ByteArray): Int

    /**
     * Is there already a recipient for this app?
     *
     * Tells "I don't know whom to encrypt for" from "encryption failed": the
     * user resolves them differently.
     */
    external fun nativeHasCurrentPeer(appPackage: String): Boolean

    /**
     * The recipient's name for this app: the label, or the fingerprint if it has
     * none. `null` if there is no recipient (information, not an error).
     */
    external fun nativeCurrentPeerName(appPackage: String): String?

    /**
     * Encrypts a file for an **explicitly chosen** peer: there is no originating
     * app to infer the recipient from, and guessing would be the way to send a
     * photo to the wrong person.
     *
     * Returns the attachment bytes, or null if the peer is not in the keyring or
     * encryption fails. The caller zeroizes `content` once delivered.
     *
     * With `forwardSecrecy` it **modifies the keyring** like
     * [nativeEncryptForApp]: the caller must persist immediately.
     */
    external fun nativeEncryptFile(
        peer: ByteArray,
        name: String,
        mime: String,
        content: ByteArray,
        nowUnix: Long,
        forwardSecrecy: Boolean,
        errorOut: IntArray?,
    ): ByteArray?

    /**
     * Opens a received attachment. Fills `fileName`, `fileMime`, `fileContent`,
     * plus the sender fields. Returns one of the codes above.
     */
    external fun nativeDecryptFile(blob: ByteArray, nowUnix: Long, result: IncomingResult): Int

    /**
     * Assigns a name to an already pinned key. This is where TOFU becomes able
     * to say "Mark's key changed".
     *
     * On [LABEL_CONFLICT] it changes NOTHING: it fills `existingFingerprint` and
     * `existingKey`, and the UI shows both fingerprints. Only if the user
     * confirms is [nativeConfirmKeyChange] called, never automatically.
     */
    external fun nativeAssignLabel(peer: ByteArray, label: String, result: IncomingResult): Int

    /** Replaces a pin. ONLY after explicit user confirmation. */
    external fun nativeConfirmKeyChange(oldPeer: ByteArray, newPeer: ByteArray, nowUnix: Long): Int

    external fun nativeMarkVerified(peer: ByteArray): Int

    /**
     * Forgets a peer and stops using it as recipient.
     *
     * The caller must **already have warned the user**: the pin is lost, and the
     * next message from that person appears as an unseen sender and is silently
     * re-pinned, indistinguishable from someone impersonating them.
     */
    external fun nativeForgetPeer(peer: ByteArray): Int

    /**
     * Serialised keyring, to encrypt and persist. **Contains private keys** (the
     * forward secrecy chain prekeys): it must be encrypted before touching disk,
     * and never handed to code that only needs names (use [nativeListPeers]).
     */
    external fun nativeExportKeyring(): ByteArray?

    /**
     * Contact list only, without private keys. Header of 5 bytes (1 version + 4
     * of little-endian count), then one record per peer:
     *
     *     pubkey(32) | firstSeenUnix(8, LE) | verified(1) | labelLen(2, LE) | label(labelLen, UTF-8)
     *
     * Records are NOT fixed-length: the label is variable. Walking them with a
     * constant stride is wrong.
     */
    external fun nativeListPeers(): ByteArray?

    /**
     * Fingerprint of any public key, already formatted. The peer need not be in
     * the keyring: also used to show the incoming key during a conflict, which
     * by definition is not pinned.
     */
    external fun nativeFingerprintOf(peer: ByteArray): String?
}
