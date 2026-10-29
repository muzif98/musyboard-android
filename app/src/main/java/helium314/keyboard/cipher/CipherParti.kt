// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.cipher

import android.content.Context
import android.view.inputmethod.EditorInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A message too long for a chat, split into several separately encrypted
 * messages.
 *
 * ## The problem
 *
 * The cap exists for a serious reason: Telegram stops at 4096 characters, and a
 * blob the app splits by itself **no longer decrypts**. So above the threshold
 * encryption used to be refused, saying how many characters to remove. With the
 * compose row on, the plaintext lives only in the keyboard's buffer, behind a
 * notice that disappears in two seconds, and the next gesture takes it away: the
 * message was lost because the only option offered was rewriting it shorter.
 *
 * ## What happens instead
 *
 * It is cut into parts that fit under the cap and **each part is encrypted on
 * its own**. The result is N normal messages: no new format, nothing to change
 * in the core, and the recipient opens them one by one with the version they
 * already have.
 *
 * ## Why the "(1/3)" marker is in the plaintext
 *
 * Because it is the only place the reader sees it. In the envelope it would
 * change the format, and automatic reassembly is impossible anyway: parts arrive
 * as separate messages and open one at a time. Without a marker, three messages
 * continuing each other are indistinguishable from three unrelated ones, and the
 * reader cannot notice one is missing. The price is that the marker **is
 * visible**, and takes space under the cap (counted in the cut).
 *
 * ## Why the cut is not computed with a formula
 *
 * Blob length depends on the scheme, the number of group slots and z-base-32.
 * That estimate already exists in [CipherActions] and is the only one deciding
 * whether a message goes out: redoing it here would be two sources of truth that
 * diverge in the bad direction (a blob passing here and then split by the chat).
 * So [dividi] computes nothing: it receives the same question the caller asks
 * ("does this fit?") and uses it to find the longest cut that still fits.
 */
internal object CipherParti {

    /**
     * Beyond this, no splitting: refuse as before.
     *
     * Not a technical limit: the point where splitting stops being a favour.
     * Twelve messages in a row are a wall for the recipient, and whoever pasted a
     * whole document into a chat field probably wanted to send a file, which this
     * keyboard encrypts in one piece.
     */
    const val MAX_PARTI = 12

    /**
     * Cuts [testo] into marked parts that all fit under the cap.
     *
     * @param ciSta the same question the caller asks before encrypting, applied
     *   to the already marked text. Must be monotonic: if a text fits, every
     *   prefix fits.
     * @return the parts ready to encrypt, marker included, or `null` if even
     *   [MAX_PARTI] are not enough.
     */
    fun dividi(testo: String, ciSta: (String) -> Boolean): List<String>? {
        if (testo.isEmpty()) return null
        var quante = 2
        while (quante <= MAX_PARTI) {
            // Cut reserving the space of the WIDEST marker this number of parts
            // can produce ("(12/12) ", not "(1/12) "). Real markers are all
            // shorter or equal, so what fits in the cut fits after the label too.
            val pezzi = taglia(testo, marcatore(quante, quante), ciSta) ?: return null
            if (pezzi.size <= quante) {
                // A single piece is not split: no marker, which would say "(1/1)".
                // [CipherActions] never asks in this case, but a function answering
                // badly to a legitimate question waits for its first new caller.
                if (pezzi.size == 1) return listOf(testo)
                return pezzi.mapIndexed { i, pezzo -> marcatore(i + 1, pezzi.size) + pezzo }
            }
            // The cut produced more pieces than planned: the marker grows and the
            // count is redone. It only increases, so the loop ends.
            quante = pezzi.size
        }
        return null
    }

    private fun marcatore(quale: Int, quante: Int) = "($quale/$quante) "

    /**
     * Greedy cut: each piece is the longest that fits, backing off to the last
     * word break so as not to split a word.
     */
    private fun taglia(
        testo: String,
        etichetta: String,
        ciSta: (String) -> Boolean,
    ): List<String>? {
        val pezzi = mutableListOf<String>()
        var resto = testo
        while (resto.isNotEmpty()) {
            // More than MAX_PARTI pieces help nobody: the caller retries with a
            // higher number and stops at the cap.
            if (pezzi.size > MAX_PARTI) return pezzi
            val quanto = quantoCiSta(resto, etichetta, ciSta)
            // Not even one character: the cap is so tight the marker alone fills
            // it. Not a real case with today's values, but returning an empty list
            // would be.
            if (quanto <= 0) return null
            pezzi.add(resto.substring(0, quanto))
            resto = resto.substring(quanto)
        }
        return pezzi
    }

