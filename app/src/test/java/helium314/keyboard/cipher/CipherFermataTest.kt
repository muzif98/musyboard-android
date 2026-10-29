// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.cipher

import android.app.ApplicationExitInfo
import helium314.keyboard.cipher.CipherFermata.Motivo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How the system's exit reasons are read.
 *
 * One boundary matters: which reasons make the notice say "it was force
 * stopped". Saying it for a crash or a low-memory kill would send the user to the
 * battery settings for a problem that is ours.
 *
 * No Robolectric: the constants are integers inlined at compile time.
 */
class CipherFermataTest {

    @Test
    fun larrestoForzatoELaSolaFermata() {
        val fermate = (0..32).filter { CipherFermata.classifica(it) == Motivo.FERMATA }
        assertEquals(
            listOf(ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED),
            fermate,
        )
    }

    @Test
    fun crashEMemoriaNonSonoFermate() {
        assertEquals(Motivo.CRASH, CipherFermata.classifica(ApplicationExitInfo.REASON_CRASH))
        assertEquals(Motivo.CRASH, CipherFermata.classifica(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals(Motivo.CRASH, CipherFermata.classifica(ApplicationExitInfo.REASON_ANR))
        assertEquals(Motivo.MEMORIA, CipherFermata.classifica(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals(Motivo.AGGIORNATA, CipherFermata.classifica(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
    }

    @Test
    fun unCodiceSconosciutoNonIncolpaNessuno() {
        // Our own killProcess after unlock arrives as SIGNALED.
        assertEquals(Motivo.ALTRO, CipherFermata.classifica(ApplicationExitInfo.REASON_SIGNALED))
        assertEquals(Motivo.ALTRO, CipherFermata.classifica(ApplicationExitInfo.REASON_UNKNOWN))
        assertEquals(Motivo.ALTRO, CipherFermata.classifica(9999))
    }
}
