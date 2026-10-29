package helium314.keyboard.cipher

import android.inputmethodservice.InputMethodService
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import helium314.keyboard.latin.R
import android.graphics.drawable.GradientDrawable
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

/**
 * The decrypted message **inside the keyboard window**.
 *
 * ## Why the activity is not enough
 *
 * `DecryptActivity` remains the path for everything coming from outside
 * (selection toolbar, share sheet, attachments), but it cannot always be opened.
 * On some ROMs the system refuses to start an activity from an app without
 * visible windows, **silently**: `startActivity` returns as if it worked.
 *
 * The keyboard window is ours already and not subject to that restriction. It
 * does need an active input session: without a focused text field an IME has no
 * window to show, which is the limit of this path.
 *
 * ## Why it is also more correct
 *
 * The plaintext does not leave the keyboard even for a separate window. No task,
 * no Recents screen, no intent, no caller to authenticate: machinery that exists
 * in [DecryptActivity] *because* it is an exported activity.
 *
 * ## FLAG_SECURE
 *
 * The IME window does not have it, which is fine while it shows keys, not while
 * it shows a decrypted message. But the flag is NOT set or cleared here: the
 * window is shared with the compose row, which is protected too. The change is
 * declared and [CipherSchermoProtetto] recomputes it, since clearing it matters
 * as much as setting it (leaving it on would block screenshots in every app,
 * forever, with nothing explaining why).
 *
 * ## What it does NOT do
 *
 * Only successful text messages. Every other outcome stays with
 * [DecryptActivity], which handles all six in one place. By then the activity
 * opens anyway, because the keyboard window is visible.
 */
object CipherPanel {

    private var pannello: View? = null
    private var chi: TextView? = null
    private var quando: TextView? = null
    private var testo: TextView? = null
    private var servizio: InputMethodService? = null

    /**
     * Attaches the views. Called on every `setInputView`, like
     * [CipherCompose.bind]: the hierarchy is rebuilt on every theme change or
     * rotation, and a reference kept longer would point to dead views.
     */
    fun bind(ime: InputMethodService, view: View) {
        servizio = ime
        val trovato = view.findViewById<View>(R.id.cipher_message_panel)
        pannello = trovato ?: return
        chi = trovato.findViewById(R.id.cipher_panel_who)
        quando = trovato.findViewById(R.id.cipher_panel_when)
        testo = trovato.findViewById(R.id.cipher_panel_text)

        val colori = Settings.getValues().mColors
        colori.setBackground(trovato, ColorType.STRIP_BACKGROUND)
        chi?.setTextColor(colori.get(ColorType.KEY_TEXT))
        quando?.setTextColor(colori.get(ColorType.KEY_HINT_TEXT))
        testo?.setTextColor(colori.get(ColorType.KEY_TEXT))
        // The bubble takes the colour of the KEYS, not of the strip: the same
        // relation as between a key and the keyboard background, so contrast is
        // already guaranteed by the theme author. The drawable is tinted rather
        // than the background painted, to keep the rounded corners.
        //
        // `mutate()` before tinting: otherwise the tint would go into the
        // drawable's shared state and stain every other view using it.
        val bolla = trovato.findViewById<View>(R.id.cipher_panel_bubble)
            ?.background?.mutate() as? GradientDrawable
        bolla?.setColor(colori.get(ColorType.KEY_BACKGROUND))
        // A thin border, which makes the bubble reliable: in many themes the key
        // background matches the strip, and a bubble filled with the colour it
        // sits on would be invisible. The border is the text colour, very
        // transparent, so it reads on light and dark.
        bolla?.setStroke(1.dp(trovato), colori.get(ColorType.KEY_TEXT) and 0x22FFFFFF)
        trovato.findViewById<TextView>(R.id.cipher_panel_close)?.apply {
            setTextColor(colori.get(ColorType.KEY_TEXT))
            setOnClickListener { chiudi() }
        }
        // Closed on attach: a new hierarchy must not inherit a message left open
        // in the old one.
        chiudi()
    }

    /** Is there a keyboard window to show something in? */
    fun disponibile(): Boolean = pannello != null && servizio != null

    fun mostra(intestazione: String, data: String, messaggio: String) {
        val vista = pannello ?: return
        val ime = servizio ?: return
        // Before anything can reach the screen. The flag is shared with the
        // compose row and decided by one arbiter, [CipherSchermoProtetto]. Marked
        // open BEFORE requesting the recomputation, so the arbiter knows it is
        // needed.
        vista.isVisible = true
        CipherSchermoProtetto.aggiorna(ime)
        chi?.text = intestazione
        quando?.text = data
        testo?.text = messaggio
    }

    /**
     * Closes and **forgets**. The text is cleared here, not just hidden: an
     * invisible view still holding plaintext keeps it in memory as long as the
     * keyboard lives.
     */
    fun chiudi() {
        pannello?.isVisible = false
        chi?.text = ""
        quando?.text = ""
        testo?.text = ""
        // The flag is NOT cleared here: the compose row may still be on screen.
        // Recompute; the arbiter clears it only if nobody needs it.
        CipherSchermoProtetto.aggiorna(servizio)
    }

    /** dp -> pixels. The border is declared in pixels and must stay equally thin
     *  on every density. */
    private fun Int.dp(view: View): Int =
        (this * view.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    /**
     * What the panel is showing, or `null` if closed. Used to show it again
     * without decrypting a second time: with forward secrecy a message opens once.
     * See `CipherActions.attendiFinestra`.
     */
    fun contenuto(): Triple<String, String, String>? {
        if (!isAperto()) return null
        return Triple(chi?.text.toString(), quando?.text.toString(), testo?.text.toString())
    }

    /** Open now? For deciding whether the back key closes it. */
    fun isAperto(): Boolean = pannello?.isVisible == true
}
