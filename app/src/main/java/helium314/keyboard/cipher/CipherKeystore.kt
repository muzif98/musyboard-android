package helium314.keyboard.cipher

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Master key in Android Keystore, and encryption of the blobs that go to disk.
 *
 * The Rust core does no I/O by construction: the identity secret and the keyring
 * leave it as bytes and something must store them. Plaintext in the app's
 * private directory would be pointless (readable on a rooted device or from an
 * extractable backup), so they go through an AES-256-GCM key that lives in
 * Keystore and never leaves it. An attacker copying the files gets ciphertext.
 *
 * Why API 23 rather than 21 (the fork's minSdk): before Marshmallow
 * AndroidKeyStore only does RSA, and [KeyGenParameterSpec] does not exist. Below
 * API 23 encryption is simply unavailable, and that is reported: see
 * [CipherState.Unavailable].
 *
 * Two deliberate key properties:
 *
 *  - `setUnlockedDeviceRequired(true)` (API 28+): the blob cannot be decrypted
 *    while the device is locked. Needed because the files are in
 *    device-protected storage (the app is `directBootAware`, so its directory is
 *    readable before unlock). At-rest protection comes from this line, not from
 *    the filesystem;
 *  - NO `setUserAuthenticationRequired`. It would ask for biometrics or PIN on
 *    every operation, i.e. a prompt in the middle of typing, and users would
 *    turn the feature off.
 */
@RequiresApi(Build.VERSION_CODES.M)
internal object CipherKeystore {

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "keyboard-cipher/v1/master"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** Standard GCM. Not a choice: the only length Keystore uses. */
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val TAG_LEN = TAG_BITS / 8

    /** Version of the on-disk container, not of the message format. */
    private const val VERSION: Byte = 1

    /**
     * Encrypts [plaintext]. The result is `version(1) | iv(12) | ct+tag`.
     *
     * [domain] is the AAD and tells the two files apart: without it an identity
     * blob renamed to keyring would decrypt without complaint, and the failure
     * would show up much later.
     *
     * Keystore chooses the IV, not us: `setRandomizedEncryptionRequired` rejects
     * a caller-supplied IV, which is exactly how GCM gets broken.
     */
    fun wrap(
        domain: ByteArray,
        plaintext: ByteArray,
        creaSeManca: Boolean = false,
    ): ByteArray? = runCatching {
        val key = masterKey(creaSeManca) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(domain)
        val iv = cipher.iv
        if (iv == null || iv.size != IV_LEN) return null
        val ct = cipher.doFinal(plaintext)
        ByteArray(1 + IV_LEN + ct.size).also { out ->
            out[0] = VERSION
            iv.copyInto(out, 1)
            ct.copyInto(out, 1 + IV_LEN)
        }
    }.getOrNull()

    /**
     * Inverse of [wrap]. `null` for any reason: short blob, unknown version,
     * invalid tag, master key gone, device locked.
     *
     * The caller must NOT react by regenerating: `null` here may mean "key lost",
     * and overwriting would destroy the user's identity at the first transient
     * error. See [CipherIdentity].
     */
    fun unwrap(domain: ByteArray, blob: ByteArray): ByteArray? = runCatching {
        if (blob.size < 1 + IV_LEN + TAG_LEN) return null
        if (blob[0] != VERSION) return null
        // NEVER create here. Decrypting is not when a key is born: if it is not
        // there, the only honest answer is "cannot read".
        val key = masterKey(creaSeManca = false) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 1, IV_LEN))
        cipher.updateAAD(domain)
        cipher.doFinal(blob, 1 + IV_LEN, blob.size - 1 - IV_LEN)
    }.getOrNull()

    /**
     * Deletes the master key. From then on no blob already written can be
     * decrypted: data destruction, not cleanup. Only from
     * [CipherIdentity.resetIdentity].
     */
    fun deleteKey() {
        runCatching {
            KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(ALIAS)
        }
    }

    /**
     * The master key. **Generated only when explicitly requested.**
     *
     * "Not there yet" and "there but unreadable now" are the same `null` with
     * opposite consequences: generating in the first case is the first start;
     * generating in the second **replaces the alias** and makes everything saved
     * (identity, keyring, contacts) undecryptable forever, at the first transient
     * hiccup.
     *
     * So first check whether the alias exists. If it exists but the entry is not
     * the expected one (wrong type, invalidated key), return `null` and touch
     * nothing: the caller treats it as "unreadable" and `CipherIdentity` answers
     * `Unreadable` instead of starting over.
     *
     * Replacing the key remains possible, but only where it is a decision
     * (identity creation, backup restore), never as a side effect of a read.
     */
    private fun masterKey(creaSeManca: Boolean): SecretKey? = runCatching {
        val keystore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (keystore.containsAlias(ALIAS)) {
            (keystore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        } else if (creaSeManca) {
            generate()
        } else {
            null
        }
    }.getOrNull()

    /**
     * Three attempts in decreasing order of strength; each falls back to the
     * next, for a different reason.
     *
     * 1. **StrongBox + unlocked device.** StrongBox (API 28+) keeps the key in a
     *    secure element separate from the application processor. There is no way
     *    to know in advance whether the device has one and accepts these
     *    parameters, so it is tried. It fails with `HARDWARE_TYPE_UNAVAILABLE` on
     *    most devices, emulators included.
     *
     * 2. **TEE + unlocked device.** The normal case.
     *
     * 3. **TEE only.** Necessary: on a device **without a screen lock**,
     *    `setUnlockedDeviceRequired(true)` fails at GENERATION with
     *    *"Failed to handle super encryption: User ECDH key missing"* (that
     *    per-user key exists only if the user has a credential). Without this
     *    attempt encryption would be unavailable to anyone without a PIN.
     *
     *    Nothing real is lost: `setUnlockedDeviceRequired` protects data *while
     *    the device is locked*, and a device without a screen lock is never
     *    locked.
     *
     * Observed on an API 34 emulator without a screen lock: the first two fail,
     * the third succeeds.
     */
    private fun generate(): SecretKey? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { build(spec(unlockedRequired = true).setIsStrongBoxBacked(true)) }
                .getOrNull()?.let { return it }
        }
        runCatching { build(spec(unlockedRequired = true)) }.getOrNull()?.let { return it }
        return runCatching { build(spec(unlockedRequired = false)) }.getOrNull()
    }

    private fun spec(unlockedRequired: Boolean): KeyGenParameterSpec.Builder {
        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
        if (unlockedRequired && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
        }
        return builder
    }

    private fun build(builder: KeyGenParameterSpec.Builder): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(builder.build())
        return generator.generateKey()
    }
}
