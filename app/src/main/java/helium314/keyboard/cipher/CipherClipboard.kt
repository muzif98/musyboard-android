package helium314.keyboard.cipher

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import java.security.MessageDigest

/**
 * Path 1: the blob comes from the clipboard.
 *
 * The path that works everywhere, and the only one not depending on what the
 * chat app does: the other three need the standard selection toolbar, a share
 * sheet, or the blob already in the field served by the IME.
 *
 * ## Why an IME can read the clipboard
 *
 * Since Android 10 access is restricted: *"unless your app is the default IME or
 * is the app that currently has focus, your app cannot access clipboard data"*.
 * This keyboard is the default IME while in use. HeliBoard already reads the
 * clipboard for its own history, so the marginal privacy cost is nil.
 *
 * ## The Android 12 toast, and how to avoid it
 *
 * Since Android 12 the system shows "app X pasted from your clipboard" the
 * FIRST time an app reads data another app put there. Checking the content to
 * know whether there is something to decrypt would trigger it at every typing
 * session.
 *
 * [ClipboardManager.getPrimaryClipDescription] NEVER triggers it: it gives the
 * MIME type without the text. Hence the split below:
 *
 *  - [hasText] looks at the description. Can be called any time;
 *  - [read] returns the content. Only on an explicit user gesture.
 *
 * Calling [read] "to see whether a blob happens to be there" would turn a useful
 * feature into an app that seems to spy on the clipboard.
 */
internal object CipherClipboard {

    /**
     * Is there text on the clipboard? Says nothing about whether it is our blob
     * (that would require reading it), only whether it is worth asking.
     */
    fun hasText(context: Context): Boolean {
        val manager = manager(context) ?: return false
        return runCatching {
            manager.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
        }.getOrDefault(false)
    }

    /**
     * The clipboard content. **Only after an explicit gesture**: this call shows
     * the system toast and, above all, really reads what the user copied, which
     * can be anything.
     */
    fun read(context: Context): CharSequence? {
        val manager = manager(context) ?: return null
        return runCatching {
            val clip = manager.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            clip.getItemAt(0)?.coerceToText(context)
        }.getOrNull()
    }

    /**
     * Clears the system clipboard.
     *
     * Called by full destruction. The most exposed surface here: whatever is on
     * the clipboard can be read by any app asking for it, and survives the
     * destruction of every key.
     *
     * `clearPrimaryClip` exists since API 28; below that it is overwritten with an
     * empty string, which still leaves an item but removes the content.
     */
    fun svuota(context: Context) {
        val clip = manager(context) ?: return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                clip.clearPrimaryClip()
            } else {
                clip.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
            }
        }
    }

    private fun manager(context: Context): ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    // ========================================================================
    // Hint: there is something to decrypt on the clipboard
    // ========================================================================

    @Volatile
    private var clipLooksLikeOurs = false

    /**
     * Called by `ClipboardHistoryManager` **on text it already read**.
     *
     * The only honest way to feed the hint. Looking at the content ourselves would
     * mean reading the clipboard at every typing session (with the Android 12+
     * toast). Here the read already happened for the history, so the check is
     * free.
     *
     * Declared consequence: **with clipboard history disabled the hint does not
     * light up.** The price of not reading behind the user's back.
     */
    fun noteClipboardContent(text: CharSequence) {
        clipLooksLikeOurs = CipherCore.available &&
            runCatching { CipherCore.nativeLooksLikeOurBlob(text.toString()) }.getOrDefault(false)
    }

    /**
     * Like [noteClipboardContent], and also tells the keep-alive notification what
     * it saw.
     *
     * Tells apart two failures that look alike: the clipboard listener **not
     * firing at all** (no process, or no longer the default keyboard) and the
     * listener firing but **not recognising** the blob. In the first case the
     * notification line never changes.
     */
    fun noteClipboardContent(context: Context, text: CharSequence) {
        noteClipboardContent(text)
        if (CipherSettings.isKeepAlive(context)) {
            runCatching { CipherKeepAlive.segnalaCopia(context, clipLooksLikeOurs) }
        }
    }

    /**
     * Whether the "decrypt" key should look active.
     *
     * Promises nothing: there is something on the clipboard shaped like our blob.
     * It may be truncated, of another version, or for another recipient.
     */
    fun clipboardLooksDecryptable(): Boolean = clipLooksLikeOurs

    // ========================================================================
    // Exclusion from the keyboard's clipboard history
    // ========================================================================

    /**
     * Digest of the last content we put on the clipboard that must not enter the
     * history.
     *
     * **A hash, not the text.** Keeping plaintext in a static field of the IME
     * would be exactly the leak this exists to avoid. A digest recognises the
     * content without allowing it to be rebuilt.
     */
    @Volatile
    private var sensitiveDigest: ByteArray? = null

    /**
     * Declares that the next clipboard content must not be stored in history.
     *
     * **Call BEFORE `setPrimaryClip`**: the history listener may fire during that
     * call, and a marker set afterwards would be too late.
     */
    fun markSensitive(text: CharSequence) {
        sensitiveDigest = digest(text)
    }

    /**
     * The core of the trap described in `DecryptActivity.copyPlaintext`: the
     * system clipboard is read by the default IME, i.e. THIS keyboard, which
     * keeps a history that can be persisted to disk.
     *
     * `EXTRA_IS_SENSITIVE` is not enough: it hides the system preview, does not
     * stop our history from collecting the text, and does not exist below
     * Android 13. This check works on every version.
     */
    fun isSensitive(text: CharSequence): Boolean {
        val expected = sensitiveDigest ?: return false
        val actual = digest(text) ?: return false
        return expected.contentEquals(actual)
    }

    private fun digest(text: CharSequence): ByteArray? = runCatching {
        MessageDigest.getInstance("SHA-256").digest(text.toString().toByteArray())
    }.getOrNull()
}
