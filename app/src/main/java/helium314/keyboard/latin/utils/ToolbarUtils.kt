// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)

package helium314.keyboard.latin.utils

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.content.edit
import androidx.core.view.forEach
import helium314.keyboard.cipher.CipherClipboard
import helium314.keyboard.cipher.CipherSettings
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.EnumMap
import java.util.Locale

fun createToolbarKey(context: Context, key: ToolbarKey): ImageButton {
    val button = ImageButton(context, null, R.attr.suggestionWordStyle)
    button.scaleType = ImageView.ScaleType.CENTER
    button.tag = key
    button.contentDescription = key.name.lowercase().getStringResourceOrName("", context)
    setToolbarButtonActivatedState(button)
    button.setImageDrawable(KeyboardIconsSet.instance.getNewDrawable(key.name, context))
    return button
}

fun setToolbarButtonsActivatedStateOnPrefChange(buttonsGroup: ViewGroup, key: String?) {
    // settings need to be updated when buttons change
    if (key != Settings.PREF_AUTO_CORRECTION
        && key != Settings.PREF_ALWAYS_INCOGNITO_MODE
        && key != GestureDataGatheringSettings.PREF_BACKGROUND_GATHERING_ENABLED
        && key != GestureDataGatheringSettings.PREF_BACKGROUND_DISABLED_BEFORE_TIME_MILLIS
        && key?.startsWith(Settings.PREF_ONE_HANDED_MODE_PREFIX) == false)
        return

    GlobalScope.launch {
        delay(10) // need to wait until SettingsValues are reloaded
        buttonsGroup.forEach { if (it is ImageButton) setToolbarButtonActivatedState(it) }
    }
}

/**
 * keyboard-cipher: recomputes the "active" state of the keys.
 *
 * Keys are built ONCE, and their state stays frozen from then on. Fine for
 * anything depending on preferences, not for "decrypt", which depends on the
 * clipboard: its content can change while the keyboard is alive.
 */
fun refreshToolbarButtonStates(buttonsGroup: ViewGroup) {
    buttonsGroup.forEach { if (it is ImageButton) setToolbarButtonActivatedState(it) }
}

private fun setToolbarButtonActivatedState(button: ImageButton) {
    button.isActivated = when (button.tag) {
        INCOGNITO -> button.context.prefs().getBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, Defaults.PREF_ALWAYS_INCOGNITO_MODE)
        ONE_HANDED -> Settings.getValues().mOneHandedModeEnabled
        SPLIT -> Settings.getValues().mIsSplitKeyboardEnabled
        AUTOCORRECT -> Settings.getValues().mAutoCorrectionEnabledPerUserSettings
        BACKGROUND_GATHERING -> useBackgroundGathering
        // keyboard-cipher: active when the clipboard holds something shaped like
        // one of our blobs. It does not promise it decrypts (it may be truncated
        // or for another recipient), only that it is worth trying.
        //
        // Only with clipboard history on, because that is where the content is
        // read: looking ourselves would mean reading the clipboard at every
        // typing session.
        DECRYPT -> CipherClipboard.clipboardLooksDecryptable()
        // Active while typing inside the keyboard. A switch, not a command: it
        // must show its state without being pressed.
        COMPOSE -> CipherSettings.isComposeMode(button.context.prefs())
        else -> true
    }
}

