# MusyBoard

**An Android keyboard that encrypts your messages before the chat app sees
them.** A fork of [HeliBoard](https://github.com/HeliBorg/HeliBoard).

You type inside the keyboard; when you press the lock, the chat app's text field
receives only the encrypted message. The chat app, its servers, cloud backups and
any platform-side content scanning only ever see ciphertext. Like HeliBoard, the
app has **no internet permission**.

> **The keyboard is HeliBoard's work.** Layouts, correction, gesture typing,
> themes, dictionaries, emoji and clipboard history come from HeliBoard
> unchanged; MusyBoard adds the encryption on top. It is an independent fork,
> **not endorsed by the HeliBoard developers**: please do not report problems
> with this version to them. HeliBoard's original README is kept in
> [README-HeliBoard.md](README-HeliBoard.md).

> **Status: experimental.** The cryptography has not had an independent audit,
> and the message format may still change. If a mistake would have serious
> consequences for you, use Signal.

The cryptographic core lives in a separate repository,
[musyboard-core](https://github.com/muzif98/musyboard-core) (Rust), included here
as a submodule. An iPhone companion,
[musyboard-ios](https://github.com/muzif98/musyboard-ios), runs the same core
inside Scriptable.

## What it protects against, and what it does not

It is designed against one adversary: **automated, indiscriminate analysis of
everyone's messages with bulk retention**, the model discussed under the name
"Chat Control". It is not designed against someone targeting you specifically.

**Protects:** message content from whoever reads the chat (platform, servers,
backups, automated scanning), and the integrity of messages (tampering is
detected).

**Does not protect, by explicit choice:**

- a compromised phone (keyloggers, root, screen capture, accessibility
  services): the text can be read before it is encrypted;
- metadata: who talks to whom and when stays visible to the platform;
- the fact that you are encrypting: messages are recognisable;
- the reposting of an old message (shown with its original composition date, not
  prevented);
- past messages after a key compromise, **unless forward secrecy is on** (the
  default): then long-term keys alone open nothing from the second message on.

The full threat model is in the core repository.

## How it works

- Everyone has **one identity**, valid for all contacts.
- **First contact:** a long press on the lock inserts your identity card into the
  chat; the other person decrypts it and their keyboard remembers your key. For
  the highest assurance, show your QR code in person.
- **Writing:** with encryption on, you type in a **row inside the keyboard**. The
  app's field stays empty until you press the lock, so the app cannot save drafts
  of your plaintext or announce that you are typing. The recipient's name is
  shown under the row.
- **Reading:** copy the message (it opens by itself), or select it and choose
  "Decrypt", or share it to MusyBoard. The decrypted text appears in a window
  that blocks screenshots and **never goes back to the chat app**.
- **Recipients** are remembered per app: decrypting a message makes its sender
  the recipient of your reply.
- **Key changes** are never accepted silently: both fingerprints are shown side
  by side and nothing changes until you confirm.

Features:

- forward secrecy (on by default): a message opens once and cannot be reopened
  later, not even by you;
- or, with forward secrecy off, a rereadable conversation that can be **burned**
  for both sides;
- encrypted photos, videos and files, sent as documents;
- group messages, with clear warnings about what groups cannot guarantee;
- long messages split automatically into parts;
- passphrase-encrypted backup of your identity;
- screenshots of decrypted text blocked by default.

Cryptography: X25519, XChaCha20-Poly1305, HKDF-SHA-256, Argon2id for backups.
The rationale for the Android side is in [DESIGN.md](DESIGN.md); the protocol
and its design are in the core repository.

## Permissions

No `INTERNET` and no `CAMERA` permission (QR codes are shown, not scanned: one
side scanning with any QR reader is enough). The only permissions added to
HeliBoard's are those of the optional service that keeps the keyboard alive
(`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, notifications). None of
them lets data leave the phone. The build fails if any other permission appears
in the merged manifest.

## Installation

APKs are on the [releases](../../releases) page. MusyBoard installs next to
HeliBoard, with its own package name.

1. Install the APK.
2. Settings → System → Languages & input → On-screen keyboard: enable MusyBoard
   and select it.
3. Exchange identity cards with your contacts (long press on the lock).

Encryption needs **Android 6.0** or later; below that the keyboard works normally
and reports encryption as unavailable. Keep a second keyboard installed: if this
one has a problem, you still need a way to type.

All encryption settings are under keyboard settings → **Encryption**.

### Keys

| Gesture | Action |
|---|---|
| lock | encrypt the row for the current recipient |
| long press on lock | insert your identity card (first contact) |
| open lock | decrypt the row, the field, or the clipboard |
| long press on open lock | decrypt the clipboard directly |
| paper plane | send the row's text in clear, for people without this keyboard |
| contacts | names, fingerprints, QR code, backup, burn |
| long press on contacts | destroy everything, after confirmation |
| picture / paperclip | send an encrypted file: choose the person, then the file |

The most reliable way to open a received message is **selecting it and choosing
"Decrypt"** in apps that show the standard selection toolbar: it works even if
the phone has stopped the keyboard to save battery. Opening on copy depends on
the keyboard process being alive; if your phone stops it often, remove the
battery restriction for the app.

## Building

Requirements: JDK 21, Android SDK, NDK 28.0.13004108, Rust (stable) with the
Android targets, and `cargo-ndk`.

```sh
git clone --recurse-submodules https://github.com/muzif98/musyboard-android.git
cd musyboard-android
./gradlew :app:assembleDebug                       # builds the Rust library, then the APK
./gradlew :app:testRunTestsUnitTest -PsenzaRete    # JVM tests (without network-dependent tests)
./gradlew :app:connectedDebugNoMinifyAndroidTest   # instrumented tests, on an emulator
```

The core is taken from the `core/` submodule, or from a sibling
`../musyboard-core` checkout, or from `-PcipherCorePath=/path`. Release builds are
signed if `keystore.properties` (or the `KC_*` environment variables) are present,
and unsigned otherwise. An F-Droid recipe is in [fdroid/](fdroid/).

## License

GPL-3.0, like HeliBoard, from which this fork descends and which holds the
copyright on everything that is not the encryption part. See [LICENSE](LICENSE)
and HeliBoard's additional license files.
