package helium314.keyboard.cipher

import java.security.SecureRandom

/**
 * Handoff between the keyboard and [DecryptActivity], to say which app the user
 * was typing in.
 *
 * ## Why an intent extra is not enough
 *
 * `DecryptActivity` is exported (it must be, to receive text from other apps),
 * so any application can send it an intent with whatever extras it likes.
 *
 * `getReferrer()` is not a guard either: `Activity.getReferrer()` returns
 * `Intent.EXTRA_REFERRER` when present, an extra written by the caller, and
 * Android's documentation says explicitly it is not a security feature. With it,
 * an app without any permission could declare itself a chat app and move that
 * chat's current recipient to its own key.
 *
 * ## What this does instead
 *
 * A random value, generated in memory by the keyboard and consumed once.
 * Keyboard and activity live in the **same process**, so the value crosses no
 * observable boundary; an external app can write any extra but cannot guess 128
 * bits.
 *
 * If the process dies between issuing and consuming, the token is lost and
 * attribution fails: [DecryptActivity] falls back to "no app", which disables
 * implicit recipient selection. The right way to fail.
 */
internal object CipherHandoff {

    private const val EXTRA = "helium314.keyboard.cipher.HANDOFF"

    private val random = SecureRandom()

    /**
     * One token at a time: the flow is always "press decrypt, the screen opens".
     * A collection would only make tokens live longer than needed.
     */
    @Volatile
    private var pendente: Pair<String, String>? = null

    /** Name of the extra to put in the intent. */
    fun extraName(): String = EXTRA

    /**
     * Records the editor's package and returns the token to attach to the
     * intent.
     */
    fun issue(editorPackage: String): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        pendente = token to editorPackage
        return token
    }

    /**
     * Retrieves the package bound to the token, if it matches. The token is
     * consumed regardless: a value that stays valid can be retried.
     *
     * Constant-time comparison: a secret compared with an externally supplied
     * value is exactly where an early-exit comparison leaks information.
     */
    fun consume(token: String?): String? {
        val atteso = pendente
        pendente = null
        if (token == null || atteso == null) return null
        return if (equalsCostante(token, atteso.first)) atteso.second else null
    }

    private fun equalsCostante(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }
}
