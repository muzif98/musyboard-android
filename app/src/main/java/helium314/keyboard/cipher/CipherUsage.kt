package helium314.keyboard.cipher

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * When each contact was last chosen as recipient.
 *
 * Used for one thing: putting recently used people at the top of the "who are
 * you writing to?" list, instead of the order the core returns peers in.
 *
 * ## What it does NOT claim
 *
 * The order does not say "it's them": it says "you used these recently". That is
 * why there is no preselection: within one chat app you write to several people,
 * and any UI suggesting a default recipient invites encrypting for the wrong
 * person. Sorting saves scrolling, not the choice.
 *
 * ## Why a file of its own
 *
 * Not `SharedPreferences`: those are clear XML in `filesDir`, which is included
 * in backups. This holds *whom you talk to and when*. Same approach as
 * [CipherRecipients]: wrapped by [CipherKeystore], **its own AAD domain**, and
 * `noBackupFilesDir`. Unreadable file: empty map, only the order is lost.
 */
internal object CipherUsage {

    private val AAD = "keyboard-cipher/v1/storage/usage".toByteArray()

    private const val VERSION: Byte = 1
    private const val KEY_LEN = 32

    /**
     * Records that we are encrypting for this contact, now.
     *
     * On CHOICE, not on send: choosing is the gesture that says "this is who I am
     * talking to", and the message might never be sent.
     */
    fun nota(context: Context, peer: ByteArray) {
        if (peer.size != KEY_LEN) return
        val mappa = carica(context).toMutableMap()
        mappa[peer.toList()] = System.currentTimeMillis() / 1000
        salva(context, mappa)
    }

    /**
     * Most recent first, then those never used.
     *
     * A single read, the map held by the caller: sorting calls the comparator
     * O(n log n) times, and each call would otherwise be a decryption.
     */
    fun ordinati(context: Context, peers: List<Peer>): List<Peer> {
        val quando = carica(context)
        return peers.sortedByDescending { quando[it.key.toList()] ?: 0L }
    }

    private fun carica(context: Context): Map<List<Byte>, Long> {
        val blob = CipherStorage.read(context, CipherStorage.USAGE) ?: return emptyMap()
        val plain = CipherKeystore.unwrap(AAD, blob) ?: return emptyMap()
        return try {
            runCatching { decodifica(plain) }.getOrDefault(emptyMap())
        } finally {
            plain.fill(0)
        }
    }

    private fun salva(context: Context, mappa: Map<List<Byte>, Long>) {
        val plain = codifica(mappa)
        val blob = try {
            CipherKeystore.wrap(AAD, plain) ?: return
        } finally {
            plain.fill(0)
        }
        CipherStorage.write(context, CipherStorage.USAGE, blob)
    }

    private fun codifica(mappa: Map<List<Byte>, Long>): ByteArray {
        val buffer = ByteBuffer
            .allocate(1 + 4 + mappa.size * (KEY_LEN + 8))
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(VERSION)
        buffer.putInt(mappa.size)
        for ((chiave, quando) in mappa) {
            buffer.put(chiave.toByteArray())
            buffer.putLong(quando)
        }
        return buffer.array()
    }

    private fun decodifica(bytes: ByteArray): Map<List<Byte>, Long> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.remaining() < 5) return emptyMap()
        // A different version: start without ordering instead of guessing a
        // format. Convenience is lost, not contacts.
        if (buffer.get() != VERSION) return emptyMap()
        val quanti = buffer.int
        if (quanti < 0) return emptyMap()
        val mappa = HashMap<List<Byte>, Long>(quanti)
        repeat(quanti) {
            if (buffer.remaining() < KEY_LEN + 8) return mappa
            val chiave = ByteArray(KEY_LEN)
            buffer.get(chiave)
            mappa[chiave.toList()] = buffer.long
        }
        return mappa
    }
}
