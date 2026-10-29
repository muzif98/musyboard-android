package helium314.keyboard.cipher

import helium314.keyboard.event.HapticEvent
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.isVisible
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.settings.Settings

/**
 * Compose row: the plaintext is typed **inside the keyboard**, and the app only
 * receives the blob when the lock key is pressed.
 *
 * ## What it closes
 *
 * Without it, plaintext was typed into the chat app's field and stayed there
 * until encryption. During that window the app sees all of it: it is its own
 * `EditText`, it receives every keystroke, it can save it as a draft on its
 * server and announce "typing". The project exists not to hand plaintext to
 * the chat app, and it was handing it over, only for a few seconds, with the
 * user believing otherwise.
 *
 * With the row active the app's field stays **empty** until the lock key.
 *
 * What was already outside the threat model stays outside: a keylogger, an
 * accessibility service or a screenshot see the keyboard like anything else.
 * This moves the boundary between keyboard and chat app, not between keyboard
 * and system.
 *
 * ## How it works
 *
 * There is no second typing engine: only the `InputConnection` is replaced.
 * HeliBoard writes wherever `getCurrentInputConnection` says, so when the mode
 * is active it is enough for that to be a connection on a buffer of ours, and
 * correction, suggestions, deletion, cursor and gestures keep working
 * **unchanged**. Reimplementing them would mean writing a keyboard inside a
 * keyboard.
 *
 * ## The buffer belongs to an app
 *
 * The recipient is per app. Text started in one conversation must not be
 * encryptable in another: it would be encrypted for the wrong person, the worst
 * failure this system can produce. So the buffer remembers the package it was
 * created for and is cleared when focus moves to a different app (not when
 * focus is lost, otherwise going to decrypt a message and coming back would
 * throw away what was being written).
 */
object CipherCompose {

    /**
     * How much longer the press that sends one's own key lasts, as a fraction of
     * the system long-press timeout: 0.5 means one and a half times (600 ms with
     * the default). Proportional rather than in milliseconds, so it follows
     * users who lengthened the threshold in accessibility settings.
     */
    private const val ATTESA_PRESENTAZIONE = 0.5f

