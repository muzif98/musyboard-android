package helium314.keyboard.cipher

import android.inputmethodservice.InputMethodService
import android.view.WindowManager

/**
 * Decides whether the keyboard window is protected from screenshots.
 *
 * ## Why not each part on its own
 *
 * `FLAG_SECURE` is on the WINDOW, and the IME has a single window shared by the
 * compose row and the reading panel. With two independent owners calling
 * `setFlags` and `clearFlags`, closing the panel while the row is on screen
 * would unprotect the row, silently.
 *
 * So nobody sets or clears the flag directly: changes are declared and the flag
 * is **recomputed** here from scratch. Recomputing rather than counting owners is
 * deliberate: a counter drifts if someone forgets to release, and it drifts in
 * the worst direction (protection stuck on, a keyboard blocking screenshots in
 * every app with nothing explaining why).
 *
 * ## The real price
 *
 * While the flag is on, the system refuses screenshots: someone encrypting in a
 * chat cannot capture that screen while the keyboard is open. That is the cost
 * of keeping plaintext off captures, the same the reading panel already pays.
 *
 * The flag follows the row, not its content: it turns on when the row appears,
 * not at the first character, otherwise the first character would already have
 * been on screen unprotected.
 */
internal object CipherSchermoProtetto {

    /**
     * Recomputes from the current state. Called by anything that changes what
     * may show plaintext: the row appearing or disappearing, the panel opening or
     * closing.
     *
     * Idempotent: calling it more than needed costs nothing.
     */
    fun aggiorna(ime: InputMethodService?) {
        val finestra = ime?.window?.window ?: return
        // The switch is in `CipherSettings`, one for the whole app: turning it off
        // makes plaintext capturable everywhere, here and on the decryption
        // screen.
        val serve = CipherSettings.isBlockScreenshot(finestra.context) &&
            (CipherPanel.isAperto() || CipherCompose.rigaASchermo())
        runCatching {
            if (serve) {
                finestra.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE,
                )
            } else {
                finestra.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}