fun getCodeForToolbarKey(key: ToolbarKey) = Settings.getInstance().getCustomToolbarKeyCode(key) ?: when (key) {
    VOICE -> KeyCode.VOICE_INPUT
    CLIPBOARD -> KeyCode.CLIPBOARD
    NUMPAD -> KeyCode.NUMPAD
    DPAD -> KeyCode.DPAD
    UNDO -> KeyCode.UNDO
    REDO -> KeyCode.REDO
    SETTINGS -> KeyCode.SETTINGS
    SELECT_ALL -> KeyCode.CLIPBOARD_SELECT_ALL
    SELECT_WORD -> KeyCode.CLIPBOARD_SELECT_WORD
    COPY -> KeyCode.CLIPBOARD_COPY
    CUT -> KeyCode.CLIPBOARD_CUT
    PASTE -> KeyCode.CLIPBOARD_PASTE
    ONE_HANDED -> KeyCode.TOGGLE_ONE_HANDED_MODE
    INCOGNITO -> KeyCode.TOGGLE_INCOGNITO_MODE
    AUTOCORRECT -> KeyCode.TOGGLE_AUTOCORRECT
    CLEAR_CLIPBOARD -> KeyCode.CLIPBOARD_CLEAR_HISTORY
    CLOSE_HISTORY -> KeyCode.CLIPBOARD
    EMOJI -> KeyCode.EMOJI
    LEFT -> KeyCode.ARROW_LEFT
    RIGHT -> KeyCode.ARROW_RIGHT
    UP -> KeyCode.ARROW_UP
    DOWN -> KeyCode.ARROW_DOWN
    WORD_LEFT -> KeyCode.WORD_LEFT
    WORD_RIGHT -> KeyCode.WORD_RIGHT
    PAGE_UP -> KeyCode.PAGE_UP
    PAGE_DOWN -> KeyCode.PAGE_DOWN
    FULL_LEFT -> KeyCode.MOVE_START_OF_LINE
    FULL_RIGHT -> KeyCode.MOVE_END_OF_LINE
    PAGE_START -> KeyCode.MOVE_START_OF_PAGE
    PAGE_END -> KeyCode.MOVE_END_OF_PAGE
    SPLIT -> KeyCode.SPLIT_LAYOUT
    FLOATING -> KeyCode.TOGGLE_FLOATING_WINDOW
    BACKGROUND_GATHERING -> KeyCode.BACKGROUND_GATHERING
    ENCRYPT -> KeyCode.CIPHER_ENCRYPT
    DECRYPT -> KeyCode.CIPHER_DECRYPT
    SEND_PLAIN -> KeyCode.CIPHER_SEND_PLAIN
    COMPOSE -> KeyCode.CIPHER_TOGGLE_COMPOSE
    ATTACH -> KeyCode.CIPHER_ATTACH
    GALLERY -> KeyCode.CIPHER_GALLERY
    CONTACTS -> KeyCode.CIPHER_CONTACTS
}

fun getCodeForToolbarKeyLongClick(key: ToolbarKey) = Settings.getInstance().getCustomToolbarLongpressCode(key) ?: when (key) {
    // Long press on "encrypt": inserts our identity card. The first-contact
    // bootstrap; here because writing into the field is native for an IME,
    // while reading the chat history is not.
    ENCRYPT -> KeyCode.CIPHER_IDENTITY_CARD
    // Long press on "contacts": full destruction, behind a confirmation.
    //
    // Not a key of its own: a dedicated key would take strip space for a gesture
    // one hopes never to use, and would be a visible marker (a "destroy
    // everything" button tells anyone looking at the screen that there is
    // something to destroy). Under contacts is where one would look for it.
    CONTACTS -> KeyCode.CIPHER_WIPE
    // Same as the short press: delivering plaintext is already the most
    // irreversible thing this key can do, and hiding a second action under it
    // would make it fire by mistake.
    SEND_PLAIN -> KeyCode.CIPHER_SEND_PLAIN
    // Long press: skip the input field and go straight to the clipboard. For when
    // the field already holds something else (a reply in progress) and the short
    // press would use that text and say "not encrypted".
    DECRYPT -> KeyCode.CIPHER_DECRYPT_CLIPBOARD
    CLIPBOARD -> KeyCode.CLIPBOARD_PASTE
    UNDO -> KeyCode.REDO
    REDO -> KeyCode.UNDO
    SELECT_ALL -> KeyCode.CLIPBOARD_SELECT_WORD
    SELECT_WORD -> KeyCode.CLIPBOARD_SELECT_ALL
    COPY -> KeyCode.CLIPBOARD_CUT
    PASTE -> KeyCode.CLIPBOARD
    LEFT -> KeyCode.KEY_REPEAT
    RIGHT -> KeyCode.KEY_REPEAT
    UP -> KeyCode.KEY_REPEAT
    DOWN -> KeyCode.KEY_REPEAT
    WORD_LEFT -> KeyCode.KEY_REPEAT
    WORD_RIGHT -> KeyCode.KEY_REPEAT
    PAGE_UP -> KeyCode.MOVE_START_OF_PAGE
    PAGE_DOWN -> KeyCode.MOVE_END_OF_PAGE
    BACKGROUND_GATHERING -> KeyCode.BACKGROUND_GATHERING_TEMP_OFF
    else -> KeyCode.UNSPECIFIED
}

