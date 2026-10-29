package helium314.keyboard.cipher

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fingerprints of forgotten contacts, with the name they had ("tombstones").
 *
 * ## Why it exists
 *
 * Forgetting a contact deletes the pin, and with it the system's only anti-MITM
 * signal: the next key that arrives is pinned as new, silently, because there is
 * nothing left to compare it with. That is the window in which an impersonator
 * passes unnoticed. The tombstone reopens that comparison. It is not
 * cryptography: it is the memory of what you wrote yourself.
 *
 * ## What it can say, and what not
 *
 * The app **knows keys, not people**: a contact is a key plus a user label. So:
 *
 *  - **same fingerprint** returning: reliable, the key compared with itself.
 *    "This is the one you forgot";
 *  - **same name on a different fingerprint**: the link is the name the user
 *    types, not cryptography. It does not claim "same person"; it shows a
 *    contradiction in the user's own records, the same signal TOFU would give
 *    with "key changed", which forgetting had disarmed.
 *
 * It cannot tell "changed phone" from "impersonation": indistinguishable from
 * inside. It only says to stop and verify out of band.
 *
 * ## The declared price
 *
 * **"Forget" no longer forgets completely**: a trace of whom you talked to
 * remains, on the phone, encrypted and outside backups. A deliberate choice: the
 * chat platform has that metadata anyway, while silence in front of a MITM is
 * not compensated by anything. The "Forget" notice says so.
 *
 * **Burning** does not write here: it exists to leave no trace.
 */
internal object CipherLapidi {

    private val AAD = "keyboard-cipher/v1/storage/tombstones".toByteArray()

    private const val VERSION: Byte = 1

    /** A forgotten contact: fingerprint, the name it had, when. */
    class Lapide(val impronta: String, val nome: String, val quando: Long)

    fun ricorda(context: Context, impronta: String, nome: String) {
        if (impronta.isBlank()) return
        val tutte = carica(context).filterNot { it.impronta == impronta }
        salva(context, tutte + Lapide(impronta, nome, System.currentTimeMillis() / 1000))
    }

    /** The tombstone of this exact key, if it was already forgotten. */
    fun perImpronta(context: Context, impronta: String): Lapide? =
        carica(context).firstOrNull { it.impronta == impronta }

    /**
     * A tombstone with this name but a **different** fingerprint: the
     * contradiction worth showing.
     */
    fun conflitto(context: Context, nome: String, impronta: String): Lapide? {
        val cercato = nome.trim()
        if (cercato.isEmpty()) return null
        return carica(context).firstOrNull {
            it.nome.equals(cercato, ignoreCase = true) && it.impronta != impronta
        }
    }

    /** When a key comes back into the contacts, its tombstone is no longer needed. */
    fun scorda(context: Context, impronta: String) {
        val restanti = carica(context).filterNot { it.impronta == impronta }
        salva(context, restanti)
    }

    private fun carica(context: Context): List<Lapide> {
        val blob = CipherStorage.read(context, CipherStorage.TOMBSTONES) ?: return emptyList()
        val plain = CipherKeystore.unwrap(AAD, blob) ?: return emptyList()
        return try {
            runCatching { decodifica(plain) }.getOrDefault(emptyList())
        } finally {
            plain.fill(0)
        }
    }

    private fun salva(context: Context, lapidi: List<Lapide>) {
        val plain = codifica(lapidi)
        val blob = try {
            CipherKeystore.wrap(AAD, plain) ?: return
        } finally {
            plain.fill(0)
        }
        CipherStorage.write(context, CipherStorage.TOMBSTONES, blob)
    }

    private fun codifica(lapidi: List<Lapide>): ByteArray {
        val pezzi = lapidi.map { it.impronta.toByteArray() to it.nome.toByteArray() }
        val size = 1 + 4 + pezzi.sumOf { (i, n) -> 2 + i.size + 2 + n.size + 8 }
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(VERSION)
        buffer.putInt(pezzi.size)
        for ((indice, pezzo) in pezzi.withIndex()) {
            val (impronta, nome) = pezzo
            buffer.putShort(impronta.size.toShort())
            buffer.put(impronta)
            buffer.putShort(nome.size.toShort())
            buffer.put(nome)
            buffer.putLong(lapidi[indice].quando)
        }
        return buffer.array()
    }

    private fun decodifica(bytes: ByteArray): List<Lapide> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.remaining() < 5) return emptyList()
        // A different version: start without tombstones instead of guessing a
        // format. The comparison is lost, not the contacts.
        if (buffer.get() != VERSION) return emptyList()
        val quante = buffer.int
        if (quante < 0) return emptyList()
        val fuori = ArrayList<Lapide>(quante)
        repeat(quante) {
            val impronta = leggiStringa(buffer) ?: return fuori
            val nome = leggiStringa(buffer) ?: return fuori
            if (buffer.remaining() < 8) return fuori
            fuori.add(Lapide(impronta, nome, buffer.long))
        }
        return fuori
    }

    private fun leggiStringa(buffer: ByteBuffer): String? {
        if (buffer.remaining() < 2) return null
        val quanti = buffer.short.toInt()
        if (quanti < 0 || buffer.remaining() < quanti) return null
        val bytes = ByteArray(quanti)
        buffer.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
