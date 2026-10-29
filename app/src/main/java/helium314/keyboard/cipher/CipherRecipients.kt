package helium314.keyboard.cipher

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The recipient for each app, **on disk**.
 *
 * ## The problem this solves
 *
 * The core keeps the recipient choice in memory, rightly: it is a crate without
 * I/O. But an IME's memory belongs to a service Android restarts whenever it
 * likes (low RAM, keyboard switch, reboot), and every restart cleared the
 * choice: hours later the lock key asked to choose a recipient already chosen.
 *
 * ## Why encrypted
 *
 * No secrets (public keys and package names), but it is exactly the metadata
 * this project exists not to give away: whom you talk to, and in which app. So
 * it goes through the same Keystore as the keyring, with its own domain so one
 * blob cannot be accepted in place of the other.
 *
 * ## Format
 *
 *     version(1) | count(4, LE) | record*
 *     record: package-length(2, LE) | package(UTF-8) | pubkey(32)
 *
 * Records are not fixed-length: the package name is variable.
 */
internal object CipherRecipients {

    private const val VERSION = 1
    private const val KEY_LEN = 32
    private val AAD = "keyboard-cipher/v1/storage/recipients".toByteArray()

    /**
     * Remembers that in [appPackage] one writes to [peer], and writes it to disk.
     *
     * Rereads the file instead of caching it: writes are rare (choosing a
     * recipient, decrypting a message), and a cache would be a second state to
     * keep in sync with the core's.
     */
    fun remember(context: Context, appPackage: String, peer: ByteArray): Boolean {
        if (appPackage.isEmpty() || peer.size != KEY_LEN) return false
        // ## The group is cleared HERE, not in the callers
        //
        // "This app's recipient is this person" and "is this group" are the same
        // choice, and when encrypting the group wins over the individual. So
        // pinning a person without clearing the group pins nothing: the message
        // still goes to everyone.
        //
        // Several paths set a recipient (manual choice, the button on the message
        // screen, and the two automatic paths triggered by decrypting), and all
        // pass through here: the one place where the rule is written once.
        if (!CipherGroups.scegli(context, appPackage, null)) return false
        val current = load(context).toMutableMap()
        // ## Setting a recipient cancels the pending assignment
        //
        // Choosing the recipient goes through one of OUR screens, so focus
        // returns to the app right after: if the last-read contact were still
        // pending, that return would reapply it **on top of** the choice just
        // made, and the message would go to someone else with nothing saying so.
        val cera = current.remove(ULTIMO_LETTO) != null
        if (!cera && current[appPackage]?.contentEquals(peer) == true) return true
        current[appPackage] = peer
        save(context, current)
        // ## The core too, not only the disk
        //
        // The recipient lives in two places: on disk (this map) and inside the
        // core, which the UI queries to show the name next to the row
        // (`nativeCurrentPeerName`). Updating only the first left the label
        // showing the previous name after reading a message. This is the one
        // place where "this app's recipient is this person" is written, and both
        // halves of that fact must move together.
        CipherCore.nativeSetCurrentPeer(appPackage, peer)
        return true
    }

    /**
     * Reserved key for "the last contact whose message I read".
     *
     * A leading space: an Android package name cannot contain one, so it collides
     * with no real app and no second file is needed.
     */
    private const val ULTIMO_LETTO = " ultimo"

    /**
     * Records who wrote the message just opened, **even when the originating app
     * is unknown**.
     *
     * Recipient memory is per app, and the most used way to decrypt (the share
     * sheet) does not say where the text came from: the system does not
     * attribute it, and `getReferrer()` can be spoofed, so it is not used.
     *
     * This value never replaces a choice already made for an app: see
     * [ereditaUltimoLetto].
     */
    fun ricordaUltimoLetto(context: Context, peer: ByteArray): Boolean {
        if (peer.size != KEY_LEN) return false
        val current = load(context).toMutableMap()
        if (current[ULTIMO_LETTO]?.contentEquals(peer) == true) return true
        current[ULTIMO_LETTO] = peer
        save(context, current)
        return true
    }

