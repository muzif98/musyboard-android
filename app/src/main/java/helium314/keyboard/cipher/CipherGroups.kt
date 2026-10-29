package helium314.keyboard.cipher

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A saved group: a name chosen by the user and the keys of its members.
 */
internal class CipherGroup(val nome: String, val membri: List<ByteArray>)

/**
 * Saved groups (decision K5).
 *
 * ## What a group is here
 *
 * **A local label over a set of keys**, exactly as a contact name is a local
 * label over a key. It does not travel, is not in the ciphertext, and nobody
 * else knows it, not even the members.
 *
 * ## Why it is encrypted at rest like the rest
 *
 * It holds contact keys grouped by affinity: not only "whom you talk to" but
 * "who is with whom", metadata richer than the contact list. Same protection,
 * with its own AAD domain so the groups file cannot pass for another file.
 *
 * ## What it does NOT do
 *
 * It does not touch the keyring. A member forgotten meanwhile stays written
 * here; encryption notices, because the core rejects unpinned keys. Cleaning
 * groups on every contact change would silently delete a user choice.
 */
internal object CipherGroups {

    private const val VERSION: Byte = 1
    private const val KEY_LEN = 32
    /**
     * The cap of decision K3: eight recipients, plus the sender's slot the core
     * adds by itself. Public because **the UI must enforce it before getting
     * here**: stopping selection at eight is a limit; accepting ten and then
     * refusing to save throws away the user's work.
     */
    const val MAX_MEMBRI = 8
    private val AAD = "keyboard-cipher/v1/storage/groups".toByteArray()

    fun tutti(context: Context): List<CipherGroup> = load(context).gruppi

    /**
     * The group chosen as recipient for that app, if any.
     *
     * The choice lives here and not in the core, which has no "current group":
     * between two people the recipient is set by reading, for a group it is not.
     * Encrypted like the rest.
     */
    /**
     * Which saved group matches the message just read, if exactly one.
     *
     * A group blob **does not contain the member list**, by design: decision K
     * forbids per-slot identifiers because they would make membership verifiable
     * from outside. The blob says only who built the slots and how many people
     * could read it, so the group is recognised **only by comparing with the
     * groups saved here**.
     *
     * Two conditions together:
     *
     * - the sender is one of the members;
     * - the count matches. `membri` are the **others**, without us, while the
     *   blob's count includes everyone, hence the `+ 1`.
     *
     * If two match, none is chosen: guessing silently about who reads the message
     * is the costliest mistake this project can make. The caller tells the user
     * and leaves the recipient where it was.
     */
    fun riconosci(context: Context, mittente: ByteArray, quanti: Int): CipherGroup? =
        tutti(context)
            .filter { gruppo ->
                gruppo.membri.size + 1 == quanti &&
                    gruppo.membri.any { it.contentEquals(mittente) }
            }
            .singleOrNull()

    fun corrente(context: Context, appPackage: String): CipherGroup? {
        val stato = load(context)
        val nome = stato.scelte[appPackage] ?: return null
        // `ignoreCase` as in [salva] and [dimentica]: three comparisons of the same
        // name must follow the same rule.
        return stato.gruppi.firstOrNull { it.nome.equals(nome, ignoreCase = true) }
    }

    /** Chooses a group for that app. `null` goes back to a single recipient. */
    fun scegli(context: Context, appPackage: String, nome: String?): Boolean {
        if (appPackage.isEmpty()) return false
        val stato = load(context)
        val scelte = stato.scelte.toMutableMap()
        // The name is normalised as in [salva] (`trim()`). Otherwise a name with a
        // trailing space saved the group and **never selected it**.
        //
        // A name matching no group is rejected: a choice pointing at nothing would
        // show a recipient that does not exist.
        val pulito = nome?.trim()
        if (pulito != null && stato.gruppi.none { it.nome.equals(pulito, ignoreCase = true) }) {
            return false
        }
        if (pulito == null) scelte.remove(appPackage) else scelte[appPackage] = pulito
        return save(context, Stato(stato.gruppi, scelte))
    }

    /**
     * Saves a group under that name, replacing any existing one.
     *
     * The name is the key: two groups with the same name would be
     * indistinguishable in the list.
     */
    fun salva(context: Context, nome: String, membri: List<ByteArray>): Boolean {
        val pulito = nome.trim()
        if (pulito.isEmpty() || membri.isEmpty() || membri.size > MAX_MEMBRI) return false
        if (membri.any { it.size != KEY_LEN }) return false
        val stato = load(context)
        val restanti = stato.gruppi.filterNot { it.nome.equals(pulito, ignoreCase = true) }
        return save(context, Stato(restanti + CipherGroup(pulito, membri), stato.scelte))
    }

    /**
     * Forgets a group, and every choice naming it: a choice pointing at a missing
     * group would show "write to <group>" in a chat where it no longer exists.
     */
    fun dimentica(context: Context, nome: String): Boolean {
        val stato = load(context)
        return save(
            context,
            Stato(
                stato.gruppi.filterNot { it.nome.equals(nome, ignoreCase = true) },
                stato.scelte.filterNot { it.value.equals(nome, ignoreCase = true) },
            ),
        )
    }

