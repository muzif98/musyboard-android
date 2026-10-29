// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.cipher

import android.view.inputmethod.EditorInfo
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Splitting a message that is too long.
 *
 * Tests the one thing [CipherParti.dividi] promises: every part fits under the
 * cap, marker included, and joining them gives back exactly the original text.
 * The second half is what matters: splitting a message and losing a piece would
 * be worse than the refusal this function replaces.
 *
 * **No Robolectric**, like [CipherFieldsTest]: `dividi` does not touch Android.
 */
class CipherPartiTest {

    /** A fake cap in bytes, in place of the blob estimate. */
    private fun tetto(byte: Int): (String) -> Boolean = { it.toByteArray().size <= byte }

    private fun senzaContrassegni(parti: List<String>) =
        parti.joinToString("") { it.substringAfter(") ") }

    @Test
    fun ogniParteStaNelTetto() {
        val testo = "parola ".repeat(200)
        val ciSta = tetto(300)
        val parti = assertNotNull(CipherParti.dividi(testo, ciSta))
        assertTrue(parti.size > 1, "un testo cosi' deve venire spezzato")
        for (parte in parti) {
            assertTrue(ciSta(parte), "questa parte sfora il tetto: ${parte.length} caratteri")
        }
    }

    /**
     * The property everything else exists for: nothing is lost.
     */
    @Test
    fun lePartiRimesseInsiemeDannoIlTestoDiPartenza() {
        val testo = "Questo e' un messaggio lungo, con virgole, a capo\ne spazi. " .repeat(30)
        val parti = assertNotNull(CipherParti.dividi(testo, tetto(300)))
        assertTrue(parti.size > 1, "un testo cosi' deve venire spezzato")
        assertEquals(testo, senzaContrassegni(parti))
    }

    /** The marker says which part and how many there are, starting from one. */
    @Test
    fun ilContrassegnoNumeraLeParti() {
        val parti = assertNotNull(CipherParti.dividi("x".repeat(500), tetto(100)))
        val quante = parti.size
        parti.forEachIndexed { i, parte ->
            assertTrue(
                parte.startsWith("(${i + 1}/$quante) "),
                "contrassegno sbagliato: ${parte.take(10)}",
            )
        }
    }

    /**
     * Emoji are surrogate pairs: cutting in the middle produces two half
     * characters. The joined text would still look whole, so the check is on the
     * individual parts.
     */
    @Test
    fun nonSiTagliaInMezzoAUnaCoppiaSurrogata() {
        val testo = "🔐".repeat(300)
        val parti = assertNotNull(CipherParti.dividi(testo, tetto(200)))
        assertTrue(parti.size > 1)
        for (parte in parti) {
            assertTrue(!parte.last().isHighSurrogate(), "parte finita a meta' di un'emoji")
            assertTrue(!parte.first().isLowSurrogate(), "parte cominciata a meta' di un'emoji")
        }
        assertEquals(testo, senzaContrassegni(parti))
    }

    /** Where there is a space near the cut, cut there. */
    @Test
    fun ilTaglioPreferisceLoStaccoDiParola() {
        val testo = ("parolalunga ").repeat(40)
        val parti = assertNotNull(CipherParti.dividi(testo, tetto(100)))
        // The first part ends with a space, not halfway through "parolalunga".
        assertTrue(parti.first().endsWith(" "), "ha tagliato dentro una parola: ${parti.first()}")
    }

    /**
     * Beyond the parts cap it goes back to refusing: splitting into twenty
     * messages serves nobody.
     */
    @Test
    fun oltreIlMassimoDellePartiNonSiSpezza() {
        val testo = "x".repeat(100_000)
        assertNull(CipherParti.dividi(testo, tetto(100)))
    }

    /** A text that already fits gets no marker. */
    @Test
    fun unTestoCheCiStaTornaIntatto() {
        val parti = assertNotNull(CipherParti.dividi("corto", tetto(100)))
        assertEquals(listOf("corto"), parti)
    }

    @Test
    fun unTestoVuotoNonSiSpezza() {
        assertNull(CipherParti.dividi("", tetto(100)))
    }

    // --- The queue ---------------------------------------------------------

    /**
     * [CipherParti] is one object for the whole process: one test's queue would
     * leak into the next. Discard before each.
     */
    @BeforeTest
    fun codaPulita() {
        CipherParti.scarta()
    }

    private fun campo(pacchetto: String = "com.whatsapp", id: Int = 7) =
        EditorInfo().apply {
            packageName = pacchetto
            fieldId = id
            inputType = 180225
        }

    /**
     * **The bug this test blocks.** The part used to be removed from the queue
     * before trying to deliver it: a failed `commitText` left it out of the queue
     * and never delivered. Looking must not consume.
     */
    @Test
    fun guardareLaProssimaParteNonLaToglieDallaCoda() {
        val c = campo()
        CipherParti.accoda(c, listOf("due", "tre"), 3, dallaRiga = true)
        assertEquals("due", CipherParti.prossima(c))
        assertEquals("due", CipherParti.prossima(c), "guardare due volte da' la stessa parte")
        assertEquals(Pair(2, 3), CipherParti.prossimaEtichetta())
        assertTrue(CipherParti.inAttesaSu(c))
    }