// names need to be aligned with resources strings (using lowercase of key.name)
enum class ToolbarKey {
    VOICE, CLIPBOARD, NUMPAD, DPAD, UNDO, REDO, SETTINGS, SELECT_ALL, SELECT_WORD, COPY, CUT, PASTE, ONE_HANDED, FLOATING, SPLIT,
    INCOGNITO, AUTOCORRECT, CLEAR_CLIPBOARD, CLOSE_HISTORY, EMOJI, LEFT, RIGHT, UP, DOWN, WORD_LEFT, WORD_RIGHT,
    PAGE_UP, PAGE_DOWN, FULL_LEFT, FULL_RIGHT, PAGE_START, PAGE_END, BACKGROUND_GATHERING,
    // keyboard-cipher. At the end on purpose: enum order is the order keys appear
    // in the customiser, and inserting in the middle would move HeliBoard's keys
    // for no reason.
    ENCRYPT, DECRYPT, SEND_PLAIN, COMPOSE, ATTACH, CONTACTS, GALLERY
}

enum class ToolbarMode {
    EXPANDABLE, TOOLBAR_KEYS, SUGGESTION_STRIP, HIDDEN,
}

val toolbarKeyStrings = entries.associateWithTo(EnumMap(ToolbarKey::class.java)) { it.toString().lowercase(Locale.US) }

val defaultToolbarPref by lazy {
    // keyboard-cipher: the encryption keys are enabled by default and come first:
    // it is why this fork exists, and leaving them off meant new users found
    // nothing.
    //
    // Note: this applies to NEW installs. Users with a saved toolbar profile get
    // them added by `upgradeToolbarPref`, which adds new entries as disabled, and
    // that is right: those preferences are the user's choices.
    // CLIPBOARD right after the encryption keys, i.e. in the part visible without
    // expanding: otherwise the clipboard history would be past the edge and
    // users would conclude the keyboard has none.
    val default = listOf(COMPOSE, CLIPBOARD, ATTACH, GALLERY, CONTACTS, DECRYPT, ENCRYPT, SEND_PLAIN, SETTINGS, VOICE, UNDO, REDO, SELECT_WORD, COPY, PASTE, LEFT, RIGHT)
    val others = entries.filterNot { it in default || it == CLOSE_HISTORY }
    default.joinToString(Separators.ENTRY) { it.name + Separators.KV + true } + Separators.ENTRY +
            others.joinToString(Separators.ENTRY) { it.name + Separators.KV + false }
}

val defaultPinnedToolbarPref by lazy {
    // keyboard-cipher: these keys are pinned, i.e. visible next to the
    // suggestions without opening the bar. It costs strip space, gladly paid: a
    // feature hidden behind an arrow is a feature nobody finds.
    //
    // CLIPBOARD is pinned with them for the same reason: bar order alone does not
    // decide what shows next to the suggestions.
    // The order is read FROM THE RIGHT, where the thumb is; the strip is drawn
    // from the left, so the list is reversed.
    //
    // COMPOSE stays at the far left: it is the switch that leads out of here, not
    // an action on the message, and sits away from the keys pressed constantly.
    // ENCRYPT and SEND_PLAIN are not here: they are the most used and live INSIDE
    // the compose row, larger and reachable even with emoji open. Having them here
    // too would cost suggestion space twice. In their place are the two the row
    // does not have: "decrypt" and images.
    val pinned = listOf(COMPOSE, CLIPBOARD, ATTACH, GALLERY, CONTACTS, DECRYPT)
    val others = entries.filterNot { it in pinned || it == CLOSE_HISTORY }
    pinned.joinToString(Separators.ENTRY) { it.name + Separators.KV + true } + Separators.ENTRY +
            others.joinToString(Separators.ENTRY) { it.name + Separators.KV + false }
}

val defaultClipboardToolbarPref by lazy {
    val default = listOf(CLEAR_CLIPBOARD, UP, DOWN, LEFT, RIGHT, UNDO, CUT, COPY, PASTE, SELECT_WORD, CLOSE_HISTORY)
    val others = entries.filterNot { it in default }
    default.joinToString(Separators.ENTRY) { it.name + Separators.KV + true } + Separators.ENTRY +
            others.joinToString(Separators.ENTRY) { it.name + Separators.KV + false }
}