    fun cancellaTutto(context: Context) {
        CipherStorage.delete(context, CipherStorage.GROUPS)
    }

    private class Stato(val gruppi: List<CipherGroup>, val scelte: Map<String, String>)

    private fun load(context: Context): Stato {
        val vuoto = Stato(emptyList(), emptyMap())
        val blob = CipherStorage.read(context, CipherStorage.GROUPS) ?: return vuoto
        val plain = CipherKeystore.unwrap(AAD, blob) ?: return vuoto
        return try {
            runCatching { decode(plain) }.getOrDefault(vuoto)
        } finally {
            plain.fill(0)
        }
    }

    private fun save(context: Context, stato: Stato): Boolean {
        if (stato.gruppi.isEmpty()) {
            cancellaTutto(context)
            return true
        }
        val plain = encode(stato)
        val blob = try {
            CipherKeystore.wrap(AAD, plain) ?: return false
        } finally {
            plain.fill(0)
        }
        return CipherStorage.write(context, CipherStorage.GROUPS, blob)
    }

    /**
     *     version(1) | count(4) |
     *     per group:  nameLen(2) | name | members(1) | key(32) * members
     */
    private fun encode(stato: Stato): ByteArray {
        val nomi = stato.gruppi.associateWith { it.nome.toByteArray(Charsets.UTF_8) }
        val scelte = stato.scelte.entries.associateWith {
            it.key.toByteArray(Charsets.UTF_8) to it.value.toByteArray(Charsets.UTF_8)
        }
        var size = 5
        for (g in stato.gruppi) size += 2 + (nomi[g]?.size ?: 0) + 1 + g.membri.size * KEY_LEN
        size += 4
        for ((app, nome) in scelte.values) size += 2 + app.size + 2 + nome.size
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(VERSION)
        buffer.putInt(stato.gruppi.size)
        for (g in stato.gruppi) {
            val nome = nomi[g] ?: ByteArray(0)
            buffer.putShort(nome.size.toShort())
            buffer.put(nome)
            buffer.put(g.membri.size.toByte())
            for (m in g.membri) buffer.put(m)
        }
        buffer.putInt(scelte.size)
        for ((app, nome) in scelte.values) {
            buffer.putShort(app.size.toShort())
            buffer.put(app)
            buffer.putShort(nome.size.toShort())
            buffer.put(nome)
        }
        return buffer.array()
    }

    /**
     * Every read is preceded by a check that the bytes are there: the blob comes
     * from a file, and a truncated file must not become an `IndexOutOfBounds`
     * inside the UI.
     */
    private fun decode(blob: ByteArray): Stato {
        val vuoto = Stato(emptyList(), emptyMap())
        if (blob.size < 5 || blob[0] != VERSION) return vuoto
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(1)
        val quanti = buffer.int
        if (quanti < 0 || quanti > 1024) return vuoto
        val gruppi = ArrayList<CipherGroup>(quanti)
        repeat(quanti) {
            if (buffer.remaining() < 3) return Stato(gruppi, emptyMap())
            val nomeLen = buffer.short.toInt() and 0xFFFF
            if (buffer.remaining() < nomeLen + 1) return Stato(gruppi, emptyMap())
            val nome = ByteArray(nomeLen).also { buffer.get(it) }
            val quantiMembri = buffer.get().toInt() and 0xFF
            if (quantiMembri == 0 || quantiMembri > MAX_MEMBRI) return Stato(gruppi, emptyMap())
            if (buffer.remaining() < quantiMembri * KEY_LEN) return Stato(gruppi, emptyMap())
            val membri = ArrayList<ByteArray>(quantiMembri)
            repeat(quantiMembri) {
                membri.add(ByteArray(KEY_LEN).also { buffer.get(it) })
            }
            gruppi.add(CipherGroup(String(nome, Charsets.UTF_8), membri))
        }

        // Choices are at the end: a file written before they existed still reads,
        // with no choices instead of an error.
        if (buffer.remaining() < 4) return Stato(gruppi, emptyMap())
        val quanteScelte = buffer.int
        if (quanteScelte < 0 || quanteScelte > 1024) return Stato(gruppi, emptyMap())
        val scelte = HashMap<String, String>(quanteScelte)
        repeat(quanteScelte) {
            if (buffer.remaining() < 2) return Stato(gruppi, scelte)
            val appLen = buffer.short.toInt() and 0xFFFF
            if (buffer.remaining() < appLen + 2) return Stato(gruppi, scelte)
            val app = ByteArray(appLen).also { buffer.get(it) }
            val nomeLen = buffer.short.toInt() and 0xFFFF
            if (buffer.remaining() < nomeLen) return Stato(gruppi, scelte)
            val nome = ByteArray(nomeLen).also { buffer.get(it) }
            scelte[String(app, Charsets.UTF_8)] = String(nome, Charsets.UTF_8)
        }
        return Stato(gruppi, scelte)
    }
}
