// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.cipher

import android.text.InputType
import android.view.inputmethod.EditorInfo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Field classification: the thing that already went wrong twice in production
 * (once by widening exclusions to forms, which turned the encrypted row off
 * inside a major chat app; once by reading the "multi-line" bit outside its
 * class).
 *
 * These fields come from third-party apps and declare what they like: this tests
 * a judgement on input we do not control.
 *
 * **No Robolectric, on purpose.** `EditorInfo` is a plain struct of public
 * fields and `InputType` constants are compiled into the bytecode: no fake
 * Android is needed, which makes this much faster.
 */
class CipherFieldsTest {

    private fun campo(tipo: Int, azione: Int = EditorInfo.IME_ACTION_UNSPECIFIED) =
        EditorInfo().apply {
            inputType = tipo
            imeOptions = azione
        }

    private val testo = InputType.TYPE_CLASS_TEXT

    /**
     * A typical chat composer: multi-line, and it may declare any action on the
     * enter key. Multi-line must win.
     */
    @Test
    fun unCampoMultirigaComponeMessaggiQualunqueAzioneDichiari() {
        for (azione in intArrayOf(
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_PREVIOUS,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_UNSPECIFIED,
        )) {
            val campo = campo(testo or InputType.TYPE_TEXT_FLAG_MULTI_LINE, azione)
            assertFalse(
                CipherFields.nonComponeMessaggi(campo),
                "la riga deve comparire su un campo multiriga (azione $azione)",
            )
        }
    }

    /** Also with autocomplete declared. */
    @Test
    fun laMultirigaVinceAncheSulCompletamentoAutomatico() {
        val campo = campo(
            testo or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE,
        )
        assertFalse(CipherFields.nonComponeMessaggi(campo))
    }

    /**
     * The one exclusion no signal overrides. If multi-line were ever checked before
     * this, the encrypted row would appear on a password field, showing what the
     * field hides.
     */
    @Test
    fun unaPasswordRestaVietataAncheSeMultiriga() {
        val campo = campo(
            testo or InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        )
        assertTrue(CipherFields.vietata(campo))
        assertTrue(CipherFields.nonComponeMessaggi(campo))
    }

    /**
     * The multi-line bit counts only inside `TYPE_CLASS_TEXT`: outside it is some
     * other class's bit and must enable nothing.
     */
    @Test
    fun ilBitMultirigaNonValeFuoriDalTesto() {
        val campo = campo(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        assertTrue(CipherFields.nonComponeMessaggi(campo))
    }

    /** Search bars stay out: single line, "search" action. */
    @Test
    fun unaBarraDiRicercaNonComponeMessaggi() {
        assertTrue(CipherFields.nonComponeMessaggi(campo(testo, EditorInfo.IME_ACTION_SEARCH)))
    }

    /**
     * **The search bar case**, the reason the criterion was inverted. Some search
     * bars declare nothing (text, single line, no action). With a list of contrary
     * evidence they passed every check and got the encrypted row; now, with no
     * supporting evidence, they stay out.
     */
    @Test
    fun unCampoARigaSingolaSenzaAzioneNonComponeMessaggi() {
        assertTrue(CipherFields.nonComponeMessaggi(campo(testo)))
    }

    /**
     * The second supporting evidence: single-line composers exist, and are
     * recognised because they declare "send".
     */
    @Test
    fun unCampoARigaSingolaCheDichiaraInviaComponeMessaggi() {
        assertFalse(CipherFields.nonComponeMessaggi(campo(testo, EditorInfo.IME_ACTION_SEND)))
    }

    /**
     * "Send" is not enough outside text: the class is checked first, and a numeric
     * field declaring "send" stays a numeric field.
     */
    @Test
    fun inviaNonAccendeLaRigaFuoriDalTesto() {
        val campo = campo(InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_ACTION_SEND)
        assertTrue(CipherFields.nonComponeMessaggi(campo))
    }
}
