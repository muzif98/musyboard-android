package helium314.keyboard.cipher

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.latin.utils.prefs

/**
 * The encryption preferences.
 *
 * Here rather than in HeliBoard's `Settings`/`Defaults` because every line added
 * to an upstream file is a merge conflict waiting to happen, and these describe
 * a feature upstream does not have.
 */
object CipherSettings {

    /**
     * Main switch. Off, the fork behaves like HeliBoard: no toolbar keys, no
     * compose row, no reachable encryption path.
     *
     * On by default: it is why this keyboard exists, and shipping it with its
     * feature disabled would mean users find nothing.
     */
    const val PREF_ENABLED = "cipher_enabled"
    const val DEFAULT_ENABLED = true

    const val DEFAULT_COMPOSE_MODE = false

    /**
     * After delivering the message to the app, also ask it to send it.
     *
     * Applies **only** with the compose row active: there the two keys already
     * mean "send this message", because the text was never in the app's field.
     * Without the row the lock key replaces what you are typing, and sending it
     * automatically would be something else, irreversible and unrequested.
     *
     * On by default in that mode: the alternative is pressing twice to do one
     * thing. Turned off in Settings → Encryption.
     */
    const val PREF_AUTO_SEND = "cipher_auto_send"
    const val DEFAULT_AUTO_SEND = true

    fun isAutoSend(prefs: SharedPreferences): Boolean =
        isComposeMode(prefs) && prefs.getBoolean(PREF_AUTO_SEND, DEFAULT_AUTO_SEND)

    fun isAutoSend(context: Context): Boolean = isAutoSend(context.prefs())

    /**
     * Open a just-copied message automatically.
     *
     * Copying is the **only** automatic hook a keyboard has: it does not see the
     * chat, and reading the clipboard on its own initiative at every typing
     * session would make it an app that snoops on the clipboard. Here the text
     * has already been read for the clipboard history, so the check is free.
     *
     * *Declared consequence:* **with clipboard history disabled this does not
     * fire.** The price of not reading behind the user's back.
     */
    const val PREF_AUTO_OPEN = "cipher_auto_open"
    const val DEFAULT_AUTO_OPEN = true