/** add missing keys, typically because a new key has been added */
fun upgradeToolbarPrefs(prefs: SharedPreferences) {
    upgradeToolbarPref(prefs, Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref)
    upgradeToolbarPref(prefs, Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)
    upgradeToolbarPref(prefs, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS, defaultClipboardToolbarPref)
}

private fun upgradeToolbarPref(prefs: SharedPreferences, pref: String, default: String) {
    if (!prefs.contains(pref)) return
    val list = prefs.getString(pref, default)!!.split(Separators.ENTRY).toMutableList()
    val splitDefault = defaultToolbarPref.split(Separators.ENTRY)
    splitDefault.forEach { entry ->
        val keyWithSeparator = entry.substringBefore(Separators.KV) + Separators.KV
        if (list.none { it.startsWith(keyWithSeparator) })
            list.add("${keyWithSeparator}false")
    }
    // likely not needed, but better prepare for possibility of key removal
    list.removeAll {
        try {
            ToolbarKey.valueOf(it.substringBefore(Separators.KV))
            false
        } catch (_: IllegalArgumentException) {
            true
        }
    }
    prefs.edit { putString(pref, list.joinToString(Separators.ENTRY)) }
}

fun getEnabledToolbarKeys(prefs: SharedPreferences) =
    withCipherKeys(prefs, Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref)

fun getPinnedToolbarKeys(prefs: SharedPreferences) =
    withCipherKeys(prefs, Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)

/**
 * keyboard-cipher: the encryption keys follow their own settings, not only the
 * toolbar's.
 *
 * Filtered here rather than by switching toolbar preferences off, because those
 * are the user's choices: disabling and re-enabling encryption must give back
 * the bar as it was.
 *
 * **The bar is always built as if encryption were on.** Which keys are VISIBLE
 * is decided at runtime by `CipherCompose.aggiornaTastiCifratura`, which with no
 * row leaves only `COMPOSE` and `DECRYPT`. Filtering here meant the key list was
 * read once when the strip was created, so toggling the row forced a full
 * keyboard rebuild (`hideWindow` plus `showWindow`) on every tap.
 *
 * Without the row the lock keys still disappear: "encrypt" would take what is
 * in the app's field and send it to the recipient **remembered for that app**.
 *
 * Keys are **added** if the saved preference does not name them at all:
 * existing preferences are not overwritten by defaults, so without this users
 * updating would get a compose row without the key to encrypt. A key set to
 * `false` by the user stays `false`.
 */
private fun withCipherKeys(prefs: SharedPreferences, pref: String, default: String): List<ToolbarKey> {
    val result = getEnabledToolbarKeys(prefs, pref, default).toMutableList()
    // Different lists for the two bars: otherwise the automatic addition below
    // would put DECRYPT back among the always-visible keys right after removing it
    // from the defaults.
    val fissati = pref == Settings.PREF_PINNED_TOOLBAR_KEYS
    val wanted = when {
        // Encrypt and send-in-clear are not added to the always-visible keys:
        // they live in the compose row.
        fissati -> listOf(COMPOSE, ATTACH, GALLERY, CONTACTS, DECRYPT)
        else -> listOf(COMPOSE, ATTACH, GALLERY, CONTACTS, DECRYPT, ENCRYPT, SEND_PLAIN)
    }
    // Added if the saved preference does not name them at all: existing
    // preferences are not overwritten by defaults, so users updating would never
    // see new keys. A key set to `false` by the user stays `false`.
    val saved = prefs.getString(pref, default).orEmpty()
    for (key in wanted) {
        if (key in result || saved.contains(key.name)) continue
        val after = result.indexOf(ENCRYPT)
        if (after >= 0) result.add(after.inc(), key) else result.add(key)
    }
    return result
}

fun getEnabledClipboardToolbarKeys(prefs: SharedPreferences) = getEnabledToolbarKeys(prefs, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS, defaultClipboardToolbarPref)

