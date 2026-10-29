// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.cipher

import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The parts queue surviving the keyboard's death, tested **on a device**.
 *
 * Instrumented rather than on the JVM: the half that matters goes through
 * Android Keystore (absent on the JVM) and `noBackupFilesDir`, a real
 * filesystem. JVM tests cover the encoder and its inverse; this covers what lies
 * between them, where a half-sent message would be lost.
 *
 * WARNING: if the device has no identity yet, [Before] creates one. Run on a
 * test emulator.
 */
@RunWith(AndroidJUnit4::class)
class CipherPartiPersistenzaTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** `180225` is a multi-line text field, like a chat composer. */
    private fun campo(pacchetto: String = "com.whatsapp", id: Int = 42) =
        EditorInfo().apply {
            packageName = pacchetto
            fieldId = id
            inputType = 180225
        }

    /**
     * Without an identity there is no master key, and without it [CipherParti]
     * writes nothing, on purpose: a queue exists only after something was
     * encrypted, and encrypting requires the identity.
     */
    @Before
    fun identitaProntaECodaVuota() {
        assertTrue("il core nativo non si e' caricato", CipherCore.available)
        assertEquals(CipherState.Ready, CipherIdentity.ensureReady(context))
        CipherParti.scarta()
        CipherParti.salva(context)
        assertFalse(CipherStorage.exists(context, CipherStorage.PARTS))
    }

    @After
    fun nonLasciareCode() {
        CipherParti.scarta()
        CipherParti.salva(context)
    }

    /**
     * The case all this exists for: the keyboard dies between two parts, and on
     * restart the rest of the message is still there.
     *
     * `scarta()` in the middle stands for the process dying: it clears in-memory
     * state without touching the disk, which is what really happens when Android
     * kills the keyboard.
     */
    @Test
    fun laCodaTornaDopoCheLaTastieraEMorta() {
        val c = campo()
        CipherParti.accoda(c, listOf("kc/seconda", "kc/terza"), 3, dallaRiga = true)
        CipherParti.consegnata("kc/prima")
        CipherParti.salva(context)
        assertTrue("la coda non e' arrivata su disco", CipherStorage.exists(context, CipherStorage.PARTS))

        CipherParti.scarta()
        assertFalse("lo stato in memoria doveva sparire", CipherParti.inAttesaSu(c))

        CipherParti.ripristina(context)
        assertTrue("la coda non e' tornata", CipherParti.inAttesaSu(c))
        assertEquals("kc/seconda", CipherParti.prossima(c))
        assertEquals(Pair(2, 3), CipherParti.prossimaEtichetta())
        assertTrue("si e' persa la memoria di come era uscita la prima parte", CipherParti.daRiga())
        assertEquals("kc/prima".length, CipherParti.ultimaLunghezza())
    }

    /** And it is delivered only where it started: another chat does not get it. */
    @Test
    fun laCodaRipristinataRestaLegataAlSuoCampo() {
        CipherParti.accoda(campo(id = 42), listOf("kc/seconda"), 2, dallaRiga = false)
        CipherParti.salva(context)
        CipherParti.scarta()
        CipherParti.ripristina(context)

        assertFalse(CipherParti.inAttesaSu(campo(id = 99)))
        assertFalse(CipherParti.inAttesaSu(campo(pacchetto = "org.telegram.messenger")))
        assertTrue(CipherParti.inAttesaSu(campo(id = 42)))
    }

    /**
     * When the queue is done the file disappears. Keeping it would waste space and
     * leave a trace that you were writing to someone at that time.
     */
    @Test
    fun ilFileSparisceQuandoLaCodaFinisce() {
        val c = campo()
        CipherParti.accoda(c, listOf("kc/seconda"), 2, dallaRiga = true)
        CipherParti.salva(context)
        assertTrue(CipherStorage.exists(context, CipherStorage.PARTS))

        CipherParti.consuma()
        CipherParti.salva(context)
        assertFalse("il file e' rimasto a coda finita", CipherStorage.exists(context, CipherStorage.PARTS))
    }

    /**
     * The blobs are already encrypted for the recipient, but they still do not go
     * to disk in clear: a readable file would tell whoever looks at the phone that
     * you were writing to someone, and when. Checks the Keystore wrapping really
     * happens.
     */
    @Test
    fun suDiscoIlBlobNonSiLegge() {
        val riconoscibile = "kc/questaStringaNonDeveComparireSulDisco"
        CipherParti.accoda(campo(), listOf(riconoscibile), 2, dallaRiga = true)
        CipherParti.salva(context)

        val bytes = CipherStorage.read(context, CipherStorage.PARTS)
        assertNotNull("il file non c'e'", bytes)
        val comeTesto = String(bytes!!, Charsets.ISO_8859_1)
        assertFalse(
            "il blob si legge in chiaro sul disco: il giro di Keystore non c'e' stato",
            comeTesto.contains(riconoscibile),
        )
        // Nor the chat's package name, the most telling metadata.
        assertFalse(comeTesto.contains("com.whatsapp"))
    }

    /**
     * A file that does not open does not become a random queue and is not left to
     * be found at the next start.
     */
    @Test
    fun unFileIlleggibileNonProduceUnaCodaEVieneButtato() {
        assertTrue(
            CipherStorage.write(context, CipherStorage.PARTS, ByteArray(64) { it.toByte() }),
        )
        CipherParti.ripristina(context)
        assertFalse(CipherParti.inAttesaSu(campo()))
        assertNull(CipherParti.prossima(campo()))
        assertFalse(
            "un file illeggibile e' rimasto su disco",
            CipherStorage.exists(context, CipherStorage.PARTS),
        )
    }

    /** No file, no queue: the first start, not a fault. */
    @Test
    fun senzaFileNonSuccedeNiente() {
        CipherParti.ripristina(context)
        assertFalse(CipherParti.inAttesaSu(campo()))
    }
}