    /**
     * Gives [appPackage] the last contact read, but **only if that app has none
     * yet**.
     *
     * Precedence is what matters: an explicit choice for an app, or one made by
     * reading inside that app, always wins. This is the last step.
     */
    fun ereditaUltimoLetto(context: Context, appPackage: String): Boolean {
        if (appPackage.isEmpty() || appPackage == ULTIMO_LETTO) return false
        val map = load(context)
        val ultimo = map[ULTIMO_LETTO] ?: return false
        // **Overwrites** the app's recipient, then is consumed. Two halves of the
        // same rule.
        //
        // Overwrites because within an app the last sender read wins.
        //
        // Consumed because otherwise the same read would follow the user into
        // **every** app opened afterwards, and no manual choice would survive. It
        // applies to the first chat opened after reading, the one where the reply
        // is about to be written; from then on per-app memory holds it.
        //
        // `remember` does the consuming, by removing the pending assignment.
        return remember(context, appPackage, ultimo)
    }

    /**
     * Puts the saved choices back into the core. Must be called **after**
     * `nativeInit`, since before that there is no session to tell.
     *
     * A peer no longer in the keyring is rejected by the core, and that is fine:
     * the user cleaned up contacts, and restoring a recipient that no longer
     * exists would be worse than asking again.
     */
    fun restore(context: Context) {
        for ((appPackage, peer) in load(context)) {
            // The reserved key is not an app: giving it to the core as one would
            // create a recipient for a package that does not exist.
            if (appPackage == ULTIMO_LETTO) continue
            CipherCore.nativeSetCurrentPeer(appPackage, peer)
        }
    }

    fun forget(context: Context) {
        CipherStorage.delete(context, CipherStorage.RECIPIENTS)
    }

    /**
     * Plaintext does not stay on the heap longer than needed: it holds the peers'
     * keys, i.e. the list of whom you talk to. Same rule as the identity secret
     * in [CipherIdentity].
     */
    private fun load(context: Context): Map<String, ByteArray> {
        val blob = CipherStorage.read(context, CipherStorage.RECIPIENTS) ?: return emptyMap()
        val plain = CipherKeystore.unwrap(AAD, blob) ?: return emptyMap()
        return try {
            runCatching { decode(plain) }.getOrDefault(emptyMap())
        } finally {
            plain.fill(0)
        }
    }

    private fun save(context: Context, map: Map<String, ByteArray>) {
        val plain = encode(map)
        val blob = try {
            CipherKeystore.wrap(AAD, plain) ?: return
        } finally {
            plain.fill(0)
        }
        CipherStorage.write(context, CipherStorage.RECIPIENTS, blob)
    }

    private fun encode(map: Map<String, ByteArray>): ByteArray {
        var size = 5
        val names = map.keys.associateWith { it.toByteArray(Charsets.UTF_8) }
        for (name in names.values) size += 2 + name.size + KEY_LEN
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(VERSION.toByte())
        buffer.putInt(map.size)
        for ((app, peer) in map) {
            val name = names[app] ?: continue
            buffer.putShort(name.size.toShort())
            buffer.put(name)
            buffer.put(peer)
        }
        return buffer.array()
    }

    /**
     * A file that does not decode becomes an empty map, not an exception: at
     * worst the user picks the recipient again, much better than a keyboard that
     * does not start.
     */
    private fun decode(bytes: ByteArray): Map<String, ByteArray> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.remaining() < 5) return emptyMap()
        if (buffer.get().toInt() != VERSION) return emptyMap()
        val count = buffer.int
        if (count < 0) return emptyMap()
        val result = HashMap<String, ByteArray>(count)
        repeat(count) {
            if (buffer.remaining() < 2) return result
            val length = buffer.short.toInt()
            if (length < 0 || buffer.remaining() < length + KEY_LEN) return result
            val name = ByteArray(length)
            buffer.get(name)
            val peer = ByteArray(KEY_LEN)
            buffer.get(peer)
            result[String(name, Charsets.UTF_8)] = peer
        }
        return result
    }
}
