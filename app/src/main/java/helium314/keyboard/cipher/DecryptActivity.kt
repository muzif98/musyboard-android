package helium314.keyboard.cipher

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import java.util.Date

/**
 * Receives text from `ACTION_PROCESS_TEXT` (path 2), from the share sheet (path
 * 3) and from the toolbar "decrypt" key (path 4), decrypts it and shows the
 * plaintext.
 *
 * All paths converge here on purpose: the six outcomes of
 * `nativeHandleIncomingText` must be handled the same way however the blob
 * arrived, and two implementations would diverge at the first new outcome.
 */
class DecryptActivity : ComponentActivity() {

    companion object {
        /** Request code of the "where to save" picker. */
        private const val RICHIESTA_SALVA = 1

    }

    /**
     * The package of the app the text comes from, resolved ONCE per intent.
     *
     * Once, not on demand, because the [CipherHandoff] token is single-use: the
     * first call consumes it, and every later call would find "no
     * attribution".
     */
    private var appDiProvenienza: String = ""

    /** Decrypted content, waiting for the user to decide whether to save it. */
    private var fileInAttesa: ByteArray? = null
    private var nomeInAttesa: String? = null

    /**
     * True while the "where to save" picker is in front of us.
     *
     * Prevents closing at that moment. See [onStop]: this window closes as soon
     * as it leaves the screen, and the picker makes it leave: the system created
     * the file and nobody wrote into it (a zero-byte attachment marked "saved").
     */
    private var inAttesaDelSelettore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before anything that can reach the screen. Blocks screenshots, screen
        // recording, and the thumbnail the system saves for Recents.
        //
        // The switch is in the encryption settings, one for the whole app. On by
        // default; the cost of turning it off is spelled out next to the
        // preference.
        if (CipherSettings.isBlockScreenshot(this)) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        super.onCreate(savedInstanceState)
        // `savedInstanceState != null` means a rebirth: the system destroyed us
        // and recreates us with the same intent. It is not reprocessed:
        // decrypting is not read-only (it advances the chain, pins the key, and
        // on a burn request burns), so a second pass is a second effect.
        //
        // Rotation does not get here (handled by `configChanges` in the
        // manifest). What remains are cases where Android kills us anyway, and
        // there the content cannot be rebuilt: plaintext is never saved in a
        // Bundle, which is system memory. So the user is told to reopen.
        if (savedInstanceState != null) {
            showNotice(R.string.cipher_reopen_needed)
            return
        }
        handle(intent)
    }

    /**
     * With `launchMode=singleTask` a second intent to a live instance does NOT
     * go through [onCreate]. Without this override the second decryption would
     * be ignored and the previous plaintext would stay on screen, with the user
     * believing they are reading the new message.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handle(intent)
        }
    }

    private fun handle(intent: Intent) {
        // We are here: whoever launched us must not fall back to the
        // notification. If we came from the notification, it is done; the blob
        // its PendingIntent held in `system_server` goes too.
        CipherNotification.dismiss(this)
        appDiProvenienza = resolveCallerPackage()

        // An attachment arrives as a stream, not text. Checked first: if there is
        // a stream, that is the content.
        val stream = extractStream(intent)
        if (stream != null) {
            if (stream.scheme != ContentResolver.SCHEME_CONTENT) {
                return showNotice(R.string.cipher_uri_rejected)
            }
            handleFile(stream)
            return
        }

        val incoming = extractText(intent)
        if (incoming == null) {
            finish()
            return
        }

        when (val state = CipherIdentity.ensureReady(this)) {
            CipherState.Ready -> Unit
            CipherState.Locked -> return showNotice(R.string.cipher_locked)
            is CipherState.Unreadable -> return showUnreadable(state.part)
            is CipherState.Unavailable -> return showNotice(R.string.cipher_unavailable)
        }

        val result = CipherCore.IncomingResult()
        val code = CipherCore.nativeHandleIncomingText(
            appDiProvenienza,
            incoming.toString(),
            System.currentTimeMillis() / 1000,
            result,
        )

        when (code) {
            // A NORMAL outcome, not an error: most text that arrives here is not
            // ours, which is what the sentinel is for.
            CipherCore.NOT_OUR_BLOB -> return showNotice(R.string.cipher_not_our_blob)
            CipherCore.UNSUPPORTED_VERSION -> return showNotice(R.string.cipher_version_too_new)
            CipherCore.TIER_UNSUPPORTED -> return showNotice(R.string.cipher_tier_unsupported)
            // One message for every crypto failure. The core deliberately does
            // not distinguish causes, and this layer must not reintroduce the
            // distinction disguised as different messages.
            CipherCore.CRYPTO, CipherCore.FORMAT, CipherCore.DECODE ->
                return showNotice(perche_non_si_apre(incoming.toString()))
            // We wrote it but the recipient can no longer be found: a normal
            // outcome, not a fault.
            //
            // Two codes, because there are two causes and the difference matters
            // to the reader: the first says the recipient is no longer in the
            // contacts; the second that they are, but the key the message was
            // encrypted to was replaced or burned.
            CipherCore.OWN_MESSAGE -> return showNotice(R.string.cipher_own_message_no_peer)
            CipherCore.OWN_MESSAGE_KEY_GONE ->
                return showNotice(R.string.cipher_own_message_key_gone)
            CipherCore.OK -> CipherCompose.segnaCifrato()
            else -> return showNotice(R.string.cipher_unavailable)
        }

        // The keyring may have changed: a never-seen sender was just pinned, and
        // the current recipient for this app may have moved. Without this the pin
        // would live only in memory and vanish on restart, reopening the MITM
        // window the pin closes.
        //
        // The pin already happened in memory and cannot be undone: the honest
        // thing is to say it might not survive a restart.
        if (!CipherIdentity.persistKeyring(this)) {
            Toast.makeText(this, R.string.cipher_keyring_not_saved, Toast.LENGTH_LONG).show()
        }

        // Decrypting sets the recipient: whoever reads and then replies has
        // already chosen by reading. The core keeps that choice in memory, so it
        // is also written to disk here, otherwise the first service restart
        // erases it. This applies to our own messages too: rereading what you
        // sent to someone means looking at the conversation with them.
        val letturaDa = result.senderKey
        if (letturaDa != null &&
            (result.kind == CipherCore.KIND_MESSAGE || result.kind == CipherCore.KIND_OWN_MESSAGE)
        ) {
            // If the originating app is known, the recipient goes **there and only
            // there**: memory is per app.
            //
            // If not (the most used path, the share sheet, which the system does
            // not attribute), a pending assignment is left for the first chat
            // opened. Without it, reading from the share sheet selected nobody,
            // and replying asked again whom to.
            val gruppoLetto = result.isGroup == 1
            if (gruppoLetto) {
                // A group read selects THE GROUP, not the writer: `remember` pins a
                // person and clears the group, so the reply would go privately to
                // the sender.
                //
                // The blob does not carry the member list (decision K), so the
                // group is recognised only by comparing with saved ones. If not
                // unique: nothing is chosen, the user is told, and the recipient
                // stays. Same rule as in the keyboard.
                val gruppo = if (appDiProvenienza.isNotEmpty()) {
                    CipherGroups.riconosci(this, letturaDa, result.recipientCount)
                } else {
                    null
                }
                if (gruppo != null) {
                    CipherGroups.scegli(this, appDiProvenienza, gruppo.nome)
                } else {
                    Toast.makeText(this, R.string.cipher_group_unknown, Toast.LENGTH_LONG)
                        .show()
                }
            } else if (appDiProvenienza.isEmpty()) {
                CipherRecipients.ricordaUltimoLetto(this, letturaDa)
            } else {
                CipherRecipients.remember(this, appDiProvenienza, letturaDa)
            }
        }

        when (result.kind) {
            // A burn has no text: say what happened and close.
            CipherCore.KIND_BURNED -> showNotice(
                getString(
                    R.string.cipher_burned_incoming,
                    senderLine(result),
                    // The date of the request. A burn composed months ago and
                    // arriving now is a reposted blob, not a new request, and by
                    // then the conversation is already destroyed. Rejecting by date
                    // is ruled out (decision C: authenticated, not verifiable), so
                    // showing it is the whole defence.
                    formatTimestamp(result.sentAtUnix),
                )
            )
            CipherCore.KIND_MESSAGE -> showMessage(result)
            CipherCore.KIND_OWN_MESSAGE -> showMessage(result, mio = true)
            CipherCore.KIND_IDENTITY_CARD -> showIdentityCard(result)
            // Our own key: just say so. No action to offer: nothing to name,
            // nothing to choose as recipient.
            CipherCore.KIND_OWN_IDENTITY_CARD -> showNotice(
                getString(R.string.cipher_own_card, result.senderFingerprint.orEmpty())
            )
            else -> showNotice(R.string.cipher_unavailable)
        }
    }

    /**
     * The attachment URI as received. The caller keeps only `content://` URIs;
     * see the guard in [handle], and the reason below.
     *
     * This activity is exported, and must be: it is how other apps hand us text
     * and files. But we open what they hand us with OUR uid, and without
     * checking the scheme that made it a confused deputy: any app could send a
     * `file://` it cannot read itself (inside our `filesDir`, say) and have us
     * open it.
     *
     * With `content://` the problem does not exist: it is a reference to a
     * provider, and the read permission travels with the intent
     * (`FLAG_GRANT_READ_URI_PERMISSION`). The sender gives us access to its own
     * data, which is exactly the case to serve.
     *
     * Rejecting the rest removes no real flow: chat apps share from their own
     * `FileProvider`, file managers do the same, the document picker always
     * returns a `DocumentsProvider` URI. Since Android 7 a `file://` crossing
     * between apps crashes THE SENDER with `FileUriExposedException`.
     *
     * Verified rather than assumed: plaintext never returns to the caller (no
     * `setResult`, see `neverReturnPlaintext`) and a file that is not ours only
     * produces a notice. This closes the deputy, not an ongoing leak.
     */
    private fun extractStream(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        Intent.ACTION_VIEW -> intent.data
        else -> null
    }

    /**
     * Opens an encrypted attachment.
     *
     * The plaintext does **not** go back to the app that sent it, as for
     * messages: it is shown here, in a `FLAG_SECURE` window, and leaves only if
     * the user saves it on purpose.
     */
    private fun handleFile(uri: Uri) {
        when (val state = CipherIdentity.ensureReady(this)) {
            CipherState.Ready -> Unit
            CipherState.Locked -> return showNotice(R.string.cipher_locked)
            is CipherState.Unreadable -> return showUnreadable(state.part)
            is CipherState.Unavailable -> return showNotice(R.string.cipher_unavailable)
        }

        val result = CipherCore.IncomingResult()
        val code = CipherFiles.decrypt(this, uri, System.currentTimeMillis() / 1000, result)
        when (code) {
            CipherCore.OK -> CipherCompose.segnaCifrato()
            CipherCore.UNSUPPORTED_VERSION -> return showNotice(R.string.cipher_version_too_new)
            CipherCore.TIER_UNSUPPORTED -> return showNotice(R.string.cipher_tier_unsupported)
            CipherCore.CRYPTO -> return showNotice(R.string.cipher_cannot_decrypt)
            CipherCore.OWN_MESSAGE -> return showNotice(R.string.cipher_own_message_no_peer)
            CipherCore.OWN_MESSAGE_KEY_GONE ->
                return showNotice(R.string.cipher_own_message_key_gone)
            // Any file that is not ours ends here, the most common outcome: the
            // intent filter takes every `application/octet-stream`, the only thing
            // it can filter on.
            else -> return showNotice(R.string.cipher_not_our_blob)
        }

        // A never-seen sender was just pinned: the keyring must be written,
        // otherwise the pin lives only in memory.
        if (!CipherIdentity.persistKeyring(this)) {
            Toast.makeText(this, R.string.cipher_keyring_not_saved, Toast.LENGTH_LONG).show()
        }
        showFile(result, mio = result.kind == CipherCore.KIND_OWN_FILE)
    }

    /**
     * `ACTION_PROCESS_TEXT` carries the text in [Intent.EXTRA_PROCESS_TEXT];
     * `ACTION_SEND` in [Intent.EXTRA_TEXT].
     */
    private fun extractText(intent: Intent): CharSequence? = when (intent.action) {
        Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        else -> null
    }

    /**
     * The package of whoever invoked us, which the core uses to remember the
     * recipient per app.
     *
     * An empty string if it cannot be determined, never a guess: attributing the
     * message to the wrong app would move the current recipient of another
     * conversation, and the next encryption would go to the wrong person.
     */
    private fun resolveCallerPackage(): String {
        // Only our keyboard can have set the token: it is random, in memory, in
        // the same process, and consumed once. An external app can write any
        // extra but cannot guess it.
        CipherHandoff.consume(intent.getStringExtra(CipherHandoff.extraName()))
            ?.let { return it }

        // `callingActivity` is the only attribution set by the SYSTEM, and exists
        // only if the caller used startActivityForResult, i.e. for
        // ACTION_PROCESS_TEXT.
        //
        // `getReferrer()` is NOT used: it returns `Intent.EXTRA_REFERRER` when
        // present, an extra written by the caller. Android's documentation says
        // explicitly it is not a security feature and can be spoofed: declaring
        // oneself a chat app would be enough to move that app's recipient.
        //
        // Without attribution "" is returned, which the core treats as "unknown"
        // and which disables implicit recipient selection: the right way to fail.
        return callingActivity?.packageName.orEmpty()
    }

    // ========================================================================
    // Screens
    //
    // The same look as contacts and recipient selection: a rounded dialog, tappable
    // rows, and shared pieces from CipherUi.
    // ========================================================================

    /** What is on screen now. A single state: two screens at once do not exist. */
    private var schermo by mutableStateOf<Schermo?>(null)

    private sealed interface Schermo {
        /** Just a notice: errors, normal outcomes, burns. */
        class Avviso(val testo: String) : Schermo

        class Messaggio(
            val chi: String,
            val mio: Boolean,
            val quando: String,
            val testo: String,
        ) : Schermo

        class Presentazione(
            val gia: Boolean,
            val impronta: String,
            /** The name already given to this key, if any. */
            val nome: String?,
            val comeDestinatario: (() -> Unit)?,
            /** Opens contacts on the name dialog, to change it. */
            val rinomina: (() -> Unit)?,
        ) : Schermo

        class Allegato(
            val chi: String,
            val mio: Boolean,
            val quando: String,
            val dettaglio: String,
            val anteprima: Bitmap?,
            val nome: String,
        ) : Schermo

        class NonLeggibile(val part: CipherPart) : Schermo
    }

    private fun mostra(nuovo: Schermo) {
        schermo = nuovo
        setContent {
            Theme {
                when (val corrente = schermo) {
                    null -> Unit
                    is Schermo.Avviso -> Dialogo { Text(corrente.testo) }
                    is Schermo.Messaggio -> SchermoMessaggio(corrente)
                    is Schermo.Presentazione -> SchermoPresentazione(corrente)
                    is Schermo.Allegato -> SchermoAllegato(corrente)
                    is Schermo.NonLeggibile -> SchermoNonLeggibile(corrente.part)
                }
            }
        }
    }

    /**
     * The wrapper of every screen.
     *
     * A single "OK" button that closes. Actions (copy, contacts, save) are rows
     * inside the content, not buttons at the bottom: the same layout as the
     * contact card.
     */
    @Composable
    private fun Dialogo(
        titolo: String? = null,
        contenuto: @Composable () -> Unit,
    ) {
        ThreeButtonAlertDialog(
            onDismissRequest = { finish() },
            onConfirmed = { },
            confirmButtonText = null,
            cancelButtonText = stringResource(android.R.string.ok),
            scrollContent = true,
            reducePadding = true,
            title = titolo?.let { { Text(it) } },
            content = contenuto,
        )
    }

    @Composable
    private fun SchermoMessaggio(dati: Schermo.Messaggio) {
        Dialogo(titolo = dati.chi) {
            Column {
                if (dati.mio) Didascalia(stringResource(R.string.cipher_own_message_note))
                Didascalia(stringResource(R.string.cipher_composed_at, dati.quando))
                Spacer(Modifier.height(10.dp))
                // Selectable: one may want to take part of the message (an address,
                // a number) without copying all of it.
                SelectionContainer {
                    Text(text = dati.testo, style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(10.dp))
                Riquadro {
                    Voce(stringResource(R.string.cipher_copy)) { copyPlaintext(dati.testo) }
                    Divisore()
                    Voce(stringResource(R.string.cipher_contacts)) { apriContatti() }
                }
            }
        }
    }

    @Composable
    private fun SchermoPresentazione(dati: Schermo.Presentazione) {
        // No verification mark here: it means "compared in person", and an
        // identity card does not prove it. A different title if the key was
        // already known, because "New contact" on an old contact is false.
        Dialogo(
            titolo = stringResource(
                if (dati.gia) R.string.cipher_card_known else R.string.cipher_card_title
            )
        ) {
            Column {
                // The name before the fingerprint: on a known contact it is the only
                // thing that says WHO it is.
                dati.nome?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                }
                Impronta(dati.impronta, selezionabile = false)
                Spacer(Modifier.height(6.dp))
                Didascalia(
                    stringResource(
                        if (dati.gia) R.string.cipher_card_known_hint else R.string.cipher_card_pinned
                    )
                )
                Spacer(Modifier.height(10.dp))
                Riquadro {
                    // Renaming from here: whoever rereads a known card often does so
                    // because the name does not look right.
                    dati.rinomina?.let { azione ->
                        Voce(stringResource(R.string.cipher_rename)) { azione() }
                    }
                    // The card does NOT choose the recipient by itself: it is not
                    // authenticated, so anyone could send one and redirect whom you
                    // encrypt for. This is the explicit gesture that does it, and
                    // only if we know which app we are in.
                    if (dati.comeDestinatario != null) {
                        Voce(stringResource(R.string.cipher_use_as_recipient)) {
                            dati.comeDestinatario.invoke()
                        }
                        Divisore()
                    }
                    Voce(stringResource(R.string.cipher_contacts)) { apriContatti() }
                }
            }
        }
    }

    @Composable
    private fun SchermoAllegato(dati: Schermo.Allegato) {
        Dialogo(titolo = dati.chi) {
            Column {
                if (dati.mio) Didascalia(stringResource(R.string.cipher_own_message_note))
                Didascalia(stringResource(R.string.cipher_composed_at, dati.quando))
                Spacer(Modifier.height(10.dp))
                Text(text = dati.dettaglio, style = MaterialTheme.typography.bodyLarge)
                if (dati.anteprima != null) {
                    Spacer(Modifier.height(10.dp))
                    Image(
                        bitmap = dati.anteprima.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Riquadro {
                    Voce(stringResource(R.string.cipher_file_save)) { chiediDoveSalvare(dati.nome) }
                    Divisore()
                    Voce(stringResource(R.string.cipher_contacts)) { apriContatti() }
                }
            }
        }
    }

    @Composable
    private fun SchermoNonLeggibile(part: CipherPart) {
        // No "repair" entry: the only way out is resetIdentity, which destroys the
        // identity and shows every contact a key change. Here it would be pressed
        // just to get rid of the message. It lives in the contacts UI, behind a
        // screen that explains what is being thrown away.
        Dialogo(titolo = stringResource(R.string.cipher_unreadable_title)) {
            Text(
                stringResource(
                    if (part == CipherPart.IDENTITY) R.string.cipher_unreadable_identity
                    else R.string.cipher_unreadable_keyring
                )
            )
        }
    }

    /**
     * The sentence to show when a blob does not open, chosen by looking at **the
     * shape of the envelope**.
     *
     * This does not break the opaque-error rule: the core does not say **why**
     * decryption failed, and must not. Here we look at what the blob declares
     * about itself **in clear** (version, kind, flag bits), bytes anyone
     * intercepting the message reads anyway.
     *
     * A single sentence listing two **guessed** causes ("it may be for someone
     * else, or already opened once") was false on many blobs: "opens once only"
     * has nothing to do with a message that never used the chain, and neither
     * cause applies to a group message.
     */
    private fun perche_non_si_apre(testo: String): Int = when (CipherCore.nativeBlobShape(testo)) {
        CipherCore.SHAPE_MESSAGE_FS -> R.string.cipher_cannot_decrypt_fs
        CipherCore.SHAPE_MESSAGE_EPHEMERAL -> R.string.cipher_cannot_decrypt_ephemeral
        CipherCore.SHAPE_MESSAGE_EPOCH -> R.string.cipher_cannot_decrypt_epoch
        CipherCore.SHAPE_MESSAGE -> R.string.cipher_cannot_decrypt_plain
        CipherCore.SHAPE_GROUP -> R.string.cipher_cannot_decrypt_group
        CipherCore.SHAPE_BURN -> R.string.cipher_cannot_decrypt_burn
        // Identity cards and unknown shapes: nothing more precise to say, and
        // inventing it would bring back the problem.
        else -> R.string.cipher_cannot_decrypt
    }

    /**
     * @param mio when we wrote the message: then the person shown is the
     *   **recipient**, and calling them "sender" would be false.
     */
    private fun showMessage(result: CipherCore.IncomingResult, mio: Boolean = false) {
        val bytes = result.plaintext
        if (bytes == null) {
            showNotice(R.string.cipher_cannot_decrypt)
            return
        }
        // The core returns a ByteArray, not a String, so it can be zeroized. Showing
        // it needs a CharSequence, so a non-zeroizable copy exists in the UI anyway:
        // the guarantee stops here. Mitigated by FLAG_SECURE, closing in onStop,
        // and zeroizing the array.
        val testo = try {
            String(bytes, Charsets.UTF_8)
        } finally {
            bytes.fill(0)
        }
        val chi = senderLine(result)
        mostra(
            Schermo.Messaggio(
                // The "compared in person" mark is not shown on our own message:
                // there is no identity to verify.
                chi = when {
                    mio -> getString(R.string.cipher_own_message_to, chi)
                    // See CipherActions: no author is shown in a group, because any
                    // member could have rewritten the text keeping the original
                    // slots (decision K6).
                    result.isGroup == 1 -> getString(R.string.cipher_group_header)
                    result.verified == 1 -> getString(R.string.cipher_sender_verified, chi)
                    else -> chi
                },
                mio = mio,
                // See CipherActions: a group message says it is one and states the
                // price, because it has no forward secrecy.
                quando = if (result.isGroup == 1) {
                    formatTimestamp(result.sentAtUnix) + "  ·  " +
                        getString(R.string.cipher_group_message, result.recipientCount)
                } else {
                    formatTimestamp(result.sentAtUnix)
                },
                testo = testo,
            )
        )
    }

    private fun showIdentityCard(result: CipherCore.IncomingResult) {
        val peer = result.senderKey
        val app = appDiProvenienza
        val gia = result.alreadyPinned == 1

        // First time: straight to contacts to give it a name. A key without a name
        // is a contact nobody recognises, and "Mark's key changed" only exists if
        // Mark has a name.
        if (!gia && peer != null) {
            runCatching { startActivity(ContactsActivity.intentNomina(this, peer)) }
                .onSuccess { finish(); return }
        }

        mostra(
            Schermo.Presentazione(
                gia = gia,
                impronta = result.senderFingerprint.orEmpty(),
                // The core holds the name, not the intent: `senderLabel` is empty
                // for a key just pinned and set for a known one, exactly the
                // distinction needed here.
                nome = result.senderLabel?.takeIf { it.isNotBlank() },
                rinomina = peer?.let { chiave ->
                    {
                        runCatching { startActivity(ContactsActivity.intentNomina(this, chiave)) }
                            .onSuccess { finish() }
                    }
                },
                comeDestinatario = if (peer != null && app.isNotEmpty()) {
                    {
                        if (CipherCore.nativeSetCurrentPeer(app, peer) == CipherCore.OK) {
                            CipherRecipients.remember(this, app, peer)
                            Toast.makeText(this, R.string.cipher_recipient_set, Toast.LENGTH_SHORT).show()
                            finish()
                        } else {
                            Toast.makeText(this, R.string.cipher_unavailable, Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    null
                },
            )
        )
    }

    /**
     * Shows a decrypted attachment.
     *
     * The content stays in memory and in this window: it is **not** written
     * anywhere until the user saves it on purpose. A decrypted file left in
     * Downloads ends up in the gallery and the cloud backup, exactly where
     * encryption was meant to keep it out of.
     *
     * @param mio as in [showMessage]: then the person shown is the recipient.
     */
    private fun showFile(result: CipherCore.IncomingResult, mio: Boolean = false) {
        val content = result.fileContent
        val name = result.fileName.orEmpty()
        if (content == null) {
            showNotice(R.string.cipher_cannot_decrypt)
            return
        }
        fileInAttesa = content
        nomeInAttesa = name

        // Images are viewed in here, under FLAG_SECURE. The type is declared by the
        // sender and proves nothing: if the content is not an image, decoding fails
        // and only the description line remains.
        val anteprima = if (result.fileMime.orEmpty().startsWith("image/")) {
            runCatching {
                android.graphics.BitmapFactory.decodeByteArray(content, 0, content.size)
            }.getOrNull()
        } else {
            null
        }

        val chi = senderLine(result)
        mostra(
            Schermo.Allegato(
                chi = when {
                    mio -> getString(R.string.cipher_own_message_to, chi)
                    // See CipherActions: no author is shown in a group, because any
                    // member could have rewritten the text keeping the original
                    // slots (decision K6).
                    result.isGroup == 1 -> getString(R.string.cipher_group_header)
                    result.verified == 1 -> getString(R.string.cipher_sender_verified, chi)
                    else -> chi
                },
                mio = mio,
                // See CipherActions: a group message says it is one and states the
                // price, because it has no forward secrecy.
                quando = if (result.isGroup == 1) {
                    formatTimestamp(result.sentAtUnix) + "  ·  " +
                        getString(R.string.cipher_group_message, result.recipientCount)
                } else {
                    formatTimestamp(result.sentAtUnix)
                },
                dettaglio = getString(R.string.cipher_file_detail, name, content.size / 1024),
                anteprima = anteprima,
                nome = name,
            )
        )
    }

    /**
     * Entry to the contacts UI, where a key gets a name and is confirmed in
     * person.
     *
     * Here because this is when it is needed: the user just saw a contact
     * appear. `ContactsActivity` is not in the launcher.
     */
    private fun apriContatti() {
        runCatching { startActivity(ContactsActivity.intent(this)) }
        finish()
    }

    @Suppress("DEPRECATION")
    private fun chiediDoveSalvare(name: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/octet-stream"
            // The name comes from the sender: authenticated, not trustworthy. Only
            // the last segment is kept, so a name with `../` or a separator cannot
            // propose a path.
            putExtra(Intent.EXTRA_TITLE, name.substringAfterLast('/').ifEmpty { "allegato" })
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        inAttesaDelSelettore = true
        if (runCatching { startActivityForResult(intent, RICHIESTA_SALVA) }.isFailure) {
            inAttesaDelSelettore = false
            Toast.makeText(this, R.string.cipher_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") // paired with startActivityForResult above
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        inAttesaDelSelettore = false
        val uri = data?.data
        if (requestCode != RICHIESTA_SALVA || resultCode != RESULT_OK || uri == null) return
        val content = fileInAttesa ?: return
        val scritto = runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(content) } != null
        }.getOrDefault(false)
        Toast.makeText(
            this,
            if (scritto) R.string.cipher_file_saved else R.string.cipher_unavailable,
            Toast.LENGTH_LONG,
        ).show()
    }

    /**
     * Plaintext is not reachable by going back.
     *
     * `noHistory` in the manifest used to do this, and was too much: it also
     * closes the window when **we** put the "where to save" picker in front, and
     * then nobody is left to write the file. Here the same property is obtained
     * by closing by hand, with the one exception needed.
     *
     * `isChangingConfigurations`: a rotation is not leaving the screen.
     */
    override fun onStop() {
        super.onStop()
        if (!inAttesaDelSelettore && !isChangingConfigurations) finish()
    }

    /**
     * Plaintext does not outlive the window that showed it.
     *
     * Not a strong guarantee (GC may have made copies, and the decoded `Bitmap`
     * stays until collected), but the same rule as the rest of the project:
     * zeroize what can be zeroized.
     */
    override fun onDestroy() {
        fileInAttesa?.fill(0)
        fileInAttesa = null
        nomeInAttesa = null
        super.onDestroy()
    }

    private fun showUnreadable(part: CipherPart) = mostra(Schermo.NonLeggibile(part))

    private fun showNotice(resId: Int) = showNotice(getString(resId))

    private fun showNotice(testo: String) = mostra(Schermo.Avviso(testo))

    private fun senderLine(result: CipherCore.IncomingResult): String =
        result.senderLabel ?: result.senderFingerprint ?: getString(R.string.cipher_unknown_sender)

    /**
     * The timestamp is authenticated (it is inside the ciphertext) but NOT
     * verifiable: nobody can prove the sender's clock was right. Shown because it
     * makes a blob reposted months later visible to a human; never used to
     * decide anything.
     */
    private fun formatTimestamp(unix: Long): String {
        val date = Date(unix * 1000)
        return "${DateFormat.getDateFormat(this).format(date)} ${DateFormat.getTimeFormat(this).format(date)}"
    }

    /**
     * WARNING: the `ACTION_PROCESS_TEXT` contract lets the activity return
     * replacement text to the caller with
     * `setResult(RESULT_OK, Intent().putExtra(EXTRA_PROCESS_TEXT, newText))`.
     *
     * That is NEVER done here, for any reason. Returning the plaintext would hand
     * it to the chat app the selection came from, exactly the application this
     * whole project exists to keep it from. It is the "natural" implementation of
     * this intent, and the worst possible one.
     *
     * Plaintext is shown only in our own window, and ends there.
     */
    @Suppress("unused")
    private fun neverReturnPlaintext() = Unit

    /**
     * Copies the plaintext, only if the user explicitly asks.
     *
     * Still the most dangerous operation in the app.
     *
     * TRAP, now closed: the system clipboard is read by the default IME, i.e.
     * THIS keyboard, which keeps a history that can be persisted to disk. The
     * decrypted text would have ended up in that archive, the opposite of why the
     * message was encrypted. `CipherClipboard.markSensitive` excludes it, on every
     * Android version.
     *
     * `EXTRA_IS_SENSITIVE` (Android 13+) covers something else: it hides the
     * preview in the system popup, so the text does not show to someone looking
     * at the screen. It is not protection (the text is in the clipboard in clear,
     * readable by any app with focus), and that residual cannot be removed: from
     * here on the plaintext is outside our perimeter.
     */
    private fun copyPlaintext(text: CharSequence) {
        // BEFORE setPrimaryClip: the history listener may fire during that call,
        // and a marker set afterwards would be too late.
        CipherClipboard.markSensitive(text)
        val clip = ClipData.newPlainText(null, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        Toast.makeText(this, R.string.cipher_copied, Toast.LENGTH_SHORT).show()
    }
}