    /**
     * How many characters of [resto] fit in one piece, marker included.
     *
     * Binary search because [ciSta] goes through the blob estimate, and trying one
     * character at a time on an 8000-character message would be 8000 calls. The
     * monotonicity required of [ciSta] makes it valid.
     */
    private fun quantoCiSta(
        resto: String,
        etichetta: String,
        ciSta: (String) -> Boolean,
    ): Int {
        if (ciSta(etichetta + resto)) return resto.length
        var basso = 0
        var alto = resto.length
        while (basso < alto) {
            val mezzo = (basso + alto + 1) / 2
            if (ciSta(etichetta + resto.substring(0, mezzo))) basso = mezzo else alto = mezzo - 1
        }
        // Never in the middle of a surrogate pair: cutting there produces two half
        // characters (every emoji is like that).
        if (basso in 1 until resto.length && resto[basso - 1].isHighSurrogate()) basso--
        if (basso <= 0) return basso
        // Back off to the last break, but only if it is close: cutting mid-word
        // reads badly, and cutting far back to honour a distant break would
        // produce more parts than needed. A quarter of the piece is the
        // compromise.
        val minimo = basso - basso / 4
        val stacco = resto.lastIndexOf('\n', basso - 1).coerceAtLeast(
            resto.lastIndexOf(' ', basso - 1),
        )
        return if (stacco >= minimo && stacco > 0) stacco + 1 else basso
    }

    // --- The queue of already encrypted parts ---------------------------------

    /**
     * Which field parts are being delivered to: package, view id and type.
     *
     * The package is not enough: one messaging app holds every conversation, and
     * a part delivered after switching chat would go to another person (who could
     * not read it, but the writer would lose that piece unnoticed).
     *
     * The same triple [CipherCompose] uses for the forced row, with the same
     * declared limit: two truly identical fields on two screens are
     * indistinguishable. Here the cost of being wrong is lower than the cost of
     * not trying.
     */
    private var campoDellaCoda: Triple<String, Int, Int>? = null

    /** The blobs still to deliver, already encrypted, in order. */
    private val coda = ArrayDeque<String>()

    /** How many parts there were in total, to say so while delivering. */
    private var totale = 0

    /**
     * Whether the first part left from the compose row.
     *
     * Keeps behaviour from changing halfway through a message. In field mode the
     * fork never asks the app to send (auto-send applies only to the row), and
     * without this the parts from the second on would send themselves while the
     * first waited for a tap.
     */
    private var dallaRiga = false

    private fun identita(campo: EditorInfo?): Triple<String, Int, Int>? {
        val app = campo?.packageName.orEmpty()
        if (campo == null || app.isEmpty()) return null
        return Triple(app, campo.fieldId, campo.inputType)
    }

    /**
     * Whether a queue can be kept on this field.
     *
     * Asked **before** splitting. A field without a usable identity (no
     * `EditorInfo`, empty package) cannot hold a queue, and finding out later
     * would mean having told the user "part 1 of 3" and never delivering the
     * rest. Better not to split and refuse as before: the text stays where it is.
     */
    fun campoUtilizzabile(campo: EditorInfo?): Boolean = identita(campo) != null

    /**
     * Takes charge of the parts after the first.
     *
     * They arrive **already encrypted**: ciphertext, not plaintext, so keeping
     * them in memory adds nothing to what already left in the field. Encrypting
     * them one at a time would keep the plaintext here the whole time, and a
     * failure halfway would leave the message half delivered.
     */
    fun accoda(campo: EditorInfo?, blob: List<String>, quanteInTutto: Int, dallaRiga: Boolean) {
        scarta()
        val identita = identita(campo) ?: return
        if (blob.isEmpty()) return
        campoDellaCoda = identita
        totale = quanteInTutto
        this.dallaRiga = dallaRiga
        coda.addAll(blob)
    }

    /** Is a part ready for this field? */
    fun inAttesaSu(campo: EditorInfo?): Boolean {
        val identita = identita(campo) ?: return false
        return coda.isNotEmpty() && identita == campoDellaCoda
    }

    /** The first part left from the row, so auto-send applies. */
    fun daRiga(): Boolean = dallaRiga

    /**
     * How long the last delivered blob was, in characters.
     *
     * Tells **a field that emptied because the message was sent** from one
     * emptied any other way. They need opposite reactions: in the first case the
     * next part is due; in the second the user just deleted that piece, and
     * putting it back would not let them discard it.
     *
     * The sign is in the positions `onUpdateSelection` carries: after a send the
     * cursor was at the end of the blob ([ultimaLunghezza]) and the field goes
     * empty in one step; deleting by hand gets there from anywhere, selecting
     * all from zero.
     */
    fun ultimaLunghezza(): Int = ultimaLunghezza

    private var ultimaLunghezza = 0

    /** Records the part that just landed in the field. */
    fun consegnata(blob: String) {
        ultimaLunghezza = blob.length
    }

    /** The number of the next part and the total, for the notice. */
    fun prossimaEtichetta(): Pair<Int, Int> = Pair(totale - coda.size + 1, totale)

    /**
     * Looks at the next part **without removing it**.
     *
     * Looking and removing are separate: removing first and then delivering meant
     * a failed `commitText` (dead connection, truncating field) left the part
     * outside the queue without having arrived anywhere. Now it is removed only
     * after the field took it, with [consuma], and a failure leaves the queue
     * intact: retry with the lock key.
     */
    fun prossima(campo: EditorInfo?): String? =
        if (inAttesaSu(campo)) coda.first() else null

