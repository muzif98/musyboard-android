// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.cipher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contact list parser, tested on malformed input.
 *
 * The blob comes from our core, so it is not hostile, but a corrupted file or a
 * version mismatch after an update produce the same bytes as hostile input. And
 * here an exception means a contacts screen that no longer opens.
 *
 * No Robolectric: pure Kotlin on a `ByteArray`.
 */
class PeerListTest {

    private fun blob(
        versione: Byte = 4,
        conteggio: Int = 1,
        etichetta: String? = "Marco",
        chiave: Byte = 7,
    ): ByteArray {
        val nome = etichetta?.toByteArray() ?: ByteArray(0)
        val out = ArrayList<Byte>()
        out.add(versione)
        for (i in 0 until 4) out.add(((conteggio shr (8 * i)) and 0xFF).toByte())
        for (i in 0 until 32) out.add(chiave)
        for (i in 0 until 8) out.add(0)
        out.add(1) // verificato
        out.add((nome.size and 0xFF).toByte())
        out.add(((nome.size shr 8) and 0xFF).toByte())
        nome.forEach { out.add(it) }
        return out.toByteArray()
    }

    @Test
    fun unElencoValidoSiLegge() {
        val peers = PeerList.parse(blob())
        assertEquals(1, peers?.size)
        assertEquals("Marco", peers?.first()?.label)
        assertTrue(peers?.first()?.verified == true)
    }

    @Test
    fun unaVersioneSconosciutaSiRifiuta() {
        assertNull(PeerList.parse(blob(versione = 9)))
    }

    /** Truncated mid-record: rejected, nothing read past the end. */
    @Test
    fun unBlobTroncatoSiRifiuta() {
        val intero = blob()
        for (taglio in intArrayOf(1, 6, 20, intero.size - 1)) {
            assertNull(
                PeerList.parse(intero.copyOfRange(0, taglio)),
                "un blob di $taglio byte doveva essere rifiutato",
            )
        }
    }

    /**
     * A count that is negative as `Int`: rejected before allocating. Otherwise an
     * absurdly sized `ArrayList` or an endless loop.
     */
    @Test
    fun unConteggioNegativoSiRifiuta() {
        assertNull(PeerList.parse(blob(conteggio = -1)))
    }

    /**
     * A huge but positive count must not allocate: it stops at the first record
     * that does not fit.
     */
    @Test
    fun unConteggioEnormeNonAlloca() {
        assertNull(PeerList.parse(blob(conteggio = 1_000_000)))
    }

    /**
     * The label length is read **unsigned**. Read as signed, a value above 0x7FFF
     * would be negative, pass the bounds check (which compares `offset + len` with
     * the size) and end in `String(bytes, offset, negativeLength)`, i.e. an
     * exception.
     */
    @Test
    fun unaEtichettaLunghissimaSiRifiutaSenzaEccezioni() {
        val corpo = blob(etichetta = "")
        // Rewrite the two length bytes as 0xFFFF: it declares 65535 characters that
        // are not in the blob.
        corpo[corpo.size - 2] = 0xFF.toByte()
        corpo[corpo.size - 1] = 0xFF.toByte()
        assertNull(PeerList.parse(corpo))
    }
}
