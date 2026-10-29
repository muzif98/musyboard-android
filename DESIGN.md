# Design decisions (Android app)

MusyBoard is a fork of [HeliBoard](https://github.com/HeliBorg/HeliBoard). The
keyboard itself (layouts, correction, gestures, themes, dictionaries) is
HeliBoard's work; this document covers only what the fork adds and why.

The cryptography, the message format and their rationale live in the core
repository: [musyboard-core](https://github.com/muzif98/musyboard-core) and its
[DESIGN.md](https://github.com/muzif98/musyboard-core/blob/main/DESIGN.md).
Decision letters (C, G, H, I, J, K, ...) refer to that document.

## Contents

- [Integration with HeliBoard](#integration-with-heliboard)
- [The compose row](#the-compose-row)
- [Which fields get the row](#which-fields-get-the-row)
- [Delivering and sending](#delivering-and-sending)
- [Long messages](#long-messages)
- [How received messages are opened](#how-received-messages-are-opened)
- [The current recipient](#the-current-recipient)
- [Keys at rest](#keys-at-rest)
- [Identity backup](#identity-backup)
- [Plaintext handling](#plaintext-handling)
- [Attachments](#attachments)
- [Groups in the UI](#groups-in-the-ui)
- [Contacts, names and key changes](#contacts-names-and-key-changes)
- [Permissions](#permissions)
- [Keeping the keyboard alive](#keeping-the-keyboard-alive)
- [Settings](#settings)
- [Native library build](#native-library-build)
- [Release signing and application id](#release-signing-and-application-id)
- [Rules learned the hard way](#rules-learned-the-hard-way)

## Integration with HeliBoard

All fork code lives in the `helium314.keyboard.cipher` package, plus a few
resources with their own files (`values/cipher.xml`, `cipher_*` layouts and
drawables). Changes to HeliBoard's own files are small, additive and marked with
a `keyboard-cipher:` comment, so merges from upstream stay painless: pure
additions only conflict if upstream edits the exact same lines.

The only places HeliBoard "knows" about encryption:

- `KeyboardActionListenerImpl`: routes the encryption key codes to
  `CipherActions`;
- `LatinIME`: redirects the input connection to the compose row
  (`getCurrentInputConnection`), counts the row in `onComputeInsets`, and
  forwards selection and window events;
- `ClipboardHistoryManager`: lets the fork look at clipboard text HeliBoard has
  already read for its history;
- `ToolbarUtils` / `KeyCode` / `SuggestionStripView`: the toolbar keys.

Key codes are numbered far from HeliBoard's block (from -30000), so a new
upstream key can never silently take one of ours.

The Rust core is a git submodule (`core/`) pinned to an exact commit, so a given
app tag always builds the same cryptographic code. The JNI library is built from
source by Gradle with `cargo-ndk`; no prebuilt binaries are committed.

## The compose row

**The problem.** Typing plaintext into the chat app's field and encrypting it
afterwards still hands the plaintext to the chat app: it is the app's own
`EditText`, it receives every keystroke, it can save drafts to its server and
announce "typing". The project exists not to do that.

**The solution.** With the compose row active, text is typed into a row inside
the keyboard, and the app's field stays **empty** until the lock key delivers
the encrypted blob.

**How.** There is no second typing engine. HeliBoard writes wherever
`getCurrentInputConnection` says, so in compose mode that returns a
`BaseInputConnection` on a buffer of ours, and correction, suggestions, deletion,
cursor and gestures keep working unchanged. `getAppInputConnection()` returns
the real connection, used only for what must reach the app: the blob, the
identity card, and reading the field to decrypt.

Details that matter:

- **The enter key does nothing** inside the row: forwarding it would tell the
  app to send while the plaintext is not encrypted.
- **The buffer belongs to an app.** The recipient is per app, so text started in
  one app is cleared when focus moves to a *different* app (not when focus is
  lost: going to read a message and coming back must not discard the reply).
  Our own screens and non-text fields are ignored for this purpose.
- **A draft in a new field of the same app** may be for another chat: the user is
  asked whether to use it there (and confirm the recipient) or delete it, and the
  lock key does nothing until they choose.
- **The row has a fixed height** (56dp, 58dp in landscape) and the text scrolls.
  Growing with the text made the keyboard grow, and chat apps do not always
  relayout in time. The row height is subtracted in `onComputeInsets`, otherwise
  the app is drawn underneath it.
- **The cursor is drawn by hand.** An IME window never takes focus (it would steal
  it from the app's field), and a `TextView` only draws its caret when focused.
  Touch gestures (tap, drag to scroll, long press / double tap to select, handles)
  are implemented for the same reason, and selection changes are reported to
  HeliBoard, otherwise delete would ignore the selection.
- **Text already in the app's field moves into the row** on the first key press
  (a restored draft, or text sent in clear with a typo). It is adopted only if
  the field really emptied, so plaintext is never in two places. Enter,
  encryption keys and password fields never adopt.
- **Copy and cut are blocked** in the row by default: the clipboard is readable
  by the app in focus and ends up in the keyboard's clipboard history on disk.
  Paste into the row is allowed. The block can be turned off in settings.
- **Words typed in the row are not learned** by the personal dictionary by
  default (it is included in Android backups and resurfaces as suggestions in
  other apps), not donated to gesture data gathering, and debug logging is
  forced off while encryption is on.
- **Voice input is refused** while the row is on screen: the voice keyboard is a
  different IME that writes straight into the app's field, in clear.

## Which fields get the row

The row turns on by itself only on **positive evidence** that a field composes
messages:

- the field accepts newlines (`TYPE_TEXT_FLAG_MULTI_LINE` inside
  `TYPE_CLASS_TEXT`): every major chat composer is multi-line, while search bars,
  codes and form boxes are single-line;
- or the field declares the **send** action.

An earlier list of negative evidence (search, go, filter, autocomplete...) kept
growing and still missed search bars that declare nothing. A false negative
costs one tap on the toolbar key that forces the row on that field; a false
positive steals the text of a search bar. Password fields can never get the row.

## Delivering and sending

The lock key encrypts, puts the blob in the app's field and, with **auto-send**
on (compose mode only), asks the app to send it. A keyboard cannot press an
app's send button: it can only trigger the field's declared editor action, or
fall back to a simulated enter key, which some apps treat as send and others as
a newline (removed again afterwards). A trailing newline does not break the blob,
because recognition tolerates surrounding text.

Delivery is verified by **reading the field back**, never by trusting the return
value of `commitText`: the row is cleared only once the blob really arrived. If
the field ends up different from what it should be, it is restored exactly as it
was, so a failure never leaves a duplicate.

"Send in clear" (paper-plane key) delivers the row's text unencrypted, for people
who do not have this keyboard. After an encryption or a read, the first
plaintext send asks for confirmation.

## Long messages

Telegram stops at 4096 characters, and a blob split by the chat app no longer
decrypts. The blob length is **estimated before encrypting** (z-base-32 is
deterministic), erring on the long side, including group slots. Above the limit
the text is split into parts marked "(1/3)", each encrypted separately: no new
format, nothing to change in the core. The marker is in the plaintext because it
is the only place the reader sees it.

Everything is encrypted first and the field is touched afterwards, so a failure
never sends half a message. Parts after the first are queued (already encrypted)
and delivered one by one when the field empties after a send. The queue is bound
to the exact field (package, view id, input type) and persisted, encrypted with
the Keystore key, so it survives the process being killed.

Fields that silently truncate (some SMS apps stop at 2000 characters) are
detected after delivery: the truncated blob is removed, the limit is remembered
for that app, and the message is split.

## How received messages are opened

A keyboard sees the input field, never the chat history. Four paths deliver a
blob, all converging on the same code:

1. **Clipboard.** `ClipboardHistoryManager` already reads new clipboard content
   for its history; the fork only checks whether it looks like one of our blobs.
   There is no second clipboard read and no system toast. With clipboard history
   disabled this path does not fire, by design.
2. **"Decrypt" in the text selection toolbar** (`ACTION_PROCESS_TEXT`), in apps
   that use the standard toolbar. Works even if the keyboard process is not
   running.
3. **Share sheet** (`ACTION_SEND`), and opening `.kc` files.
4. **The "decrypt" key**, which reads the compose row, then the field, then the
   clipboard.

A decrypted text message is shown in a **panel inside the keyboard window**.
Starting an activity from the background is refused silently on some ROMs, while
the keyboard window is ours. Everything else (attachments, identity cards,
burns, errors) goes to `DecryptActivity`, so each outcome has one
implementation. If the window cannot be shown, a notification without any content
is the last resort.

Decrypting is **not read-only** (it pins new senders, advances the forward
secrecy chain, applies burns), so every entry point is made idempotent, a
recreated activity never reprocesses a blob, and the keyring is persisted right
after.

## The current recipient

A keyboard does not know which conversation it is in: `EditorInfo` gives the
package, not the chat. The recipient is therefore remembered **per app**:

1. decrypting a message sets its sender as recipient for that app;
2. otherwise the last choice for that app is used;
3. otherwise the user chooses explicitly (tap the name under the row).

The choice is persisted (`CipherRecipients`, encrypted), otherwise every service
restart would silently forget it. Reading a group message selects **the group**,
never the individual sender, so a reply does not go privately to one member.

The originating app of a decrypt request is never taken from intent extras or
`getReferrer()`, both of which any app can forge. The keyboard issues a random
single-use token (`CipherHandoff`) when it launches our activities; without a
valid token no recipient is changed.

## Keys at rest

The core does no I/O. The identity secret, the keyring and the other state files
are encrypted with an **AES-256-GCM master key in Android Keystore**, with a
distinct AAD domain per file, and written atomically (temporary file, fsync,
rename, directory fsync) under `noBackupFilesDir`, which keeps them out of cloud
backups (the master key could not be restored anyway).

- `setUnlockedDeviceRequired(true)` (API 28+) when the device has a screen lock;
  on devices without one, key generation with that flag fails, so it falls back
  to a TEE key without it (nothing is lost: such a device is never locked).
  StrongBox is tried first.
- No per-use user authentication: a biometric prompt while typing would get the
  feature turned off.
- **Never regenerate silently.** A file that does not decrypt yields
  `Unreadable`, and a locked device yields `Locked`, never "corrupted": offering
  to reset the identity for a transient condition would invite the user to
  destroy it. Replacing the identity is always an explicit user decision, since
  every contact would see a key change.
- Encryption requires API 23 (AES in AndroidKeyStore). Below that it reports
  itself unavailable and touches nothing.

## Identity backup

Losing the app data must not mean losing the identity (every contact would see a
key change, the impersonation signal). The backup is a file encrypted with a
passphrase using Argon2id (see the core). The passphrase is asked **before** the
file location (asked afterwards it tends to be weak) and typed twice. Files are
written through the Storage Access Framework, so no storage permission is
needed. Importing replaces the current identity, and the screen shown afterwards
displays the adopted fingerprint and how many contacts were already marked
verified, because a backup does not prove where it came from.

## Plaintext handling

- `FLAG_SECURE` on every window that shows plaintext, set before any content:
  decryption and contact screens, and the keyboard window while the compose row
  or the reading panel is on screen. A single arbiter (`CipherSchermoProtetto`)
  recomputes the flag, since the window is shared. A setting allows turning it
  off, with the cost spelled out.
- Activities showing plaintext are excluded from Recents, use their own task, are
  `singleTask`, handle configuration changes themselves (recreation would
  reprocess the blob or need plaintext in a Bundle), and close themselves in
  `onStop`. `noHistory` is deliberately not used: it also closed the window while
  the system file picker was in front, producing empty saved files.
- **`ACTION_PROCESS_TEXT` never returns text to the caller.** Returning the
  plaintext would hand it to the chat app the selection came from.
- Secrets cross JNI as `byte[]` and are zeroized after use; the compose buffer is
  overwritten before being cleared. Strings shown in the UI cannot be zeroized:
  the guarantee stops there.
- Copying plaintext on explicit request excludes it from the keyboard's clipboard
  history (a digest is remembered, never the text).

## Attachments

An IME cannot attach files, and images inserted through `commitContent` get
recompressed. Attachments are therefore encrypted from a screen and handed to
the chat through the share sheet as documents (decision G). The recipient is
always chosen explicitly. Received files stay in memory and in a `FLAG_SECURE`
window until the user saves them through the system picker; their names come
from the sender and are sanitised. The outgoing share folder keeps only the last
file. The size cap is the smaller of 50 MB and a quarter of the heap, checked
before encrypting.

## Groups in the UI

Groups are local labels over a set of keys (decision K5), stored encrypted.
Group messages show **no author** (decision K6) and say that they have no forward
secrecy (decision K1). A received group message is matched against saved groups
by sender and size; if the match is not unique nothing is chosen.

## Contacts, names and key changes

- An unseen identity card opens the contacts screen directly on the "give a name"
  dialog: without a name, "this contact's key changed" cannot be expressed.
- Assigning a name already used by another key shows both fingerprints and both
  readings (reinstall or impersonation) without choosing; the default changes
  nothing, and the replacing action sits on the button away from the thumb.
- Forgetting a contact keeps a **tombstone** (fingerprint and name, encrypted):
  giving the same name to a different key later raises the same warning a key
  change would. Burning does not leave tombstones.
- The QR code is shown, never scanned (no camera permission): one side scanning
  with any reader is enough.
- A long press on the contacts key opens the full-destruction confirmation
  (identity, keyring, names, groups, tombstones, statistics, share folder,
  clipboard). It is a visible gesture, meant for "about to hand over the phone".

## Permissions

The app has **no `INTERNET` and no `CAMERA` permission**. Compared with
HeliBoard it adds only what the optional keep-alive service needs
(`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`),
and removes `READ_CONTACTS` (a declared permission is visible in the store
listing even if never granted).

The build **fails if the merged manifest contains any permission outside a fixed
list**: a dependency update could otherwise add `INTERNET` without anyone writing
a line.

## Keeping the keyboard alive

Clipboard recognition runs in the keyboard process, and Android has no clipboard
event that can wake a stopped app. Vendor battery managers force-stop the
keyboard (and the system then switches to another one). The app detects that it
lost the default keyboard slot, shows the system's recorded exit reasons, and
offers the battery exemption screen. A foreground service that does nothing but
exist is available as an opt-in, because it costs a permanent notification.

## Settings

All encryption settings are under **Settings → Encryption**, stored in their own
preferences (`CipherSettings`) rather than in HeliBoard's files. Options other
than the main switch are shown only when encryption is on. Turning encryption off
makes the app behave like HeliBoard, without touching the user's toolbar
preferences; decryption keeps working, since the switch means "do not write
encrypted", not "discard what I receive".

## Native library build

`buildCipherCore` runs `cargo ndk` for four ABIs before every build. Source
paths are remapped (`--remap-path-prefix`), because otherwise the `.so` embeds
the build machine's absolute paths in panic location strings, which survive
stripping debug info. With the remapping, two clean builds produce
byte-identical libraries. (The APK as a whole is not byte-identical across
builds because of R8; the non-minified variant is.)

`strip = "debuginfo"`, not `"symbols"`: the JNI entry points are resolved by name.
ProGuard keeps `CipherCore` and `IncomingResult` for the same reason, since the
Rust side writes `IncomingResult` fields by name.

If the core source is missing, the build fails instead of silently packaging old
libraries left in `jniLibs`.

## Release signing and application id

The application id is `io.github.muzif98.musyboard`, distinct from HeliBoard's,
so both can be installed side by side. Release signing reads
`keystore.properties` (ignored by git) or `KC_*` environment variables; without
them the release APK is built unsigned rather than failing, so anyone can build
from a clone.

Changing the signing key or the application id makes Android treat the app as a
new one: the identity must be moved with a backup export and import before
uninstalling the old version.

## Rules learned the hard way

- **A green build does not prove the feature is there.** Components declared with
  a relative name outside the module namespace resolve to classes that do not
  exist, and R8 removes the real ones without complaint. Fork components use
  absolute names in the manifest, and verification looks at the dex and `lib/`.
- **Verify on a device, through the real keyboard.** `adb shell input text`
  bypasses the IME entirely, and UI dumps do not see the IME window.
- **A comment is a secondary source.** The most serious bug found in review was a
  comment claiming a blob held only public keys, which led to private keys being
  copied into the UI layer.
- **Bugs live between the pieces.** Several of the worst problems (a key that did
  nothing outside chats, forward secrecy and "burn" contradicting each other)
  were invisible in any single file and only showed up in use.
- **A measurement that does not isolate is not a measurement.** Storage location
  was verified on a clean install, not on an emulator with leftovers.
- **No pull requests or issues are sent upstream.** This fork is independent and
  not endorsed by the HeliBoard developers; problems in it must not be reported
  to them.