    /** Removes the part just delivered. Call **after** success. */
    fun consuma() {
        if (coda.isNotEmpty()) coda.removeFirst()
        if (coda.isEmpty()) scarta()
    }

    // --- The queue on disk -----------------------------------------------------

    private const val VERSIONE = 1
    private val AAD = "keyboard-cipher/v1/storage/parts".toByteArray()

    /**
     * Writes the queue, or deletes the file when nothing remains.
     *
     * ## Why the queue must survive the process
     *
     * Because the process dies: Android kills the IME whenever it likes. Once the
     * first part is delivered, the plaintext has already been cleared from the
     * row, and the other parts existed only in RAM. With the process dead, half
     * the message had arrived, the rest existed nowhere, and the writer did not
     * even know which half had left.
     *
     * ## What is written, and why that is acceptable
     *
     * **Already encrypted** blobs, the same thing about to appear in a chat
     * field. Plaintext never comes here. It still goes through Keystore, like
     * every other file of this system, because a clear file would tell whoever
     * looks at the disk that you were writing to someone and when.
     *
     * No expiry is needed: after a restart the chat field is empty, so the
     * "full -> empty" transition that advances the queue does not happen. An old
     * queue only moves if someone presses the lock key on an empty field, and
     * then the notice says "part 2 of 3".
     */
    fun salva(context: Context) {
        val campo = campoDellaCoda
        if (campo == null || coda.isEmpty()) {
            CipherStorage.delete(context, CipherStorage.PARTS)
            return
        }
        val chiaro = codifica(campo)
        val blob = try {
            CipherKeystore.wrap(AAD, chiaro) ?: return
        } finally {
            chiaro.fill(0)
        }
        CipherStorage.write(context, CipherStorage.PARTS, blob)
    }

    /**
     * Rereads the queue left by a previous process.
     *
     * Called once, when the core is ready (Keystore is not usable before). A file
     * that does not decode is not a fault to report (no queue is the usual
     * state), but it is deleted: a file nobody can read would stay there
     * revealing that something was written.
     */
    fun ripristina(context: Context) {
        val blob = CipherStorage.read(context, CipherStorage.PARTS) ?: return
        val chiaro = CipherKeystore.unwrap(AAD, blob) ?: run {
            CipherStorage.delete(context, CipherStorage.PARTS)
            return
        }
        val riuscito = try {
            runCatching { decodifica(chiaro) }.getOrDefault(false)
        } finally {
            chiaro.fill(0)
        }
        if (!riuscito) {
            scarta()
            CipherStorage.delete(context, CipherStorage.PARTS)
        }
    }

    /**
     * `internal` rather than private because the encoder and its inverse are
     * where a queue gets silently corrupted, and the one part of persistence that
     * can be tested without a real Keystore.
     */
    internal fun codifica(campo: Triple<String, Int, Int>): ByteArray {
        val app = campo.first.toByteArray(Charsets.UTF_8)
        val blob = coda.map { it.toByteArray(Charsets.UTF_8) }
        var size = 1 + 1 + 4 + 4 + 2 + app.size + 4 + 4 + 4
        for (b in blob) size += 4 + b.size
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(VERSIONE.toByte())
        buffer.put(if (dallaRiga) 1 else 0)
        buffer.putInt(totale)
        buffer.putInt(ultimaLunghezza)
        buffer.putShort(app.size.toShort())
        buffer.put(app)
        buffer.putInt(campo.second)
        buffer.putInt(campo.third)
        buffer.putInt(blob.size)
        for (b in blob) {
            buffer.putInt(b.size)
            buffer.put(b)
        }
        return buffer.array()
    }

    /** `false` if the file cannot be read: the caller deletes it. */
    internal fun decodifica(bytes: ByteArray): Boolean {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.get() != VERSIONE.toByte()) return false
        val riga = buffer.get() == 1.toByte()
        val quante = buffer.int
        val lunghezza = buffer.int
        val nome = ByteArray(buffer.short.toInt().coerceAtLeast(0))
        buffer.get(nome)
        val app = String(nome, Charsets.UTF_8)
        val id = buffer.int
        val tipo = buffer.int
        val quanti = buffer.int
        // An absurd count is a corrupted file, not a huge queue: the cap is
        // [MAX_PARTI].
        if (app.isEmpty() || quanti <= 0 || quanti > MAX_PARTI) return false
        val letti = ArrayList<String>(quanti)
        repeat(quanti) {
            val len = buffer.int
            if (len <= 0 || len > buffer.remaining()) return false
            val b = ByteArray(len)
            buffer.get(b)
            letti.add(String(b, Charsets.UTF_8))
        }
        scarta()
        campoDellaCoda = Triple(app, id, tipo)
        totale = quante
        dallaRiga = riga
        ultimaLunghezza = lunghezza
        coda.addAll(letti)
        return true
    }

    /** Discards the queue. Called when starting a new message. */
    fun scarta() {
        coda.clear()
        campoDellaCoda = null
        totale = 0
        dallaRiga = false
        ultimaLunghezza = 0
    }
}