    /**
     * **Does not depend on [isEnabled]**, the only encryption preference that
     * does not.
     *
     * The main switch says "I don't want to write encrypted". It does not say "I
     * don't want to READ what arrives encrypted": someone receiving a message
     * with encryption off would otherwise find an unreadable blob and no hint,
     * while holding the key to read it.
     */
    fun isAutoOpen(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(PREF_AUTO_OPEN, DEFAULT_AUTO_OPEN)

    fun isAutoOpen(context: Context): Boolean = isAutoOpen(context.prefs())

    /**
     * Forward secrecy: the key a message was encrypted with stops existing once
     * the message has been read.
     *
     * Each message uses a single-use sender key and, once the chain has started,
     * a temporary key of the recipient carried inside the previous message. **So
     * whoever later obtains either side's long-term key cannot reopen messages
     * already exchanged.** The sender's long-term key still takes part in the
     * derivation, which proves to the recipient who wrote, without signatures.
     *
     * The sender's key no longer appears in clear, so two messages from the
     * same person cannot be linked by looking at traffic.
     *
     * **On by default**, with two consequences to know: the recipient needs this
     * version or newer, and the sender must already be among their contacts
     * (without the key in clear, the recipient recognises the sender by trying
     * their contacts). Exchanging identity cards, already the first step, is now
     * required. The price: messages cannot be reread later.
     */
    const val PREF_FORWARD_SECRECY = "cipher_forward_secrecy"
    const val DEFAULT_FORWARD_SECRECY = true

    fun isForwardSecrecy(prefs: SharedPreferences): Boolean =
        isEnabled(prefs) && prefs.getBoolean(PREF_FORWARD_SECRECY, DEFAULT_FORWARD_SECRECY)

    fun isForwardSecrecy(context: Context): Boolean = isForwardSecrecy(context.prefs())

    /**
     * Copy and cut from the compose row.
     *
     * **Blocked by default.** On the clipboard the plaintext is readable by the
     * app in focus (the chat app the row exists to keep it from) and also lands
     * in the keyboard's clipboard history, on disk. Copying undoes in one tap
     * what the row does.
     *
     * The switch exists because the ban has a real cost: moving text between two
     * fields, or reusing a half-written sentence, becomes impossible.
     *
     * Applies **only** inside the compose row: without it the plaintext is
     * already in the app's field and there is nothing to protect.
     */
    /**
     * Learn the words typed in the encrypted row. **Off by default.**
     *
     * The rest of the keyboard does not know that text will be encrypted: it
     * learns it into the personal dictionary like any other. The dictionary
     * lives in `filesDir`, which the manifest leaves inside Android's automatic
     * backup, so plaintext would leave the device **without** the keyboard having
     * network permission (the system copies it). It would then come back as a
     * suggestion while typing in clear in another app.
     *
     * Worse: the personal dictionary learns exactly the words it does NOT already
     * know (names, surnames, places), i.e. the content that identifies people.
     *
     * The switch exists because the ban has a real cost: the keyboard does not
     * improve on the vocabulary of those who write encrypted often. Smaller than
     * it seems: suggestions in the row keep working, the dictionary is still
     * read, only writing to it stops.
     *
     * HeliBoard's own personal-dictionary switch is all or nothing and disables
     * learning everywhere; this is finer.
     *
     * Off by default because the promise must hold for those who never open
     * settings.
     */
    const val PREF_LEARN = "cipher_learn"
    const val DEFAULT_LEARN = false

    fun isLearn(prefs: SharedPreferences): Boolean =
        isComposeMode(prefs) && prefs.getBoolean(PREF_LEARN, DEFAULT_LEARN)

    fun isLearn(context: Context): Boolean = isLearn(context.prefs())

    /**
     * Blocks screenshots, screen recording and the Recents thumbnail on
     * everything that shows plaintext: the decryption screen, the compose row,
     * the panel.
     *
     * On by default. It can be turned off because sometimes a screenshot is
     * really needed (reporting a bug, showing how it works).
     *
     * **What is lost by turning it off**, beyond "screenshots are possible": the
     * system saves **to disk** a thumbnail of the screen with the decrypted text
     * for the Recents list, and any app with screen recording or projection sees
     * the plaintext.
     *
     * The other defences do not depend on this switch: `noHistory`,
     * `excludeFromRecents`, closing in `onStop`, zeroizing arrays.
     */
    const val PREF_BLOCK_SCREENSHOT = "cipher_block_screenshot"
    const val DEFAULT_BLOCK_SCREENSHOT = true

    fun isBlockScreenshot(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(PREF_BLOCK_SCREENSHOT, DEFAULT_BLOCK_SCREENSHOT)

    fun isBlockScreenshot(context: Context): Boolean = isBlockScreenshot(context.prefs())

    const val PREF_BLOCK_COPY = "cipher_block_copy"
    const val DEFAULT_BLOCK_COPY = true

    fun isBlockCopy(prefs: SharedPreferences): Boolean =
        isComposeMode(prefs) && prefs.getBoolean(PREF_BLOCK_COPY, DEFAULT_BLOCK_COPY)

    fun isBlockCopy(context: Context): Boolean = isBlockCopy(context.prefs())

    /**
     * Keep the keyboard process alive with a foreground service.
     *
     * ## The problem it solves
     *
     * Recognition of a blob on the clipboard lives in `ClipboardHistoryManager`,
     * i.e. in the keyboard process: if that process is gone, copying does
     * nothing. And **there is no way to notice**: Android has no clipboard event
     * that can wake a stopped app.
     *
     * The usual culprit is the vendor battery manager, which force-stops the
     * package. A foreground service is the one thing those managers usually
     * respect, because it has a visible notification.
     *
     * ## Why it is OFF by default
     *
     * It costs a permanent status bar notification and the notification
     * permission. The first thing to try is removing the app's battery
     * restriction: same effect, no clutter. See the entry that opens that screen.
     */
    const val PREF_KEEP_ALIVE = "cipher_keep_alive"
    const val DEFAULT_KEEP_ALIVE = false

    fun isKeepAlive(prefs: SharedPreferences): Boolean =
        isEnabled(prefs) && prefs.getBoolean(PREF_KEEP_ALIVE, DEFAULT_KEEP_ALIVE)

    fun isKeepAlive(context: Context): Boolean = isKeepAlive(context.prefs())

    fun isEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(PREF_ENABLED, DEFAULT_ENABLED)

    fun isEnabled(context: Context): Boolean = isEnabled(context.prefs())

    /**
     * Same as [isEnabled], on purpose.
     *
     * There used to be two switches that meant the same thing: with the row off
     * the toolbar loses locks, contacts and attachments, so "encryption on, row
     * off" was a plain keyboard, exactly like "encryption off". The preference is
     * still read and written by existing callers, but has no settings entry of
     * its own.
     */
    fun isComposeMode(prefs: SharedPreferences): Boolean = isEnabled(prefs)

    fun isComposeMode(context: Context): Boolean = isComposeMode(context.prefs())
}
