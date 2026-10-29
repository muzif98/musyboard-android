// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.cipher

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The full backup round trip, tested **on a device**.
 *
 * Instrumented rather than on the JVM: it goes through the native core (the
 * `.so` is an Android binary and does not load on the JVM) and Android Keystore,
 * which does not exist there. Those are exactly what the backup depends on.
 *
 * Backup is **the only way** to move an identity between installations (for
 * example when the signing key changes and everyone must reinstall), so it must
 * be known to work.
 *
 * WARNING: this test **replaces the identity** of the device it runs on. Run it
 * only on a test emulator.
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun identitaPronta(): String {
        assertTrue("il core nativo non si e' caricato", CipherCore.available)
        assertEquals(CipherState.Ready, CipherIdentity.ensureReady(context))
        val impronta = CipherCore.nativeMyFingerprint()
        assertNotNull("nessuna impronta: identita' non pronta", impronta)
        return impronta!!
    }

    /**
     * Export, wipe everything, re-import: the identity must be the same.
     *
     * The fingerprint is the right way to check (it is what two people compare
     * aloud), not the raw key, which must not even leave the core.
     */
    @Test
    fun esportaEReimportaMantieneLIdentita() {
        val prima = identitaPronta()
        val passphrase = "una passphrase lunga abbastanza".toByteArray()

        val blob = CipherIdentity.exportBackup(passphrase.copyOf())
        assertNotNull("l'esportazione non ha prodotto niente", blob)

        // Destroy everything, as an uninstall would.
        CipherIdentity.resetIdentity(context)
        val dopoIlReset = CipherCore.nativeMyFingerprint()
        assertNotEquals("il reset non ha cambiato identita'", prima, dopoIlReset)

        val esito = CipherIdentity.importBackup(context, blob!!, passphrase.copyOf())
        assertEquals(CipherState.Ready, esito)
        assertEquals("l'identita' non e' tornata quella di prima", prima, CipherCore.nativeMyFingerprint())
    }

    /** A wrong passphrase does not open, and must not leave half a state. */
    @Test
    fun unaPassphraseSbagliataNonApre() {
        identitaPronta()
        val blob = CipherIdentity.exportBackup("giusta".toByteArray())
        assertNotNull(blob)
        val prima = CipherCore.nativeMyFingerprint()
        val esito = CipherIdentity.importBackup(context, blob!!, "sbagliata".toByteArray())
        assertNotEquals(CipherState.Ready, esito)
        assertEquals("un import fallito ha comunque toccato l'identita'", prima, CipherCore.nativeMyFingerprint())
    }

    /**
     * An empty passphrase does not produce a backup: Argon2 would accept it, and
     * the result would be a file **that looks encrypted** and opens for anyone who
     * presses enter. The rule is in the core; this checks it reaches the app.
     */
    @Test
    fun unaPassphraseVuotaNonProduceBackup() {
        identitaPronta()
        assertNull(CipherIdentity.exportBackup(ByteArray(0)))
    }
}
