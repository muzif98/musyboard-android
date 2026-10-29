package helium314.keyboard.cipher

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.security.SecureRandom

/**
 * Encrypted files: photos, voice notes, anything (decision G).
 *
 * ## Why not through the keyboard
 *
 * An IME cannot attach a file. It can insert text and, on Android 7.1+, images
 * with `commitContent`, but the receiving app **recompresses** them, which
 * destroys the ciphertext. Audio cannot be inserted at all. So it starts from a
 * screen and is delivered with the share sheet, which sends the file as a
 * *document*: documents are not recompressed.
 *
 * ## What the platform sees
 *
 * An attachment that opens with nothing: a much stronger marker than a text
 * blob, and its size says much more than a text's length. Accepted residual
 * (G1): without this path photos are sent anyway, in clear, in the same
 * conversation.
 *
 * The **original file name** is not exposed: it is inside the ciphertext.
 * Outside it is `kc-<random>.kc`.
 */
object CipherFiles {

    /** Declared extension (decision G3). */
    private const val EXTENSION = "kc"

    /** Absolute cap, chosen with G6. */
    private const val MAX_BYTES = 50L * 1024 * 1024

    /**
     * The core encrypts in a single in-memory pass: it needs the plaintext, the
     * ciphertext and the copies JNI makes crossing the boundary. Four times the
     * file is a prudent estimate.
     *
     * Hence the real cap: **the smaller of 50 MB and a quarter of the heap**. On
     * a phone that cannot handle the limit the user is told first, instead of
     * waiting for encryption and then dying of OutOfMemory.
     */
    fun maxBytes(context: Context): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return MAX_BYTES
        val heapBytes = manager.memoryClass.toLong() * 1024 * 1024
        return minOf(MAX_BYTES, heapBytes / 4)
    }

    /** Name and size declared by the document provider. */
    class Source(val name: String, val mime: String, val size: Long)

    fun describe(context: Context, uri: Uri): Source {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        var name = uri.lastPathSegment.orEmpty()
        var size = -1L
        val cursor: Cursor? = runCatching {
            resolver.query(uri, null, null, null, null)
        }.getOrNull()
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !it.isNull(nameIndex)) name = it.getString(nameIndex)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
            }
        }
        return Source(name, mime, size)
    }

    /**
     * Encrypts the document at [uri] for [peer] and returns the share intent.
     *
     * `null` when it could not; the caller tells causes apart with [maxBytes] and
     * [describe].
     */
    fun shareIntent(context: Context, peer: ByteArray, uri: Uri, nowUnix: Long): Intent? {
        val source = describe(context, uri)
        val content = readAll(context.contentResolver, uri, maxBytes(context)) ?: return null
        val blob = try {
            CipherCore.nativeEncryptFile(
                peer, source.name, source.mime, content, nowUnix,
                CipherSettings.isForwardSecrecy(context),
                // The reason is not needed here: the caller already distinguishes
                // the cases it can explain, and the bridge accepts `null` for that.
                null,
            )
        } finally {
            // Plaintext does not stay on the heap longer than needed. Not a
            // guarantee (GC may have copied it), but the same rule as the rest of
            // the JNI boundary.
            content.fill(0)
        }
        if (blob == null) return null
        // The temporary key just generated goes to disk before the attachment
        // leaves: otherwise the reply would be encrypted to a key that died with
        // the process. As in `CipherActions.encrypt`: if the keys do not reach the
        // disk, the attachment does not leave. The shared file is not written yet,
        // so cancelling leaves nothing behind.
        if (!CipherIdentity.persistKeyring(context)) return null

        val file = writeShared(context, blob) ?: return null
        val shared = FileProvider.getUriForFile(context, authority(context), file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, shared)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Reads a received attachment and hands it to the core.
     *
     * Returns the core's code, so the caller uses the same messages as for text,
     * including the single opaque error for any crypto failure.
     */
    fun decrypt(
        context: Context,
        uri: Uri,
        nowUnix: Long,
        result: CipherCore.IncomingResult,
    ): Int {
        val blob = readAll(context.contentResolver, uri, maxBytes(context))
            ?: return CipherCore.FORMAT
        return CipherCore.nativeDecryptFile(blob, nowUnix, result)
    }

    /**
     * Reads the whole stream, stopping **above** the limit.
     *
     * One byte more than allowed is read on purpose: the only way to tell "as
     * large as the limit" from "larger" when the declared size is missing or
     * wrong.
     */
    private fun readAll(resolver: ContentResolver, uri: Uri, limit: Long): ByteArray? =
        runCatching {
            resolver.openInputStream(uri)?.use { stream: InputStream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > limit) return@use null
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
        }.getOrNull()

    /**
     * Writes the ciphertext where the FileProvider can expose it.
     *
     * Ciphertext, so not secret, but it accumulates: the folder is cleaned on
     * every share, keeping only the last file, so there is no list of how many
     * files you sent and when.
     */
    private fun writeShared(context: Context, blob: ByteArray): File? = runCatching {
        val dir = File(context.cacheDir, "cipher-share").apply {
            deleteRecursively()
            mkdirs()
        }
        val bytes = ByteArray(4)
        SecureRandom().nextBytes(bytes)
        val suffix = bytes.joinToString("") { "%02x".format(it) }
        File(dir, "kc-$suffix.$EXTENSION").apply { writeBytes(blob) }
    }.getOrNull()

    /**
     * Empties the folder encrypted attachments go out from.
     *
     * Called by full destruction: those files reveal no content, but their
     * presence says **when** you sent something.
     */
    fun svuotaCondivisione(context: Context) {
        runCatching { File(context.cacheDir, "cipher-share").deleteRecursively() }
    }

    private fun authority(context: Context) = context.packageName + ".cipherfiles"
}
