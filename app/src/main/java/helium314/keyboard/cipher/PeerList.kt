package helium314.keyboard.cipher

/**
 * A peer as the UI sees it.
 *
 * `label` is `null` until the user names it: on first appearance only the key is
 * pinned. The contact identity is a local label assigned by the user; without it
 * two different keys would just be two different peers and "Mark's key changed"
 * could not be expressed, because the keyring is indexed by public key.
 */
internal data class Peer(
    val key: ByteArray,
    val firstSeenUnix: Long,
    val verified: Boolean,
    val label: String?,
) {
    // data class + ByteArray: the generated equals/hashCode would compare the
    // array reference, not its content. Two Peers with the same key must be
    // equal.
    override fun equals(other: Any?): Boolean =
        this === other || (other is Peer && key.contentEquals(other.key))

    override fun hashCode(): Int = key.contentHashCode()
}

/**
 * Decodes the `nativeListPeers` blob.
 *
 *     header: version(1) | count(4, LE)
 *     record: pubkey(32) | firstSeenUnix(8, LE) | verified(1) |
 *             labelLen(2, LE) | label(labelLen, UTF-8)
 *
 * Records are **not** fixed-length: the label is variable. Walking them with a
 * constant stride is the natural mistake here, and produces invented peers
 * instead of an error.
 *
 * Every read is preceded by a check that the bytes are there: a truncated file
 * must not become an `IndexOutOfBounds` inside the UI.
 *
 * ## This is a SECOND reader of the storage format
 *
 * `nativeListPeers` returns the same layout the core exports for persistence
 * (truncated before private material). A change to that format arrives here
 * **without anything signalling it**, and shows up as "Encryption unavailable"
 * on the contacts screen: a failure that looks like crypto and is the parser's.
 *
 * Hence two rules:
 *
 * - **every** known version is accepted, not just one;
 * - read up to the peers and **ignore the tail**: requiring the blob to end
 *   after the last peer would turn every future addition into this same
 *   failure.
 */
internal object PeerList {

    // Versions 3 and 4 append fields to chain records. Nothing changes on this
    // side (read up to the peers, ignore the tail), but each version number must
    // be added, otherwise the whole blob is rejected and the contacts screen says
    // "Encryption unavailable".
    private val VERSIONI_NOTE = byteArrayOf(1, 2, 3, 4)
    private const val KEY_LEN = 32

    fun parse(blob: ByteArray): List<Peer>? {
        if (blob.size < 5) return null
        if (!VERSIONI_NOTE.contains(blob[0])) return null

        var offset = 1
        val count = readInt(blob, offset) ?: return null
        offset += 4

        val peers = ArrayList<Peer>(count.coerceAtMost(1024))
        repeat(count) {
            if (offset + KEY_LEN + 8 + 1 + 2 > blob.size) return null
            val key = blob.copyOfRange(offset, offset + KEY_LEN)
            offset += KEY_LEN
            val firstSeen = readLong(blob, offset) ?: return null
            offset += 8
            val verified = blob[offset] != 0.toByte()
            offset += 1
            val labelLen = readShort(blob, offset) ?: return null
            offset += 2
            if (offset + labelLen > blob.size) return null
            val label = if (labelLen == 0) null else String(
                blob, offset, labelLen, Charsets.UTF_8,
            )
            offset += labelLen
            peers.add(Peer(key, firstSeen, verified, label))
        }
        return peers
    }

    private fun readShort(b: ByteArray, at: Int): Int? {
        if (at + 2 > b.size) return null
        return (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    }

    private fun readInt(b: ByteArray, at: Int): Int? {
        if (at + 4 > b.size) return null
        var value = 0
        for (i in 3 downTo 0) {
            value = (value shl 8) or (b[at + i].toInt() and 0xFF)
        }
        // A negative count after the cast to Int means an absurd blob (over two
        // billion peers): better reject than allocate.
        return if (value < 0) null else value
    }

    private fun readLong(b: ByteArray, at: Int): Long? {
        if (at + 8 > b.size) return null
        var value = 0L
        for (i in 7 downTo 0) {
            value = (value shl 8) or (b[at + i].toLong() and 0xFF)
        }
        return value
    }
}