fun addPinnedKey(prefs: SharedPreferences, key: ToolbarKey) {
    // remove the existing version of this key and add the enabled one after the last currently enabled key
    val string = prefs.getString(Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)!!
    val keys = string.split(Separators.ENTRY).toMutableList()
    keys.removeAll { it.startsWith(key.name + Separators.KV) }
    val lastEnabledIndex = keys.indexOfLast { it.endsWith("true") }
    keys.add(lastEnabledIndex + 1, key.name + Separators.KV + "true")
    prefs.edit { putString(Settings.PREF_PINNED_TOOLBAR_KEYS, keys.joinToString(Separators.ENTRY)) }
}

fun removePinnedKey(prefs: SharedPreferences, key: ToolbarKey) {
    // just set it to disabled
    val string = prefs.getString(Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)!!
    val result = string.split(Separators.ENTRY).joinToString(Separators.ENTRY) {
        if (it.startsWith(key.name + Separators.KV))
            key.name + Separators.KV + "false"
        else it
    }
    prefs.edit { putString(Settings.PREF_PINNED_TOOLBAR_KEYS, result) }
}

private fun getEnabledToolbarKeys(prefs: SharedPreferences, pref: String, default: String): List<ToolbarKey> {
    val string = prefs.getString(pref, default)!!
    return string.split(Separators.ENTRY).mapNotNull {
        val split = it.split(Separators.KV)
        if (split.last() == "true") {
            try {
                ToolbarKey.valueOf(split.first())
            } catch (_: IllegalArgumentException) {
                null
            }
        } else null
    }
}

fun writeCustomKeyCodes(prefs: SharedPreferences, codes: EnumMap<ToolbarKey, Pair<Int?, Int?>>) {
    val string = codes.mapNotNull { entry -> entry.value?.let { "${entry.key.name},${it.first},${it.second}" } }.joinToString(";")
    prefs.edit { putString(Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, string) }
}

fun readCustomKeyCodes(prefs: SharedPreferences): EnumMap<ToolbarKey, Pair<Int?, Int?>> {
    val map = EnumMap<ToolbarKey, Pair<Int?, Int?>>(ToolbarKey::class.java)
    prefs.getString(Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, Defaults.PREF_TOOLBAR_CUSTOM_KEY_CODES)!!
        .split(";").forEach {
            runCatching {
                val s = it.split(",")
                map[ToolbarKey.valueOf(s[0])] = s[1].toIntOrNull() to s[2].toIntOrNull()
            }
        }
    return map
}

fun getCustomKeyCode(key: ToolbarKey, prefs: SharedPreferences): Int? {
    if (customToolbarKeyCodes == null)
        customToolbarKeyCodes = readCustomKeyCodes(prefs)
    return customToolbarKeyCodes!![key]?.first
}

fun getCustomLongpressKeyCode(key: ToolbarKey, prefs: SharedPreferences): Int? {
    if (customToolbarKeyCodes == null)
        customToolbarKeyCodes = readCustomKeyCodes(prefs)
    return customToolbarKeyCodes!![key]?.second
}

fun clearCustomToolbarKeyCodes() {
    customToolbarKeyCodes = null
}

fun onClickToolbarKey(view: View, onCodeInput: (Int) -> Unit) {
    AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS)
    val code = getCodeForToolbarKey(view.tag as ToolbarKey)
    if (code != KeyCode.UNSPECIFIED) {
        onCodeInput(code)
    }
}

fun onLongClickToolbarKey(view: View, onCodeInput: (Int, Boolean) -> Unit) {
    AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_LONG_PRESS)
    val longClickCode = getCodeForToolbarKeyLongClick(view.tag as ToolbarKey)
    if (longClickCode == KeyCode.KEY_REPEAT) {
        onClickToolbarKey(view) { onCodeInput(it, false) }
        repeatToolbarKey(view) { onClickToolbarKey(view) { onCodeInput(it, true) } }
    } else if (longClickCode != KeyCode.UNSPECIFIED) {
        onCodeInput(longClickCode, false)
    }
}

private fun repeatToolbarKey(view: View, onClick: (view: View) -> Unit) {
    view.handler.postDelayed({
        if (view.isPressed) {
            onClick(view)
            repeatToolbarKey(view, onClick)
        }
    }, view.resources.getInteger(R.integer.config_key_repeat_interval).toLong())
}

private var customToolbarKeyCodes: EnumMap<ToolbarKey, Pair<Int?, Int?>>? = null
