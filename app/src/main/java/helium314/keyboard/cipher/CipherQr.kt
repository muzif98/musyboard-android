package helium314.keyboard.cipher

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Generates the QR code of our identity card.
 *
 * ## What it is really for
 *
 * TOFU accepts one precise risk: at the **first** contact there is no way to
 * know whether the incoming key really belongs to who it claims. From then on
 * the pin protects, but that first exchange stays exposed. Meeting in person and
 * scanning a code is the only thing that closes it, which is why this must be
 * easy to reach.
 *
 * ## Shown, never scanned (closed decision)
 *
 * Only generation is here, and **it stays that way**. Reading a QR code needs
 * `CAMERA`, and the fork has no permissions: that is its main property.
 *
 * It is not needed either, because **one side scanning is enough**: the other
 * person scans this code with any QR reader, gets the `kc/...` string and hands
 * it to our activity through the share sheet. First-contact MITM is closed
 * anyway, and the keyboard stays permission-free. See the core's `DESIGN.md`.
 *
 * ## What it contains
 *
 * Exactly the string the "encrypt" key inserts into the field: the identity
 * card, i.e. a **public** key with checksum and padding. No secret here; the
 * activity showing it still has `FLAG_SECURE`.
 */
internal object CipherQr {

    /**
     * Level M: about 15% redundancy.
     *
     * Not the maximum, on purpose: more error correction means more modules for
     * the same data, so smaller modules on screen and *harder* reading. Here the
     * code is read up close on a clean display.
     */
    private val CORRECTION = ErrorCorrectionLevel.M

    /**
     * Margin in modules. The standard asks for 4; 2 is enough on screen and
     * leaves more room for real modules. Below this some readers no longer lock
     * on.
     */
    private const val MARGIN = 2

    /**
     * Builds the bitmap. `null` if the string does not fit or something fails:
     * the caller shows the text string, which can still be read aloud.
     */
    fun encode(content: String, sizePx: Int): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to CORRECTION,
            EncodeHintType.MARGIN to MARGIN,
            // The alphabet is ASCII (lowercase z-base-32 plus "kc/"), so
            // ISO-8859-1 keeps ZXing from declaring a UTF-8 ECI: extra bytes for
            // nothing.
            EncodeHintType.CHARACTER_SET to "ISO-8859-1",
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        // Always plain black and white, never theme colours: a low-contrast QR, or
        // light modules on a dark theme, is a QR readers get wrong.
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }.getOrNull()
}
