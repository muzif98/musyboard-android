package helium314.keyboard.cipher

import androidx.core.content.FileProvider
import helium314.keyboard.latin.R

/**
 * Exposes to the chat app only the encrypted attachment just produced.
 *
 * A subclass rather than androidx's `FileProvider` used directly: two providers
 * with the same class and different authorities do not coexist well, and this
 * fork already has two others. An own class also makes it obvious, reading the
 * manifest, which folder each one exposes.
 */
class CipherFileProvider : FileProvider(R.xml.cipher_file_path)
