// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.cipher

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule deciding whether the encrypted row can appear on a field, combined
 * with the main switch.
 *
 * It needs a `Context` (settings live in preferences), so Robolectric is really
 * needed here, unlike [CipherFieldsTest].
 *
 * The two halves are tested together because a reported bug was in their
 * combination: with encryption turned off in settings the row must not appear
 * **on any field**, not even suitable ones.
 */
@RunWith(RobolectricTestRunner::class)
class CipherRigaPrevistaTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun cifratura(accesa: Boolean) {
        context.prefs().edit().putBoolean(CipherSettings.PREF_ENABLED, accesa).apply()
    }

    private val campoDaMessaggi = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }

    private val barraDiRicerca = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_SEARCH
    }

    @Test
    fun conLaCifraturaAccesaLaRigaEPrevistaSuUnCampoDaMessaggi() {
        cifratura(true)
        assertTrue(CipherFields.rigaPrevistaSu(context, campoDaMessaggi))
    }

    /**
     * The reported case: off means off, even where the field would be perfectly
     * suitable.
     */
    @Test
    fun conLaCifraturaSpentaLaRigaNonEMaiPrevista() {
        cifratura(false)
        assertFalse(CipherFields.rigaPrevistaSu(context, campoDaMessaggi))
        assertFalse(CipherFields.rigaPrevistaSu(context, barraDiRicerca))
    }

    /** A search bar stays excluded even with encryption on. */
    @Test
    fun unaBarraDiRicercaRestaEsclusaAncheAccesa() {
        cifratura(true)
        assertFalse(CipherFields.rigaPrevistaSu(context, barraDiRicerca))
    }

    /** No field, no row: no guessing on a focus that does not exist. */
    @Test
    fun senzaCampoNonSiPrevedeNiente() {
        cifratura(true)
        assertFalse(CipherFields.rigaPrevistaSu(context, null))
    }
}
