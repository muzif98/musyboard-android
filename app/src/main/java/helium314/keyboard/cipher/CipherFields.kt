package helium314.keyboard.cipher

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.utils.InputTypeUtils

/**
 * Which fields the compose row turns on by itself in.
 *
 * ## The problem
 *
 * The row used to turn on **everywhere** except password fields. To the
 * keyboard a search bar looked like a chat: the text went into our buffer, the
 * app's field stayed empty, and the "search" action was swallowed by
 * [CipherCompose] (on purpose, so the app is never told to send while the
 * plaintext is not yet encrypted). Both decisions are right inside a chat;
 * outside one, the first steals the text and the second blocks the only way to
 * give it back.
 *
 * ## Why not deliver the text and then run the action
 *
 * The obvious fix is dangerous: on a chat declaring "send" it would deliver the
 * **plaintext** and press send. The right place to decide is earlier: do not
 * turn the row on where no message is being composed.
 *
 * ## The criterion, and why it was inverted
 *
 * Fields used to be assumed message composers and excluded on contrary evidence
 * (filter variant, "search", "go", "next", "done" actions, autocomplete). That
 * list grew every time the row appeared where it should not, and still missed
 * search bars that declare NOTHING (text, single line, no action).
 *
 * Now the row turns on **only on supporting evidence**, of two kinds:
 *
 *  - the field accepts **newlines**. A multi-line field is made for text one
 *    writes, not a parameter one fills in: search bars, codes and form boxes are
 *    single-line by construction. Every major messaging app's composer is
 *    multi-line;
 *  - the field declares **"send"**, the only action meaning "this text leaves
 *    here"; single-line composers declare it.
 *
 * ## The cost
 *
 * A single-line chat composer that does not even declare "send" stays out, and
 * the row must be turned on by hand there. That false negative costs one tap on
 * the toolbar key that **forces** the row; a false positive (the row on a search
 * bar) cannot be fixed at all.
 */
internal object CipherFields {

    /**
     * Whether the compose row will turn on in this field.
     *
     * For callers that must decide BEFORE the row is on, so they cannot look at
     * [CipherCompose]'s state: it is derived from the same two things the row
     * depends on (the preference and the field type) rather than from state that
     * might not be updated yet.
     */
    fun rigaPrevistaSu(context: Context, editorInfo: EditorInfo?): Boolean =
        CipherSettings.isEnabled(context) &&
            editorInfo != null &&
            !nonComponeMessaggi(editorInfo)

    /**
     * `true` if the row is **forbidden** in this field, not just not expected.
     *
     * The only exclusion the user cannot override. A search bar is not an
     * *expected* place to compose messages, but if someone wants the row there,
     * that is their call. A password field is different: the row would show on
     * screen what the field hides behind dots, and keep it in our buffer.
     */
    fun vietata(editorInfo: EditorInfo): Boolean =
        InputTypeUtils.isPasswordInputType(editorInfo.inputType) ||
            InputTypeUtils.isVisiblePasswordInputType(editorInfo.inputType)

    /**
     * `true` if the compose row does not turn on **by itself** in this field.
     * Can be overridden by hand, except where [vietata] says no.
     *
     * The parameter is non-null on purpose. A null [EditorInfo] does not mean "a
     * field that does not compose messages", it means "nothing known yet", and
     * callers already treat it as such: suspending there would clear the buffer
     * on every empty lifecycle pass, discarding the message in progress.
     */
    fun nonComponeMessaggi(editorInfo: EditorInfo): Boolean {
        // Passwords first: the only case that cannot be overridden.
        if (vietata(editorInfo)) return true

        val inputType = editorInfo.inputType

        // Outside TYPE_CLASS_TEXT nothing is composed: numbers, phone, dates are
        // fields the app reads to do something with, not to send. The class is
        // extracted with TYPE_MASK_CLASS, since the full value carries flags and
        // variations.
        //
        // This also protects the check below: outside this class the
        // "multi-line" bit means some other class's bit.
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return true

        // First supporting evidence: accepts newlines, so it is made for written
        // text.
        if (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) return false

        // Second supporting evidence: declares "send". The raw action is checked,
        // not `getImeOptionsActionIdFromEditorInfo`, which maps custom labels to a
        // sentinel: what matters is what the app declared.
        if (editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEND) {
            return false
        }

        // No supporting evidence: row off; the toolbar key remains the way to turn
        // it on where needed.
        return true
    }
}