    /**
     * To be called from `onLongClick` of the "encrypt" key, in the row or in the
     * toolbar: the system threshold has just passed, and `azione` runs only if
     * the finger stays down for another [ATTESA_PRESENTAZIONE]. Releasing
     * earlier counts as a normal tap; leaving the key cancels.
     *
     * The touch listener is attached here and removes itself: the toolbar key is
     * an upstream view, and nothing must stay attached to it.
     */
    @JvmStatic
    @SuppressLint("ClickableViewAccessibility") // performClick is called, on early release
    fun presentaDopoAttesa(view: View, azione: () -> Unit) {
        val scatta = Runnable {
            view.setOnTouchListener(null)
            azione()
        }
        view.setOnTouchListener { v, e ->
            val fuori = e.x < 0 || e.y < 0 || e.x > v.width || e.y > v.height
            when (e.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(scatta)
                    v.setOnTouchListener(null)
                    if (!fuori) v.performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(scatta)
                    v.setOnTouchListener(null)
                }
                MotionEvent.ACTION_MOVE -> if (fuori) {
                    v.removeCallbacks(scatta)
                    v.setOnTouchListener(null)
                }
            }
            false
        }
        view.postDelayed(scatta, (ViewConfiguration.getLongPressTimeout() * ATTESA_PRESENTAZIONE).toLong())
    }

    private var enabled = false

    /** The package the text in the buffer belongs to. */
    private var owner: String = ""

    /** The keys that follow the row. `COMPOSE` is not here: that one always stays. */
    private val TASTI_CIFRATURA = setOf(
        ToolbarKey.ENCRYPT,
        ToolbarKey.DECRYPT,
        ToolbarKey.SEND_PLAIN,
        ToolbarKey.ATTACH,
        ToolbarKey.GALLERY,
        ToolbarKey.CONTACTS,
    )

    /** See `CipherSettings.PREF_LEARN`. Reread in [reload]. */
    private var apprendi = false

    /**
     * The field on which the user asked for the row **by hand**, overriding
     * automatic classification. `null` when there is no override.
     *
     * The exact field (package plus `fieldId`) is remembered rather than a
     * boolean: an override surviving a field change would show the row where
     * nobody asked for it, most likely in the next field of the same app.
     * Asking for the row on a search bar is not asking for it forever.
     */
    private var forzataSu: Triple<String, Int, Int>? = null

    /** Our own package, so we never own the buffer. See [onInputStarted]. */
    private var self: String = ""

    private var connection: CipherConnection? = null
    private var row: CipherComposeView? = null
    private var barra: View? = null
    private var destinatario: TextView? = null

    /** The row with the "use it here / delete" choice. See [inSospeso]. */
    private var sceltaBozza: View? = null

    /** The "Really send in clear?" bar. See [chiediPrimaDelChiaro]. */
    private var confermaChiaro: View? = null

    /**
     * There is an unsent message in the row and a **new** field of the same app
     * was just entered: it may be another chat.
     *
     * A keyboard does not know which conversation it is in (`EditorInfo` gives
     * the app, not the chat), so it cannot decide alone whether that text should
     * be discarded (it was for the previous chat) or brought here (the user
     * switched chat on purpose). It asks, and until the choice is made the lock
     * key does not encrypt: the case to avoid is a message written for one
     * person encrypted for the recipient of another chat.
     *
     * The signal is a non-`restarting` input start in the same app: entering a
     * chat produces it, hiding and reopening the keyboard does not, nor does the
     * same field restarting after a send.
     */
    private var sospeso = false

    /**
     * Between the app's last field and this one there was one of our screens
     * (reading a message, choosing the recipient, settings). Coming back from
     * there means the same chat as before, and asking would just be noise.
     */
    private var passatoDaNoi = false

    /**
     * The draft has already been accepted for this field ("Use it here", or the
     * return from one of our screens) and is not asked about again until the
     * field is really left.
     *
     * [passatoDaNoi] alone was not enough: it applies to one input start, and
     * some apps produce several on the same field when coming to the
     * foreground, which put the draft back on hold in a loop.
     *
     * Leaving the field here means a start with no editor, or on a field of the
     * same app that does not compose messages (chat list, search), or another
     * app: the step between one chat and another.
     */
    private var confermata = false

    /**
     * Something was encrypted or decrypted since the last confirmation: the next
     * "send in clear" may be a wrong key, so ask first. Cleared only by a "Yes",
     * so the question comes once and then plaintext goes directly until the
     * next encryption or read.
     *
     * In memory only, and starts on: after a process restart the last message is
     * unknown, so the first plaintext is asked about anyway.
     */
    private var chiaroDaChiedere = true

    /** The confirmation bar is on screen. */
    private var chiedeChiaro = false

    /** The selection drop-down menu. See [mostraMenu]. */
    private var menu: View? = null

    /**
     * The service, to open recipient selection from a tap on the name. It cannot
     * be derived from the view: inside an IME window the context is a themed
     * wrapper, not the service, and the check failed silently.
     */
    private var servizio: InputMethodService? = null

    /**
     * The two toolbar key bars (the expandable one and the pinned keys). Used
     * to hide the encryption keys on fields without the row: see
     * [aggiornaTastiCifratura].
     */
    private var barreTasti: List<ViewGroup> = emptyList()

    /**
     * Suspended for the current field. See [onInputStarted]: on a password the
     * row must neither appear nor receive anything.
     */
    private var suppressed = false

    /** See [CipherSettings.PREF_BLOCK_COPY]. */
    private var bloccaCopia = CipherSettings.DEFAULT_BLOCK_COPY

    fun reload(context: Context) {
        self = context.packageName
        // Outside the early return below: that one compares only the compose
        // mode, and changing just the copy switch does not move it, so the new
        // preference would never be reread.
        bloccaCopia = CipherSettings.isBlockCopy(context)
        // As above, outside the early return. Kept in a field rather than read on
        // the fly because [apprendimentoVietato] is called for every composed
        // word.
        apprendi = CipherSettings.isLearn(context)
        val wanted = CipherSettings.isComposeMode(context)
        // The debug log receives typed words: with the row on it must stay quiet.
        // Here because encryption can be turned on after start, when
        // DebugFlags.init has already decided. See Defaults.PREF_DEBUG_MODE.
        if (wanted) helium314.keyboard.latin.define.DebugFlags.DEBUG_ENABLED = false
        if (wanted == enabled) return
        enabled = wanted
        // Turning the mode off, the buffer has nowhere to be shown: leaving it
        // full would keep plaintext in memory that nobody can see or encrypt.
        if (!enabled) clear()
        updateRow()
    }

    fun isEnabled(): Boolean = enabled

    /**
     * The connection to give HeliBoard instead of the app's, or `null` if text
     * should go to the app as usual.
     */
    fun connection(): InputConnection? = if (enabled && !suppressed) connection else null

    /**
     * How tall the row is now, zero if absent.
     *
     * Used by `onComputeInsets`, from which the system decides where the app
     * ends and the keyboard begins. Without counting it, the app was drawn
     * **under** the row, which covered its text box and its attach and
     * microphone buttons.
     */
    fun rowHeight(): Int {
        val view = barra ?: return 0
        return if (view.isVisible) view.height else 0
    }

    /**
     * Start and end of the selection in the buffer, or `null` if keystrokes go
     * to the app. Used by `LatinIME` when input restarts: otherwise it took the
     * cursor from the app's field, which is empty with the row active.
     */
    fun selezione(): IntArray? {
        val buffer = (connection() as? CipherConnection)?.buffer ?: return null
        val a = Selection.getSelectionStart(buffer).coerceAtLeast(0)
        val b = Selection.getSelectionEnd(buffer).coerceAtLeast(a)
        return intArrayOf(a, b)
    }

    /** The plaintext composed so far. */
    fun text(): String = connection?.buffer?.toString().orEmpty()

    fun isEmpty(): Boolean = text().isEmpty()

    /**
     * Clears the buffer, overwriting its content first. See
     * [svuotaSovrascrivendo] for what this guarantees and what it does not.
     */
    fun clear() {
        svuotaSovrascrivendo(connection?.buffer)
        sospeso = false
        confermata = false
        chiedeChiaro = false
        // HeliBoard still thinks the cursor is at the end of the message just
        // sent: without notice the next keystroke would start from there.
        avvisaDelloSpostamento()
        // `owner` is NOT reset here. The owner says which app the buffer's text
        // belongs to; clearing the buffer does not change which app has focus,
        // and the next keystroke is still its own. Resetting it left an ownerless
        // buffer right after EVERY successful send, and the guard in
        // [onInputStarted] does not clear when the owner is empty: text typed
        // right after a send and carried to another chat was encrypted for the
        // OTHER app's recipient.
        updateRow()
    }

    /**
     * Removes the text from the buffer after overwriting it.
     *
     * `SpannableStringBuilder.clear()` alone zeroizes nothing: it moves indices
     * and leaves the plaintext in the character array the buffer keeps. An
     * abandoned message (switching app, entering a password field) stayed
     * readable on the heap until GC or the next keystroke covered it.
     *
     * The replacement has the SAME length on purpose: the buffer does not need
     * to resize, so the NULs land in the array that held the plaintext rather
     * than in a new one (which would leave the old one intact).
     *
     * Whatever already left is out of reach: every `String` produced by [text]
     * is immutable. This covers the working copy, not every copy: the
     * zeroization guarantee stops where the JVM begins, and this is the part that
     * can be done.
     */
    private fun svuotaSovrascrivendo(buffer: Editable?) {
        if (buffer == null) return
        val lunghezza = buffer.length
        if (lunghezza > 0) buffer.replace(0, lunghezza, String(CharArray(lunghezza)))
        buffer.clear()
    }

    /**
     * Realigns with the field that has focus now. Must be called **right after
     * turning the mode on**.
     *
     * [onInputStarted] returns immediately when the mode is off, so `owner` and
     * `suppressed` stay frozen on the last app where it was on, across any
     * number of app switches. On turning it back on, the row would repaint with
     * that stale owner: the label named the recipient of **another chat** while
     * encryption reads the current one.
     */
    fun risincronizza(ime: InputMethodService) {
        onInputStarted(ime.currentInputEditorInfo, restarting = true)
    }

    /** See [chiaroDaChiedere]. Call after a successful encryption or read. */
    fun segnaCifrato() {
        chiaroDaChiedere = true
    }

    /**
     * Call before delivering in clear: if a confirmation is due, shows the bar
     * in place of the recipient and returns true, and sending stops there. The
     * "Yes" runs it again from the start.
     */
    fun chiediPrimaDelChiaro(): Boolean {
        if (!chiaroDaChiedere) return false
        chiedeChiaro = true
        updateRow()
        return true
    }

    private fun rispostaChiaro(si: Boolean) {
        chiedeChiaro = false
        if (si) chiaroDaChiedere = false
        updateRow()
        if (si) servizio?.let { CipherActions.sendPlain(it) }
    }

    /** See [sospeso]. */
    fun inSospeso(): Boolean = sospeso && enabled && !suppressed && !isEmpty()

    /** "Use it here": the text stays, but the recipient must be confirmed. */
    private fun usaQui() {
        sospeso = false
        confermata = true
        updateRow()
        // Open recipient selection with the current one pre-selected: if the
        // chat was changed because the previous one was wrong, the recipient
        // almost always is too. Coming back from that screen must not ask again.
        passatoDaNoi = true
        servizio?.let { CipherActions.chiediDestinatarioOra(it) }
    }

    /**
     * Focus moved to a field of [editorInfo]. If the text in progress was for
     * another app it is discarded: see the note on per-app recipients at the top.
     *
     * Two cases must be **ignored**, because changing owner clears the buffer,
     * so every false owner is a message lost while writing it:
     *
     *  - `TYPE_NULL`: focus is on something that is not a text field (a screen
     *    made only of buttons);
     *  - **our own** screens: going to decrypt a received message and coming
     *    back passes through here, in the most common flow, read and reply.
     */
    fun onInputStarted(editorInfo: EditorInfo?, restarting: Boolean) {
        if (!enabled) return
        // A half-answered question does not follow the user into another field.
        chiedeChiaro = false
        val app = editorInfo?.packageName.orEmpty()
        // On a password the row does NOT appear and receives nothing: it would
        // show on screen what the field hides behind dots, and keep it in a buffer
        // of ours. See [CipherFields] for the list of fields that are not message
        // composers and why the rule is "off on contrary evidence" rather than
        // "on on supporting evidence".
        // Three cases. Forbidden: password, no discussion. Forced by hand on THIS
        // field: the user pressed the key here, and their request beats our
        // classification. Otherwise [CipherFields] decides.
        // Field identity: package, view id and input type. The id alone is not
        // enough (two screens of one app can reuse a layout id) and the type
        // separates the common cases. Not a guarantee: two truly identical fields
        // on two screens remain indistinguishable, and the forced row may
        // reappear there. That is a nuisance, not a recipient error: the owner is
        // still realigned to the current app.
        val identita = editorInfo?.let { Triple(app, it.fieldId, it.inputType) }
        if (forzataSu != null && forzataSu != identita) forzataSu = null
        suppressed = when {
            editorInfo == null -> false
            CipherFields.vietata(editorInfo) -> true
            // Our OWN screens are not a place to compose an encrypted message.
            // This must decide `suppressed`: otherwise `connection()` kept
            // diverting keystrokes into our buffer, and the "name this contact"
            // field stayed empty while the letters were appended to the message
            // about to be encrypted and sent. It also wins over a manual
            // override.
            app == self -> true
            forzataSu != null -> false
            else -> CipherFields.nonComponeMessaggi(editorInfo)
        }
        if (suppressed) {
            if (app == self) passatoDaNoi = true else confermata = false
            // **The buffer is not cleared.** Suspending means "keystrokes do not
            // come here", nothing more: deleting the message is an extra action
            // nobody asked for. Our own screens suspend, and two of them
            // ("change recipient", reading a message) are used **while**
            // writing.
            //
            // Plaintext in memory does not increase: it was there an instant
            // before. Leaking across apps is still prevented by the `owner` check
            // below, which looks at WHO the text belongs to.
            updateRow()
            return
        }
        if (editorInfo == null || editorInfo.inputType == InputType.TYPE_NULL) {
            if (app != self) confermata = false
            return
        }
        if (app.isEmpty() || app == self) return
        // No shortcut on an empty `owner`: a buffer of unknown ownership must not
        // be inherited by a new app. Clearing an empty buffer costs nothing;
        // keeping it to attribute it to whoever comes next costs a message sent
        // to the wrong person.
        if (owner != app) {
            svuotaSovrascrivendo(connection?.buffer)
            sospeso = false
            confermata = false
        } else if (passatoDaNoi) {
            confermata = true
        } else if (!restarting && !confermata && !isEmpty()) {
            sospeso = true
        }
        passatoDaNoi = false
        owner = app
        // If this app has no recipient yet, it inherits the last contact read.
        // Never overwrites a choice already made.
        servizio?.let { CipherRecipients.ereditaUltimoLetto(it, app) }
        updateRow()
        updateRecipient(app)
    }

    /**
     * Attaches the row to the newly created view. Must be called on every
     * `setInputView`: the hierarchy is rebuilt on every theme, rotation or mode
     * change, and a reference kept longer would point to a detached view.
     */
    fun bind(ime: InputMethodService, view: View) {
        servizio = ime
        val found: CipherComposeView? = view.findViewById(R.id.cipher_compose_row)
        row = found
        barra = view.findViewById(R.id.cipher_compose_bar)
        destinatario = view.findViewById(R.id.cipher_recipient)
        sceltaBozza = view.findViewById(R.id.cipher_pending)
        view.findViewById<TextView>(R.id.cipher_pending_use)?.setOnClickListener { usaQui() }
        view.findViewById<TextView>(R.id.cipher_pending_delete)?.setOnClickListener { clear() }
        confermaChiaro = view.findViewById(R.id.cipher_plain_confirm)
        view.findViewById<TextView>(R.id.cipher_plain_yes)?.setOnClickListener { rispostaChiaro(true) }
        view.findViewById<TextView>(R.id.cipher_plain_no)?.setOnClickListener { rispostaChiaro(false) }
        Settings.getValues().mColors.let { colori ->
            for (id in intArrayOf(R.id.cipher_pending_label, R.id.cipher_plain_label)) {
                view.findViewById<TextView>(id)?.setTextColor(colori.get(ColorType.KEY_HINT_TEXT))
            }
            for (id in intArrayOf(
                R.id.cipher_pending_use, R.id.cipher_pending_delete,
                R.id.cipher_plain_yes, R.id.cipher_plain_no,
            )) {
                view.findViewById<TextView>(id)?.setTextColor(colori.get(ColorType.KEY_TEXT))
            }
        }
        // Tapping the name changes recipient: it is where the user is already
        // looking when wondering "who am I sending this to?".
        destinatario?.setOnClickListener {
            servizio?.let { ime -> CipherActions.chiediDestinatarioOra(ime) }
        }
        if (found == null) return
        if (connection == null) connection = CipherConnection(view) { updateRow() }
        found.onSelezione = { inizio, fine -> spostaSelezione(inizio, fine) }
        found.onMenu = { x -> mostraMenu(x) }
        found.onMenuVuoto = { x -> mostraMenu(x, soloIncolla = true) }
        found.onMenuDaChiudere = { chiudiMenu() }
        // Tapping the row means "I'm replying": the decrypted message panel
        // covers the keys, so it gets out of the way by itself.
        found.onToccata = { CipherPanel.chiudi() }

        // The two keys inside the row. `servizio` is already set above.
        view.findViewById<android.widget.ImageButton>(R.id.cipher_compose_send)?.apply {
            setOnClickListener { servizio?.let { CipherActions.encrypt(it) } }
            // Long press: inserts one's own identity card, as on the toolbar key.
            // These are our own views, so the toolbar's long-press routing does
            // not apply and the long press is wired here.
            //
            // Longer than a normal long press: this is the most pressed key, and
            // whoever lingers a moment wanted to encrypt, not send their key. Up
            // to the system threshold it is a tap; from there to ours, releasing
            // still means "encrypt"; only beyond it does the card go, with the
            // vibration at that moment.
            setOnLongClickListener {
                val ime = servizio ?: return@setOnLongClickListener false
                presentaDopoAttesa(this) {
                    AudioAndHapticFeedbackManager.getInstance()
                        .performHapticFeedback(this, HapticEvent.KEY_LONG_PRESS)
                    CipherActions.insertIdentityCard(ime)
                }
                true
            }
        }
        view.findViewById<android.widget.ImageButton>(R.id.cipher_compose_plain)?.apply {
            setOnClickListener { servizio?.let { CipherActions.sendPlain(it) } }
        }
        barreTasti = listOfNotNull(
            view.findViewById<ViewGroup>(R.id.toolbar),
            view.findViewById<ViewGroup>(R.id.pinned_keys),
        )
        val colors = Settings.getValues().mColors
        barra?.let { colors.setBackground(it, ColorType.STRIP_BACKGROUND) }
        colors.setBackground(found, ColorType.STRIP_BACKGROUND)
        found.setTextColor(colors.get(ColorType.KEY_TEXT))
        found.setHintTextColor(colors.get(ColorType.KEY_HINT_TEXT))
        found.setCaretColor(colors.get(ColorType.KEY_TEXT))
        for (id in intArrayOf(R.id.cipher_compose_send, R.id.cipher_compose_plain)) {
            view.findViewById<android.widget.ImageButton>(id)
                ?.setColorFilter(colors.get(ColorType.KEY_ICON))
        }
        collegaMenu(view, colors)
        updateRow()
        updateRecipient(owner)
    }

    /**
     * Takes into the buffer the text that was already in the app's field.
     *
     * Otherwise text stayed on screen that the keyboard could no longer touch:
     * deletion and cursor work on the buffer, so the last word typed before
     * turning the mode on (or a draft restored by the app) became undeletable.
     *
     * Adopted only into an empty buffer: what is being composed is not touched.
     */
    fun adopt(text: CharSequence) {
        val connection = connection ?: return
        if (!enabled || suppressed || text.isEmpty()) return
        if (connection.buffer.isNotEmpty()) return
        connection.buffer.append(text)
        Selection.setSelection(connection.buffer, connection.buffer.length)
        updateRow()
        avvisaDelloSpostamento()
    }

    fun isEmptyBuffer(): Boolean = connection?.buffer?.isEmpty() != false

    /**
     * The row is suspended for the current field: not on screen, not receiving
     * keystrokes, and the app is writing again. See [onInputStarted].
     *
     * Different from `!isEnabled()`, which is about the preference: here the
     * mode is on and the row exists, **this** field just does not use it. Used
     * by whoever takes text out of the app's field (see
     * `CipherActions.adoptFieldText`): with the row suspended there is nowhere
     * to put it.
     */
    fun isSuppressed(): Boolean = suppressed

    /**
     * Copying now would take plaintext out, and the user asked not to allow it.
     *
     * The buffer must hold something and, above all, must be **the one**
     * receiving keystrokes: on a password field the row is suspended and the
     * keyboard writes into the app's field again, so what would be copied belongs
     * to the app.
     */
    fun copiaDelChiaroVietata(): Boolean =
        bloccaCopia && connection() != null && !isEmptyBuffer()

    /**
     * The keyboard must not LEARN what is being written here.
     *
     * The rest of the keyboard does not know this text will be encrypted: it
     * receives keystrokes as usual, because `getCurrentInputConnection` is
     * redirected to the row. So plaintext ended up in the personal dictionary
     * (in `filesDir`, hence in Android's automatic backup) and resurfaced as a
     * suggestion **while typing in clear in another app**. Encrypting a message
     * and then being offered the name it contained is worse than not encrypting.
     *
     * The condition is the same that decides whether keystrokes go to our
     * buffer: `connection() != null`. Reused, not duplicated: two criteria that
     * must coincide sooner or later diverge.
     *
     * Callers treat it like the existing incognito mode (`mIncognitoModeEnabled`),
     * HeliBoard's own mechanism for "do not learn".
     *
     * It does NOT cover gesture data gathering (`GestureDataGathering`), which has
     * its own decision and is off by default.
     *
     * Users can re-enable learning with `CipherSettings.PREF_LEARN`, off by
     * default: the cost is real (the keyboard does not improve on the vocabulary
     * of those who write encrypted often), but those who never open settings
     * must have the promise kept.
     */
    fun apprendimentoVietato(): Boolean = connection() != null && !apprendi

    /**
     * The finger moved the cursor or selected some text.
     *
     * The step that matters is **telling HeliBoard**. For a real field the
     * system calls `onUpdateSelection` when the cursor moves, and from there the
     * keyboard learns that the word being composed changed and that text is
     * selected. Here the buffer is ours, and nobody would call it.
     *
     * It is also what makes delete work: HeliBoard deletes a whole selection only
     * if it knows it exists.
     */
    private fun spostaSelezione(inizio: Int, fine: Int) {
        val connection = connection ?: return
        val buffer = connection.buffer
        val a = inizio.coerceIn(0, buffer.length)
        val b = fine.coerceIn(a, buffer.length)
        // The composing word ends here: the cursor went elsewhere, and leaving it
        // open would make the next keystroke rewrite text somewhere else.
        connection.finishComposingText()
        Selection.setSelection(buffer, a, b)
        updateRow()
        avvisaDelloSpostamento()
    }

    /**
     * Pastes inside the row instead of letting the fake CTRL+V through.
     *
     * HeliBoard's paste key does not deliver text: it sends a CTRL+V event
     * hoping the app's field interprets it. Here the field is our buffer, so
     * that event produced nothing. Pasting **into** the row is safe: text comes
     * in, the opposite of copying it out.
     *
     * Reading the clipboard here does not break the rule of not snooping on it:
     * it is the user's explicit gesture.
     *
     * @return true if the key was handled here, false if it must take the normal
     * path to the app.
     */
    fun incolla(context: Context): Boolean {
        // The connection **in use**, not the one that exists: on a password field
        // the row is suspended and the app is writing, so pasting here would put
        // the text into a buffer nobody sees.
        val connection = connection() as? CipherConnection ?: return false
        val appunti = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        // `toString`: the buffer keeps plain text, spans would come along for no
        // reason.
        val testo = appunti?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
        // Empty clipboard: handled anyway, doing nothing. Letting it through would
        // send CTRL+V to the app, which would paste into its own field.
        if (testo.isNullOrEmpty()) return true
        // `commitText` replaces the selection, as expected from a paste, and goes
        // through the connection because that is where the row update lives.
        connection.commitText(testo, 1)
        avvisaDelloSpostamento()
        return true
    }

    /**
     * Tells HeliBoard where the cursor went.
     *
     * Deferred, as the system would do. For a real field `onUpdateSelection`
     * never arrives inside the call that moved the cursor, but after: "select
     * all" and "paste" arrive here **from inside** HeliBoard, and calling it
     * immediately would re-enter it while it is still reading the text.
     *
     * The position is read **when the notice fires**, not when it is scheduled:
     * another keystroke may have happened meanwhile.
     *
     * Not through `onUpdateSelection`: with the row active that ignores the
     * positions it receives, which come from the system and belong to the app's
     * field. See `LatinIME.onCipherSelectionChanged`.
     */
    private fun avvisaDelloSpostamento() {
        val avvisa = Runnable {
            val (a, b) = selezione() ?: return@Runnable
            (servizio as? LatinIME)?.onCipherSelectionChanged(a, b)
        }
        if (row?.post(avvisa) != true) avvisa.run()
    }

    /**
     * Updates the recipient name shown next to the row.
     *
     * The core is queried every time instead of keeping a copy: it changes when
     * a message is decrypted, when chosen by hand, and when the service
     * restarts. A cached copy would be one more state to keep in sync, and
     * getting it wrong would show one name while encrypting for another.
     */
    fun updateRecipient(appPackage: String) {
        val view = destinatario ?: return
        // The group comes BEFORE the person: if chosen, it is the recipient, and
        // showing the previously selected individual would say the message goes to
        // one person.
        val contesto = view.context
        val gruppo = if (appPackage.isNotEmpty()) CipherGroups.corrente(contesto, appPackage) else null
        val nome = when {
            gruppo != null -> contesto.getString(
                R.string.cipher_recipient_group,
                gruppo.nome,
                gruppo.membri.size,
            )
            CipherCore.available && appPackage.isNotEmpty() ->
                CipherCore.nativeCurrentPeerName(appPackage)
            else -> null
        }
        val colori = Settings.getValues().mColors
        view.text = when {
            nome != null -> "→ $nome"
            else -> view.context.getString(R.string.cipher_no_recipient_short)
        }
        view.setTextColor(
            colori.get(if (nome != null) ColorType.KEY_TEXT else ColorType.KEY_HINT_TEXT)
        )
    }

    // ========================================================================
    // Selection drop-down menu
    // ========================================================================

    /**
     * Wires the five entries, once per hierarchy.
     *
     * **No action is written here.** Each sends the key code HeliBoard already
     * uses for the same thing, ending in `InputLogic` where the rules are written
     * once, including the ban on copying plaintext. See
     * `InputLogic.rifiutaCopiaDelChiaro`.
     */
    private fun collegaMenu(view: View, colori: helium314.keyboard.latin.common.Colors) {
        val tendina = view.findViewById<View>(R.id.cipher_selection_menu)
        menu = tendina ?: return
        colori.setBackground(tendina, ColorType.POPUP_KEYS_BACKGROUND)
        val voci = listOf(
            R.id.cipher_menu_copy to KeyCode.CLIPBOARD_COPY,
            R.id.cipher_menu_paste to KeyCode.CLIPBOARD_PASTE,
            R.id.cipher_menu_cut to KeyCode.CLIPBOARD_CUT,
            R.id.cipher_menu_delete to KeyCode.DELETE,
            R.id.cipher_menu_select_all to KeyCode.CLIPBOARD_SELECT_ALL,
        )
        // "Delete all" does not go through a key code: it clears the buffer by
        // overwriting it, as after a successful send. "Select all" plus "delete"
        // would look the same on screen while leaving plaintext in the array.
        tendina.findViewById<TextView>(R.id.cipher_menu_delete_all)?.apply {
            setTextColor(colori.get(ColorType.KEY_TEXT))
            setOnClickListener {
                chiudiMenu()
                clear()
            }
        }
        for ((id, codice) in voci) {
            val voce = tendina.findViewById<TextView>(id) ?: continue
            voce.setTextColor(colori.get(ColorType.KEY_TEXT))
            voce.setOnClickListener {
                // "Select all" is the only entry that does NOT close the menu, as in
                // Android: selecting all is a prelude (then you copy, cut or
                // delete). The others close because they are conclusions.
                if (codice != KeyCode.CLIPBOARD_SELECT_ALL) chiudiMenu()
                (servizio as? LatinIME)?.onEvent(
                    Event.createSoftwareKeypressEvent(
                        codice, 0, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false,
                    )
                )
            }
        }
    }

    /**
     * Opens the menu under the point where the finger was lifted.
     *
     * The x comes from the row and is adjusted so the menu does not go off the
     * right edge. The y is not needed: the row is 56dp tall and at the top, so
     * the menu always drops from there over the keys, the only available space
     * (the app is above).
     */
    private fun mostraMenu(x: Float, soloIncolla: Boolean = false) {
        val tendina = menu ?: return
        val riga = row ?: return
        // On an empty row only one entry makes sense (paste): there is nothing to
        // copy, cut, delete or select.
        for (id in intArrayOf(
            R.id.cipher_menu_copy,
            R.id.cipher_menu_cut,
            R.id.cipher_menu_delete,
            R.id.cipher_menu_select_all,
            R.id.cipher_menu_delete_all,
        )) {
            tendina.findViewById<View>(id)?.isVisible = !soloIncolla
        }
        tendina.visibility = View.VISIBLE
        // Measured before positioning: right after being shown its width is still
        // zero.
        tendina.post {
            val padre = tendina.parent as? View ?: return@post
            val margine = (x + riga.left - tendina.width / 2f)
                .coerceIn(0f, (padre.width - tendina.width).coerceAtLeast(0).toFloat())
            tendina.translationX = margine
        }
    }

    private fun chiudiMenu() {
        menu?.visibility = View.GONE
    }

    /**
     * Closes the menu if the selection is gone.
     *
     * Called on every row update: deleting, pasting or typing collapse the
     * selection, and a menu outliving its target offers actions without a
     * target.
     */
    private fun chiudiMenuSenzaSelezione(inizio: Int, fine: Int) {
        if (fine <= inizio) chiudiMenu()
    }

    /**
     * The encryption keys follow the row: where the row is absent they disappear
     * too, except the toggle, the only way to bring it back.
     *
     * On a search bar, a keyboard with lock keys and no row to type in is not
     * "encrypted mode": it is a panel of commands that command nothing.
     * "Encrypt" would take what is in the app's field, there the user's search.
     *
     * The VIEWS are hidden rather than rebuilding the toolbar, which is built
     * once when the keyboard view is created; changing its list would mean
     * rebuilding everything on every switch between a chat and a search.
     *
     * Keys are recognised by their `tag`, the `ToolbarKey` they were created
     * with.
     *
     * This also applies with encryption fully off: the toolbar is always created
     * with the encryption keys (see `withCipherKeys`) and this function hides
     * them, so the toggle can switch without rebuilding the keyboard.
     */
    private fun aggiornaTastiCifratura() {
        val mostra = enabled && !suppressed
        for (barra in barreTasti) {
            for (i in 0 until barra.childCount) {
                val figlio = barra.getChildAt(i) ?: continue
                val chiave = figlio.tag as? ToolbarKey ?: continue
                // The toggle stays, but its "on" state must be restored here (it
                // used to be repainted by a keyboard rebuild, which no longer
                // happens; see `CipherActions.toggleCompose`).
                if (chiave == ToolbarKey.COMPOSE) {
                    figlio.isActivated = enabled
                    continue
                }
                // Like COMPOSE, DECRYPT survives the absence of the row: reading
                // does not depend on the compose row, and an incoming message must
                // open even without any intention of replying encrypted.
                if (chiave == ToolbarKey.DECRYPT) continue
                if (chiave in TASTI_CIFRATURA) figlio.isVisible = mostra
            }
        }
    }

    /**
     * The row is on screen now. Used by [CipherSchermoProtetto] to decide whether
     * the window must be protected from screenshots: plaintext is typed here.
     */
    fun rigaASchermo(): Boolean = enabled && !suppressed

    /**
     * The row is forbidden on the current field: no override will show it. Lets
     * the toolbar key tell the truth instead of promising a row that will not
     * come.
     */
    fun rigaVietataQui(ime: InputMethodService): Boolean =
        ime.currentInputEditorInfo?.let { CipherFields.vietata(it) } ?: false

    /**
     * The user asks for the row on this field, overriding the classification.
     *
     * Returns `false` if the field forbids it (password), so the caller can
     * explain. The override applies to THIS field and drops at the first change,
     * see [forzataSu].
     */
    fun forza(ime: InputMethodService): Boolean {
        val editorInfo = ime.currentInputEditorInfo ?: return false
        if (CipherFields.vietata(editorInfo)) return false
        val app = editorInfo.packageName.orEmpty()
        // The owner must be updated HERE: on a suspended field `onInputStarted`
        // returns before assigning it, so `owner` is still the previous app's.
        // Without this, text typed in the newly forced row would belong to that
        // other app, and going back the change guard would not clear it: it would
        // be encrypted for the wrong recipient.
        if (owner.isNotEmpty() && owner != app) svuotaSovrascrivendo(connection?.buffer)
        owner = app
        forzataSu = Triple(app, editorInfo.fieldId, editorInfo.inputType)
        suppressed = false
        // Gesture data gathering is recomputed **now**. Its decision was taken
        // when focus arrived, when the row was not here and the field
        // classification said no: otherwise what is typed in the encrypted row
        // would keep being gathered. See `isBackgroundGatheringUsed`.
        (ime as? LatinIME)?.setGestureDataGatheringMode(editorInfo, false)
        updateRow()
        updateRecipient(app)
        return true
    }

    private fun updateRow() {
        val view = row ?: return
        barra?.isVisible = enabled && !suppressed
        view.isVisible = enabled && !suppressed
        // The row may have just appeared or disappeared: the window flag is
        // recomputed here rather than in each caller.
        CipherSchermoProtetto.aggiorna(servizio)
        aggiornaTastiCifratura()
        val scelta = inSospeso()
        sceltaBozza?.isVisible = scelta
        confermaChiaro?.isVisible = chiedeChiaro && !scelta
        destinatario?.isVisible = !scelta && !chiedeChiaro
        if (!enabled || suppressed) return
        val buffer = connection?.buffer
        val text = buffer?.toString().orEmpty()
        // The cursor position comes from the buffer, not from us: HeliBoard moves
        // it, and it is the only source of truth on where the next character goes.
        val start = buffer?.let { Selection.getSelectionStart(it) } ?: 0
        val end = buffer?.let { Selection.getSelectionEnd(it) } ?: 0
        val a = minOf(start, end).coerceAtLeast(0)
        val b = maxOf(start, end).coerceAtLeast(0)
        chiudiMenuSenzaSelezione(a, b)
        view.setComposed(text, a, b)
    }

    /**
     * `BaseInputConnection` on a buffer of ours.
     *
     * `BaseInputConnection` already implements on `getEditable()` everything
     * needed (text before/after the cursor, composition, deletion, selection,
     * auto-caps), so only two things remain: saying which buffer, and preventing
     * anything from escaping to the app.
     */
    private class CipherConnection(
        view: View,
        private val onChanged: () -> Unit,
    ) : BaseInputConnection(view, true) {

        val buffer = SpannableStringBuilder().also { Selection.setSelection(it, 0) }

        override fun getEditable(): Editable = buffer

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
            super.commitText(text, newCursorPosition).also { onChanged() }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
            super.setComposingText(text, newCursorPosition).also { onChanged() }

        override fun setComposingRegion(start: Int, end: Int): Boolean =
            super.setComposingRegion(start, end).also { onChanged() }

        override fun finishComposingText(): Boolean =
            super.finishComposingText().also { onChanged() }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            super.deleteSurroundingText(beforeLength, afterLength).also { onChanged() }

        override fun deleteSurroundingTextInCodePoints(before: Int, after: Int): Boolean =
            super.deleteSurroundingTextInCodePoints(before, after).also { onChanged() }

        override fun setSelection(start: Int, end: Int): Boolean =
            super.setSelection(start, end).also { onChanged() }

        /**
         * `BaseInputConnection` would forward the event to the target view, here
         * the keyboard: it would not reach the buffer. So the keys that matter are
         * applied to the buffer by hand.
         *
         * Always returns `true`, even for unhandled keys: `false` invites the
         * caller to look for another route, and the only other route leads to
         * the app.
         */
        override fun sendKeyEvent(event: KeyEvent?): Boolean {
            if (event == null || event.action != KeyEvent.ACTION_DOWN) return true
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> deleteSurroundingText(1, 0)
                KeyEvent.KEYCODE_FORWARD_DEL -> deleteSurroundingText(0, 1)
                KeyEvent.KEYCODE_ENTER -> commitText("\n", 1)
                KeyEvent.KEYCODE_DPAD_LEFT -> moveCursor(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveCursor(1)
                // Up and down: with several lines the cursor otherwise moved only
                // one character at a time. Only the view, which has the text
                // layout, can compute the position on the adjacent line.
                KeyEvent.KEYCODE_DPAD_UP -> muoviDiRiga(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> muoviDiRiga(1)
                else -> {
                    val unicode = event.unicodeChar
                    if (unicode != 0) commitText(unicode.toChar().toString(), 1)
                }
            }
            return true
        }

        private fun muoviDiRiga(delta: Int) {
            val vista = row ?: return
            val posizione = vista.offsetDiRiga(Selection.getSelectionEnd(buffer), delta)
            Selection.setSelection(buffer, posizione.coerceIn(0, buffer.length))
            onChanged()
        }

        private fun moveCursor(delta: Int) {
            val position = (Selection.getSelectionEnd(buffer) + delta).coerceIn(0, buffer.length)
            Selection.setSelection(buffer, position)
            onChanged()
        }

        /**
         * The app's enter action ("send", "search", "go").
         *
         * Does **nothing**, the most important point of this class: forwarding it
         * would hand the app the command to send while the plaintext is not yet
         * encrypted. There is nothing to send until the lock key is pressed.
         */
        override fun performEditorAction(editorAction: Int): Boolean = true

        /**
         * The context menu, which here comes from a toolbar key.
         *
         * **Only** "select all" is honoured: a shortcut to discard what was being
         * written without holding delete.
         *
         * Everything else does nothing and returns `true`: `false` invites the
         * caller to look for another route, and the only other route leads to the
         * app.
         */
        override fun performContextMenuAction(id: Int): Boolean {
            if (id == android.R.id.selectAll) spostaSelezione(0, buffer.length)
            return true
        }

        override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean = true

        override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean = true
    }
}
