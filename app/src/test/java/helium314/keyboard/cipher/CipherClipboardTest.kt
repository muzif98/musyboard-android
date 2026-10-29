// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.cipher

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The marker keeping decrypted text out of the clipboard history (an archive
 * **persisted to disk** by the keyboard) and out of the "paste" suggestion.
 *
 * Works on every Android version, unlike `EXTRA_IS_SENSITIVE` (API 33+): the
 * only defence below that, which is why it is tested.
 *
 * Pure Kotlin: a digest comparison.
 */
class CipherClipboardTest {

    @Test
    fun ilTestoMarcatoVieneRiconosciuto() {
        val chiaro = "ci vediamo alle otto"
        CipherClipboard.markSensitive(chiaro)
        assertTrue(CipherClipboard.isSensitive(chiaro))
    }

    /** Arbitrary text is not sensitive: the history must keep it. */
    @Test
    fun unAltroTestoNonVieneRiconosciuto() {
        CipherClipboard.markSensitive("ci vediamo alle otto")
        assertFalse(CipherClipboard.isSensitive("un indirizzo copiato da una mappa"))
    }

    /**
     * The comparison is exact: a one-character difference must not pass for the
     * same text.
     */
    @Test
    fun unaDifferenzaDiUnCarattereNonPassa() {
        CipherClipboard.markSensitive("segreto")
        assertFalse(CipherClipboard.isSensitive("segretO"))
    }

    /**
     * Marking twice keeps only the last, on purpose: the clipboard holds one item
     * at a time. Pinned by the test because it is a design limit, not an accident.
     */
    @Test
    fun soloLUltimoMarcatoResta() {
        CipherClipboard.markSensitive("primo")
        CipherClipboard.markSensitive("secondo")
        assertTrue(CipherClipboard.isSensitive("secondo"))
        assertFalse(CipherClipboard.isSensitive("primo"))
    }
}