    /** Advance only after the field took the part. */
    @Test
    fun laCodaAvanzaSoloConConsuma() {
        val c = campo()
        CipherParti.accoda(c, listOf("due", "tre"), 3, dallaRiga = true)
        CipherParti.consuma()
        assertEquals("tre", CipherParti.prossima(c))
        assertEquals(Pair(3, 3), CipherParti.prossimaEtichetta())
        CipherParti.consuma()
        assertFalse(CipherParti.inAttesaSu(c), "finita la coda, non resta niente")
        assertNull(CipherParti.prossima(c))
    }

    /**
     * One chat app holds every conversation: a part delivered after switching
     * chat would go to another person.
     */
    @Test
    fun laCodaNonSiConsegnaSuUnAltroCampo() {
        CipherParti.accoda(campo(id = 7), listOf("due"), 2, dallaRiga = true)
        val altraChat = campo(id = 9)
        assertFalse(CipherParti.inAttesaSu(altraChat))
        assertNull(CipherParti.prossima(altraChat))
        // Nor in another app.
        assertFalse(CipherParti.inAttesaSu(campo(pacchetto = "org.telegram.messenger")))
    }

    /**
     * Without a field identity a queue cannot be kept, and that must be known
     * **before** splitting: saying it afterwards would mean having announced
     * "part 1 of 3" and never delivering the rest.
     */
    @Test
    fun senzaIdentitaDelCampoNonSiTieneUnaCoda() {
        assertFalse(CipherParti.campoUtilizzabile(null))
        assertFalse(CipherParti.campoUtilizzabile(EditorInfo()))
        assertTrue(CipherParti.campoUtilizzabile(campo()))
        CipherParti.accoda(null, listOf("due"), 2, dallaRiga = true)
        assertFalse(CipherParti.inAttesaSu(campo()))
    }

    /**
     * The length of the last delivered part tells a field emptied **by a send**
     * from one emptied by hand. Otherwise the next part would reappear in front of
     * someone who just deleted the previous one so as not to send it.
     */
    @Test
    fun laCodaRicordaQuantoEraLungaLUltimaParteConsegnata() {
        val c = campo()
        CipherParti.accoda(c, listOf("due"), 2, dallaRiga = true)
        assertEquals(0, CipherParti.ultimaLunghezza(), "prima di consegnare non c'e' niente da confrontare")
        CipherParti.consegnata("kc/unblobqualunque")
        assertEquals("kc/unblobqualunque".length, CipherParti.ultimaLunghezza())
        CipherParti.scarta()
        assertEquals(0, CipherParti.ultimaLunghezza())
    }

    /**
     * Where the first part came from decides how the others leave: in field mode
     * the fork never sends by itself.
     */
    @Test
    fun laCodaRicordaSeLaPrimaParteVenivaDallaRiga() {
        val c = campo()
        CipherParti.accoda(c, listOf("due"), 2, dallaRiga = false)
        assertFalse(CipherParti.daRiga())
        CipherParti.accoda(c, listOf("due"), 2, dallaRiga = true)
        assertTrue(CipherParti.daRiga())
        CipherParti.scarta()
        assertFalse(CipherParti.daRiga(), "buttata la coda, non resta nemmeno quello")
    }

    /**
     * The full save round trip, without Keystore: what is written to disk must
     * come back identical. This is where a queue would be silently corrupted.
     */
    @Test
    fun laCodaScrittaSuDiscoTornaIndietroUguale() {
        val c = campo(pacchetto = "com.whatsapp", id = 42)
        CipherParti.accoda(c, listOf("kc/secondo", "kc/terzo"), 3, dallaRiga = true)
        CipherParti.consegnata("kc/primo")
        val bytes = CipherParti.codifica(Triple("com.whatsapp", 42, 180225))
        CipherParti.scarta()
        assertFalse(CipherParti.inAttesaSu(c))

        assertTrue(CipherParti.decodifica(bytes))
        assertTrue(CipherParti.inAttesaSu(c))
        assertEquals("kc/secondo", CipherParti.prossima(c))
        assertEquals(Pair(2, 3), CipherParti.prossimaEtichetta())
        assertTrue(CipherParti.daRiga())
        assertEquals("kc/primo".length, CipherParti.ultimaLunghezza())
        CipherParti.consuma()
        assertEquals("kc/terzo", CipherParti.prossima(c))
    }

    /** A corrupted file does not become a random queue: it is rejected. */
    @Test
    fun unFileCorrottoNonProduceUnaCoda() {
        val c = campo()
        CipherParti.accoda(c, listOf("kc/due"), 2, dallaRiga = false)
        val bytes = CipherParti.codifica(Triple("com.whatsapp", 7, 180225))
        CipherParti.scarta()
        // Versione sbagliata.
        val versione = bytes.copyOf().also { it[0] = 9 }
        assertFalse(CipherParti.decodifica(versione))
        // Troncato a meta'.
        assertFalse(runCatching { CipherParti.decodifica(bytes.copyOf(bytes.size / 2)) }.getOrDefault(false))
        assertFalse(CipherParti.inAttesaSu(c))
    }
}
