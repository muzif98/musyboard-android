package helium314.keyboard.cipher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import java.util.Date

/**
 * Contact management: peer list, fingerprints, labels, key conflicts, own
 * identity.
 *
 * Not exported and not in the launcher: an icon would be a second visible
 * marker of the system, and would do nothing a settings entry does not.
 *
 * ## Why Compose rather than hand-built views
 *
 * The same [Theme] and dialogs as the settings screens are used, so colours,
 * typography and shapes match the rest of the app.
 *
 * What must be preserved when touching this file: `FLAG_SECURE`, the order of
 * keyring operations, persisting right after every change, and the position
 * of destructive buttons (see [DialogoDistruttivo]).
 */
class ContactsActivity : ComponentActivity() {

    /**
     * Who gets the file chosen in the picker about to open.
     *
     * It lives across two activities, so a recreation can reset it: in that case
     * nothing is encrypted and no recipient is guessed.
     */
    private var destinatarioFile: ByteArray? = null

    /** Alive only between asking for the passphrase and the picker's return. */
    private var passphraseInAttesa: ByteArray? = null

    /**
     * Changes when the keyring changes, which is enough to reread everything.
     *
     * The keyring lives in the core: there is nothing observable to subscribe
     * to, so recomposition is requested by hand after every write.
     */
    private var revisione by mutableIntStateOf(0)

    private var dialogo by mutableStateOf<Dialogo>(Dialogo.Nessuno)

    /** The fingerprint of a key: `Peer` carries the key, not its readable form. */
    private fun improntaDi(peer: Peer): String =
        CipherCore.nativeFingerprintOf(peer.key).orEmpty()

    /** True between confirming the replacement and writing the name. */
    private var conflittoAccettato = false

    /**
     * The app the identity card came from, from the single-use token.
     *
     * A token, not an extra: extras can be written by anyone, and whom we will
     * encrypt for depends on this value.
     */
    private fun appDiProvenienza(): String? =
        CipherHandoff.consume(intent?.getStringExtra(CipherHandoff.extraName()))
            ?.takeIf { it.isNotEmpty() }

    /**
     * We arrived here from the keyboard's "attachment" key.
     *
     * The screen stays the same (the contact list) because a file's recipient is
     * **always** chosen by hand (decision G4) and this is where people already
     * are. Only what a tap does changes.
     */
    private val perAllegato: Boolean by lazy { intent?.getBooleanExtra(EXTRA_ALLEGATO, false) == true }

    /**
     * The picker starts filtered on images and video.
     *
     * A convenience filter, not a restriction: the encrypted container carries
     * any bytes. It only helps find photos where one looks for them.
     */
    private val soloMedia: Boolean by lazy { intent?.getBooleanExtra(EXTRA_SOLO_MEDIA, false) == true }

    /**
     * The key to name right away, when arriving from an identity card just
     * pinned.
     *
     * Naming is the gesture needed at that moment: a key without a name is a
     * contact nobody recognises, and "Mark's key changed" only exists if Mark has
     * a name.
     */
    private val daNominare: ByteArray? by lazy { intent?.getByteArrayExtra(EXTRA_NOMINA) }

    private val selettoreEsporta =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri == null) azzeraPassphrase() else esporta(uri)
        }

    private val selettoreImporta =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) azzeraPassphrase() else importa(uri)
        }

    private val selettoreFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) destinatarioFile = null else cifraEInvia(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // No plaintext here, but contact names and fingerprints, which must not
        // end up in the automatic Recents screenshots. Before any content, as in
        // the other activities showing sensitive data.
        //
        // Same switch as the others: a screen block that applies to three screens
        // out of four is not a block, it is a surprise.
        if (CipherSettings.isBlockScreenshot(this)) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nominaSubito()
        distruggiSubito()
        setContent {
            Theme {
                Surface {
                    Schermo()
                }
            }
        }
    }

    private val daDistruggere: Boolean by lazy {
        intent?.getBooleanExtra(EXTRA_DISTRUGGI, false) == true
    }

    /**
     * Opens the full-destruction confirmation right away, when arriving from a
     * long press on the contacts key.
     *
     * It destroys nothing: it shows the usual dialog, which says what will be
     * lost. The shortcut shortens the way to **asking**, it does not skip the
     * question.
     */
    private fun distruggiSubito() {
        if (daDistruggere) dialogo = Dialogo.Reset
    }

    /**
     * When arriving from an identity card just pinned, the name dialog is
     * already open when the screen appears.
     *
     * The peer is looked up in the list rather than built from the bytes: if for
     * any reason it is not in the keyring, no dialog opens on a key that is not
     * there.
     */
    private fun nominaSubito() {
        val chiave = daNominare ?: return
        val peer = CipherCore.nativeListPeers()
            ?.let { PeerList.parse(it) }
            ?.firstOrNull { it.key.contentEquals(chiave) }
            ?: return
        dialogo = Dialogo.Nome(peer)
    }

    // ========================================================================
    // Screen
    // ========================================================================

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Schermo() {
        // `ensureReady` and reading the keyring are core calls, so they are redone
        // only when something really changed.
        val stato = remember(revisione) { CipherIdentity.ensureReady(this) }
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { bordi ->
            Column(Modifier.padding(bordi)) {
                TopAppBar(
                    title = { Text(stringResource(R.string.cipher_contacts)) },
                    windowInsets = WindowInsets(0),
                    navigationIcon = { BackButton { finish() } },
                )
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    when (stato) {
                        CipherState.Ready -> Contatti()
                        CipherState.Locked -> Avviso(stringResource(R.string.cipher_locked))
                        is CipherState.Unavailable -> Avviso(stringResource(R.string.cipher_unavailable))
                        is CipherState.Unreadable -> NonLeggibile(stato.part)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        Dialoghi()
    }

    @Composable
    private fun Contatti() {
        val impronta = remember(revisione) { CipherCore.nativeMyFingerprint().orEmpty() }
        val peers = remember(revisione) { CipherCore.nativeListPeers()?.let { PeerList.parse(it) } }

        if (perAllegato) {
            // Only the list: someone sending a file has nothing to do with their
            // identity, the QR code or backups.
            Titolo(
                stringResource(
                    if (soloMedia) R.string.cipher_media_pick_recipient
                    else R.string.cipher_file_pick_recipient
                )
            )
            Riquadro { ElencoContatti(peers) }
            return
        }

        Titolo(stringResource(R.string.cipher_my_identity))
        Riquadro {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                Impronta(impronta, selezionabile = true)
                Didascalia(
                    stringResource(R.string.cipher_my_identity_hint),
                    Modifier.padding(top = 6.dp),
                )
            }
            Divisore()
            Voce(stringResource(R.string.cipher_show_qr)) { dialogo = Dialogo.Qr }
            Divisore()
            Voce(stringResource(R.string.cipher_backup_export)) {
                dialogo = Dialogo.Passphrase(esporta = true)
            }
            Divisore()
            Voce(stringResource(R.string.cipher_backup_import)) {
                dialogo = Dialogo.Passphrase(esporta = false)
            }
        }

        Titolo(stringResource(R.string.cipher_contacts))
        Riquadro { ElencoContatti(peers) }
    }

    @Composable
    private fun ElencoContatti(peers: List<Peer>?) {
        when {
            peers == null -> Vuoto(stringResource(R.string.cipher_unavailable))
            peers.isEmpty() -> Vuoto(stringResource(R.string.cipher_no_contacts))
            else -> peers.forEachIndexed { indice, peer ->
                if (indice > 0) Divisore()
                RigaContatto(peer)
            }
        }
    }

    @Composable
    private fun RigaContatto(peer: Peer) {
        val nome = peer.label ?: stringResource(R.string.cipher_unnamed_peer)
        Contatto(
            nome = if (peer.verified) stringResource(R.string.cipher_sender_verified, nome) else nome,
            impronta = fingerprintOf(peer),
            visto = stringResource(R.string.cipher_first_seen, formatDate(peer.firstSeenUnix)),
        ) {
            // Coming from the "attachment" key, a tap selects **the file's
            // recipient** instead of opening the card: the user already said what
            // they want to do.
            if (perAllegato) inviaFile(peer) else dialogo = Dialogo.Scheda(peer)
        }
    }

    // ========================================================================
    // Dialogs
    // ========================================================================

    /**
     * The open dialog, if any.
     *
     * `chiudi` closes **only if nothing changed meanwhile**: [ThreeButtonAlertDialog]
     * always closes after confirming, so an action that opens another dialog
     * (a name that ends in a key conflict) would see it closed an instant after
     * opening it. The conflict is exactly the case that must not go unnoticed.
     */
    @Composable
    private fun Dialoghi() {
        val aperto = dialogo
        val chiudi = { if (dialogo === aperto) dialogo = Dialogo.Nessuno }
        when (val corrente = aperto) {
            Dialogo.Nessuno -> Unit
            is Dialogo.Scheda -> SchedaContatto(corrente.peer, chiudi)
            is Dialogo.Nome -> ChiediNome(corrente.peer, chiudi)
            is Dialogo.Conflitto -> Conflitto(corrente, chiudi)
            is Dialogo.Verifica -> Verifica(corrente.peer, chiudi)
            is Dialogo.Rogo -> ChiediDiBruciare(corrente.peer, chiudi)
            is Dialogo.Oblio -> ChiediDiDimenticare(corrente.peer, chiudi)
            is Dialogo.Sostituzione -> ChiediSeSostituire(corrente, chiudi)
            Dialogo.Qr -> Qr(chiudi)
            is Dialogo.Passphrase -> ChiediPassphrase(corrente.esporta, chiudi)
            is Dialogo.ConfermaImport -> ConfermaImport(corrente, chiudi)
            is Dialogo.ImportFatto -> ImportFatto(corrente, chiudi)
            Dialogo.Reset -> ChiediReset(chiudi)
        }
    }

    @Composable
    private fun SchedaContatto(peer: Peer, chiudi: () -> Unit) {
        val nome = peer.label ?: stringResource(R.string.cipher_unnamed_peer)
        ThreeButtonAlertDialog(
            onDismissRequest = chiudi,
            onConfirmed = { },
            confirmButtonText = null,
            cancelButtonText = stringResource(android.R.string.cancel),
            reducePadding = true,
            title = { Text(nome) },
            content = {
                Column {
                    SelectionContainer {
                        Impronta(fingerprintOf(peer), selezionabile = false, Modifier.padding(horizontal = 8.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Voce(stringResource(R.string.cipher_assign_label)) { dialogo = Dialogo.Nome(peer) }
                    Voce(stringResource(R.string.cipher_mark_verified)) { dialogo = Dialogo.Verifica(peer) }
                    Voce(stringResource(R.string.cipher_file_send)) { inviaFile(peer) }
                    Voce(stringResource(R.string.cipher_burn), distruttiva = true) {
                        dialogo = Dialogo.Rogo(peer)
                    }
                    Voce(stringResource(R.string.cipher_forget), distruttiva = true) {
                        dialogo = Dialogo.Oblio(peer)
                    }
                }
            },
        )
    }

    @Composable
    private fun ChiediNome(peer: Peer, chiudi: () -> Unit) {
        val iniziale = peer.label.orEmpty()
        // The old name is SELECTED, not just shown: whoever opens this dialog to
        // rename expects the first letter to replace it. That is why
        // `TextInputDialog`, which puts the cursor at the end, is not used here.
        var valore by remember {
            mutableStateOf(TextFieldValue(iniziale, TextRange(0, iniziale.length)))
        }
        ThreeButtonAlertDialog(
            onDismissRequest = chiudi,
            onConfirmed = { assegnaNome(peer, valore.text.trim()) },
            checkOk = { valore.text.isNotBlank() },
            title = { Text(stringResource(R.string.cipher_assign_label)) },
            content = {
                Column {
                    Text(stringResource(R.string.cipher_assign_label_hint))
                    Spacer(Modifier.height(8.dp))
                    val fuoco = remember { FocusRequester() }
                    // The cursor starts HERE. Otherwise the first tap went into
                    // focusing the field, and immediate typing landed in the
                    // keyboard's compose row.
                    LaunchedEffect(Unit) { fuoco.requestFocus() }
                    OutlinedTextField(
                        value = valore,
                        onValueChange = { valore = it },
                        singleLine = true,
                        // Enter confirms, like OK: this dialog appears right after
                        // meeting someone, when the user wants to finish quickly.
                        //
                        // `Done` also changes the enter key icon. The condition is
                        // the same as `checkOk`: enter must not confirm a name the
                        // button refuses.
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (valore.text.isNotBlank()) {
                                    assegnaNome(peer, valore.text.trim())
                                    chiudi()
                                }
                            },
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(fuoco),
                    )
                }
            },
        )
    }

    /**
     * "Safety number changed".
     *
     * This is the right moment because it is the only one in which the user is
     * declaring who someone is: pinning alone cannot be a conflict (when an
     * unseen key arrives, the system cannot know whether it is a new contact or a
     * known one with a new phone). Only the user knows, and says it here.
     *
     * Four deliberate constraints:
     *
     *  - BOTH fingerprints are shown, the pinned one and the new one;
     *  - both readings are explained without picking one: the peer reinstalled
     *    the app, or someone is in the middle;
     *  - the default is to do nothing: the old key keeps the name;
     *  - the replacement is on the button far from the thumb, not on the one
     *    pressed to close.
     */
    @Composable
    private fun Conflitto(conflitto: Dialogo.Conflitto, chiudi: () -> Unit) {
        DialogoDistruttivo(
            titolo = stringResource(R.string.cipher_conflict_title),
            corpo = stringResource(
                R.string.cipher_conflict_body,
                conflitto.nome,
                conflitto.improntaEsistente,
                conflitto.improntaNuova,
            ),
            azione = stringResource(R.string.cipher_conflict_replace),
            rinuncia = stringResource(R.string.cipher_conflict_keep),
            chiudi = chiudi,
        ) {
            confermaCambioChiave(conflitto.chiaveEsistente, conflitto.peer)
        }
    }

    @Composable
    private fun Verifica(peer: Peer, chiudi: () -> Unit) {
        ThreeButtonAlertDialog(
            onDismissRequest = chiudi,
            onConfirmed = { segnaVerificato(peer) },
            title = { Text(stringResource(R.string.cipher_mark_verified)) },
            content = { Text(stringResource(R.string.cipher_mark_verified_body, fingerprintOf(peer))) },
        )
    }

    /**
     * Burns the conversation, after confirmation.
     *
     * The notice says two different things that must stay distinct: **on this
     * phone it is final**, and on the other one it is a request their app may or
     * may not honour. Presenting it as guaranteed deletion would be the easiest
     * and most harmful lie here.
     */
    @Composable
    private fun ChiediDiBruciare(peer: Peer, chiudi: () -> Unit) {
        val nome = peer.label ?: stringResource(R.string.cipher_unnamed_peer)
        // With forward secrecy on, burning destroys something that was already
        // gone: messages open once and there is no history. The action stays (it
        // still clears state, and hiding it would make the app look broken) but
        // the text says so, rather than promising a destruction that already
        // happened.
        val corpo = if (CipherSettings.isForwardSecrecy(this)) {
            stringResource(R.string.cipher_burn_warning_fs) +
                "\n\n" + stringResource(R.string.cipher_burn_warning)
        } else {
            stringResource(R.string.cipher_burn_warning)
        }
        DialogoDistruttivo(
            titolo = stringResource(R.string.cipher_burn_title, nome),
            corpo = corpo,
            azione = stringResource(R.string.cipher_burn),
            chiudi = chiudi,
        ) { brucia(peer) }
    }

    /**
     * You are about to give this key the name of a different key you had
     * forgotten.
     *
     * It does not claim anyone is lying: the app knows keys, not people, and
     * "changed phone" and "impersonating" are indistinguishable from inside. It
     * says to stop and compare the fingerprint out of band, the only thing that
     * settles the doubt.
     */
    @Composable
    private fun ChiediSeSostituire(dati: Dialogo.Sostituzione, chiudi: () -> Unit) {
        DialogoDistruttivo(
            titolo = stringResource(R.string.cipher_substitute_title, dati.nomeVecchio),
            corpo = stringResource(R.string.cipher_substitute_warning, dati.nomeVecchio),
            azione = stringResource(R.string.cipher_substitute_ok),
            chiudi = chiudi,
        ) {
            conflittoAccettato = true
            assegnaNome(dati.peer, dati.nome)
        }
    }

    /**
     * Forgets a contact, after confirmation.
     *
     * The confirmation is not politeness. Deleting a contact **loses the pin**:
     * the next message from that person will appear as an unseen sender and be
     * silently re-pinned, exactly what one would see if someone were
     * impersonating them. The user must know beforehand.
     */
    @Composable
    private fun ChiediDiDimenticare(peer: Peer, chiudi: () -> Unit) {
        val nome = peer.label ?: stringResource(R.string.cipher_unnamed_peer)
        DialogoDistruttivo(
            titolo = stringResource(R.string.cipher_forget_title, nome),
            corpo = stringResource(R.string.cipher_forget_warning),
            azione = stringResource(R.string.cipher_forget),
            chiudi = chiudi,
        ) { dimentica(peer) }
    }

    /**
     * The QR code of our identity card, for in-person exchange.
     *
     * The only way to close first-contact MITM, which TOFU alone does not: from
     * then on the pin protects, but that first exchange stays exposed if it only
     * happens over the channel nobody should be able to read.
     *
     * The string stays under the code: it can be read aloud if the other side
     * has no reader, and it is the only option if generation fails.
     */
    @Composable
    private fun Qr(chiudi: () -> Unit) {
        val lato = (resources.displayMetrics.widthPixels * 0.8f).toInt()
        val bitmap = remember { CipherCore.nativeIdentityCard()?.let { CipherQr.encode(it, lato) } }
        ThreeButtonAlertDialog(
            onDismissRequest = chiudi,
            onConfirmed = { },
            confirmButtonText = null,
            cancelButtonText = stringResource(android.R.string.ok),
            scrollContent = true,
            title = { Text(stringResource(R.string.cipher_show_qr)) },
            content = {
                Column {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            contentScale = ContentScale.Fit,
                            // No filtering when scaling: interpolating modules
                            // blurs the edges, which is what makes reading fail.
                            filterQuality = FilterQuality.None,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Didascalia(stringResource(R.string.cipher_qr_hint))
                }
            },
        )
    }

    /**
     * Asks for the passphrase, then opens the file picker.
     *
     * The passphrase is asked BEFORE the file: asked afterwards, the user would
     * choose where to save and only then discover they must invent something to
     * remember, the best way to get a terrible passphrase.
     */
    @Composable
    private fun ChiediPassphrase(esporta: Boolean, chiudi: () -> Unit) {
        var testo by remember { mutableStateOf("") }
        // On export the passphrase is typed twice: it is hidden, and a typo here
        // produces a file that will never open.
        var ripeti by remember { mutableStateOf("") }
        ThreeButtonAlertDialog(
            onDismissRequest = chiudi,
            onConfirmed = {
                // Survives until the picker returns: a short but real window, which
                // is why it is zeroized as soon as it is used. What is zeroized is
                // this array; the text field's `String` cannot be.
                passphraseInAttesa = testo.toByteArray()
                apriSelettore(esporta)
            },
            checkOk = { testo.isNotEmpty() && (!esporta || testo == ripeti) },
            title = {
                Text(
                    stringResource(
                        if (esporta) R.string.cipher_backup_export else R.string.cipher_backup_import
                    )
                )
            },
            content = {
                Column {
                    Text(
                        stringResource(
                            if (esporta) R.string.cipher_backup_export_hint
                            else R.string.cipher_backup_import_hint
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = testo,
                        onValueChange = { testo = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (esporta) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = ripeti,
                            onValueChange = { ripeti = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.cipher_backup_ripeti)) },
                            isError = ripeti.isNotEmpty() && ripeti != testo,
                            supportingText = if (ripeti.isNotEmpty() && ripeti != testo) {
                                { Text(stringResource(R.string.cipher_backup_non_coincidono)) }
                            } else null,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
        )
    }

    /** The last confirmation before replacing the identity. */
    @Composable
    private fun ConfermaImport(richiesta: Dialogo.ConfermaImport, chiudi: () -> Unit) {
        DialogoDistruttivo(
            titolo = stringResource(R.string.cipher_backup_import),
            corpo = stringResource(R.string.cipher_backup_import_conferma),
            azione = stringResource(R.string.cipher_backup_import_procedi),
            // On cancel, the dialog's copy must be zeroized: it is the only path
            // where `eseguiImport` does not run. Zeroizing again after execution
            // is harmless.
            chiudi = { chiudi(); richiesta.pass.fill(0); azzeraPassphrase() },
        ) { eseguiImport(richiesta.blob, richiesta.pass) }
    }

    /**
     * What was just adopted, after a successful import.
     *
     * A backup **does not prove where it comes from**: whoever provides file and
     * passphrase plants an identity whose private key they know, and contacts
     * already marked "verified in person" (the system's only anti-MITM signal)
     * without any out-of-band comparison.
     *
     * It cannot be prevented (importing a backup IS adopting someone else's
     * state), but it can be made visible, the same defence chosen against
     * replay. The fingerprint is the one thing the user can compare with what
     * they had; the number of already verified contacts is the surprising
     * figure, and the one a hostile backup would exploit.
     */
    @Composable
    private fun ImportFatto(dati: Dialogo.ImportFatto, chiudi: () -> Unit) {
        ThreeButtonAlertDialog(
            onDismissRequest = chiudi,
            onConfirmed = { },
            confirmButtonText = null,
            cancelButtonText = stringResource(android.R.string.ok),
            reducePadding = true,
            title = { Text(stringResource(R.string.cipher_backup_importato)) },
            content = {
                Text(
                    stringResource(
                        R.string.cipher_backup_importato_dettaglio,
                        dati.impronta,
                        dati.verificati,
                    )
                )
            },
        )
    }

    @Composable
    private fun ChiediReset(chiudi: () -> Unit) {
        DialogoDistruttivo(
            titolo = stringResource(R.string.cipher_reset_identity),
            // Three consequences, all stated: the old identity does not come back,
            // messages already received stay unreadable, and every contact will see
            // a key change, the same signal the system uses for "someone is
            // impersonating them".
            corpo = stringResource(R.string.cipher_reset_identity_body),
            azione = stringResource(R.string.cipher_reset_identity_confirm),
            chiudi = chiudi,
        ) {
            CipherIdentity.resetIdentity(this)
            ricarica()
        }
    }

    /**
     * A dialog that destroys something.
     *
     * The action is on the button **farthest from the thumb**, and the one that
     * does nothing where the thumb naturally lands. Against convention on
     * purpose: the natural spot (bottom right) is taken by "cancel", and the
     * action lives on the left, reachable only by aiming.
     */
    @Composable
    private fun DialogoDistruttivo(
        titolo: String,
        corpo: String,
        azione: String,
        chiudi: () -> Unit,
        rinuncia: String = stringResource(android.R.string.cancel),
        esegui: () -> Unit,
    ) {
        ThreeButtonAlertDialog(
            onDismissRequest = chiudi,
            onConfirmed = { },
            confirmButtonText = null,
            cancelButtonText = rinuncia,
            neutralButtonText = azione,
            // **Execute first, then close**, and the order matters.
            //
            // `chiudi` is not always just "remove the dialog": callers attach
            // cleanup to it. In the import confirmation it zeroizes the passphrase,
            // which is the argument of `esegui`. Closing first ran the import with
            // an array of zeros, which could never succeed.
            //
            // The rule: what the action needs is not torn down before the action.
            onNeutral = { esegui(); chiudi() },
            scrollContent = true,
            title = { Text(titolo) },
            content = { Text(corpo) },
        )
    }

    // ========================================================================
    // Keyring actions
    // ========================================================================

    private fun assegnaNome(peer: Peer, nome: String) {
        if (nome.isEmpty()) return
        // You are about to give this key a name you had already given to a
        // DIFFERENT key, later forgotten. It proves nothing (the app knows keys,
        // not people), but it is the contradiction the pin would have reported as
        // "key changed", which forgetting disarmed. Shown before writing; the
        // user can go on.
        val conflitto = CipherLapidi.conflitto(this, nome, improntaDi(peer))
        if (conflitto != null && !conflittoAccettato) {
            dialogo = Dialogo.Sostituzione(peer, nome, conflitto.nome)
            return
        }
        conflittoAccettato = false
        val result = CipherCore.IncomingResult()
        if (CipherCore.nativeAssignLabel(peer.key, nome, result) != CipherCore.OK) {
            toast(R.string.cipher_unavailable)
            return
        }
        when (result.kind) {
            CipherCore.LABEL_ASSIGNED -> {
                if (!CipherIdentity.persistKeyring(this)) toast(R.string.cipher_keyring_not_saved)
                ricarica()
            }
            // The conflict is NOT a failure: it is a state that needs the UI. The
            // core changed nothing and will not until an explicit confirmation.
            CipherCore.LABEL_CONFLICT -> {
                val esistente = result.existingKey
                if (esistente == null) {
                    toast(R.string.cipher_unavailable)
                    return
                }
                dialogo = Dialogo.Conflitto(
                    peer = peer,
                    nome = nome,
                    chiaveEsistente = esistente,
                    improntaEsistente = result.existingFingerprint.orEmpty(),
                    improntaNuova = result.senderFingerprint ?: fingerprintOf(peer),
                )
            }
            else -> toast(R.string.cipher_unavailable)
        }
    }

    private fun confermaCambioChiave(chiaveVecchia: ByteArray, peer: Peer) {
        val code = CipherCore.nativeConfirmKeyChange(
            chiaveVecchia,
            peer.key,
            System.currentTimeMillis() / 1000,
        )
        if (code != CipherCore.OK) {
            toast(R.string.cipher_unavailable)
            return
        }
        // replace_pinned clears `verified`: a new key has not been compared out of
        // band, by definition. The user will have to do it again.
        if (!CipherIdentity.persistKeyring(this)) toast(R.string.cipher_keyring_not_saved)
        toast(R.string.cipher_key_replaced)
        ricarica()
    }

    private fun segnaVerificato(peer: Peer) {
        if (CipherCore.nativeMarkVerified(peer.key) != CipherCore.OK) {
            toast(R.string.cipher_unavailable)
            return
        }
        if (!CipherIdentity.persistKeyring(this)) toast(R.string.cipher_keyring_not_saved)
        ricarica()
    }

    private fun brucia(peer: Peer) {
        val richiesta = CipherCore.nativeBurnConversation(peer.key, System.currentTimeMillis() / 1000)
        // To disk IMMEDIATELY: on this side the burn already happened in memory,
        // and a process dying now would leave the keys in place.
        if (!CipherIdentity.persistKeyring(this)) toast(R.string.cipher_keyring_not_saved)
        // Three outcomes, not two. `null`: the burn did not happen. Empty string:
        // it happened but the request for the other person could not be built;
        // saying "unavailable" would be false about an irreversible operation that
        // worked.
        if (richiesta == null) {
            toast(R.string.cipher_unavailable)
            ricarica()
            return
        }
        if (richiesta.isEmpty()) {
            toast(R.string.cipher_burn_done_no_request)
            ricarica()
            return
        }
        // The request is delivered by hand, like everything else: there is no
        // channel to the other person, and inventing one would mean giving the
        // keyboard internet access.
        copiaNegliAppunti(richiesta)
        toast(R.string.cipher_burn_done)
        ricarica()
    }

    private fun dimentica(peer: Peer) {
        // The tombstone FIRST: after `nativeForgetPeer` the name is gone, and a
        // tombstone without a name would be useless (the name is what lets a
        // replacement be recognised). See CipherLapidi.
        CipherLapidi.ricorda(this, improntaDi(peer), peer.label.orEmpty())
        if (CipherCore.nativeForgetPeer(peer.key) != CipherCore.OK) {
            toast(R.string.cipher_unavailable)
            return
        }
        // To disk immediately: an unpersisted keyring would bring the contact back
        // on restart.
        if (!CipherIdentity.persistKeyring(this)) toast(R.string.cipher_keyring_not_saved)
        toast(R.string.cipher_forgotten)
        ricarica()
    }

    /**
     * The burn blob to the clipboard, to paste into the chat.
     *
     * It is not plaintext (it is an encrypted blob like any other), so the
     * plaintext treatment is not needed here.
     */
    private fun copiaNegliAppunti(blob: String) {
        val clip = ClipData.newPlainText(null, blob)
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }

    // ========================================================================
    // Files
    // ========================================================================

    /**
     * Sends an encrypted file to this contact.
     *
     * The recipient is chosen **here**, explicitly (decision G4): this path starts
     * from a screen, not the keyboard, so there is no originating app to infer it
     * from, and a file sent to the wrong person cannot be withdrawn.
     */
    private fun inviaFile(peer: Peer) {
        dialogo = Dialogo.Nessuno
        destinatarioFile = peer.key
        val tipi = if (soloMedia) arrayOf("image/*", "video/*") else arrayOf("*/*")
        if (runCatching { selettoreFile.launch(tipi) }.isFailure) {
            destinatarioFile = null
            toast(R.string.cipher_unavailable)
        }
    }

    private fun cifraEInvia(uri: Uri) {
        val peer = destinatarioFile ?: return
        destinatarioFile = null
        val sorgente = CipherFiles.describe(this, uri)
        val massimo = CipherFiles.maxBytes(this)
        // The limit is reported BEFORE encrypting. Finding out afterwards would
        // make the user wait and then fail, and on a low-memory phone fail by
        // killing the process.
        if (sorgente.size > massimo) {
            toast(getString(R.string.cipher_file_too_big, massimo / (1024 * 1024)))
            return
        }
        val intent = CipherFiles.shareIntent(this, peer, uri, System.currentTimeMillis() / 1000)
        if (intent == null) {
            // NOT "too large": size was already checked above, and naming the wrong
            // cause sends the user looking in the wrong place.
            toast(R.string.cipher_file_not_prepared)
            return
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.cipher_file_send)))
        }.onFailure { toast(R.string.cipher_unavailable) }
    }

    // ========================================================================
    // Backup
    // ========================================================================

    private fun apriSelettore(esporta: Boolean) {
        // Storage Access Framework: the user picks the file and the app gains no
        // storage permission. A read permission on the whole disk to save one file
        // would be disproportionate.
        val esito = runCatching {
            if (esporta) selettoreEsporta.launch("identita-tastiera.kcb")
            else selettoreImporta.launch(arrayOf("*/*"))
        }
        if (esito.isFailure) {
            azzeraPassphrase()
            toast(R.string.cipher_unavailable)
        }
    }

    private fun esporta(uri: Uri) {
        val pass = passphraseInAttesa
        if (pass == null) {
            toast(R.string.cipher_unavailable)
            return
        }
        try {
            val blob = CipherIdentity.exportBackup(pass)
            if (blob == null) {
                toast(R.string.cipher_unavailable)
                return
            }
            val scritto = runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(blob) } != null
            }.getOrDefault(false)
            toast(if (scritto) R.string.cipher_backup_esportato else R.string.cipher_unavailable)
        } finally {
            azzeraPassphrase()
        }
    }

    private fun importa(uri: Uri) {
        val pass = passphraseInAttesa
        if (pass == null) {
            toast(R.string.cipher_unavailable)
            return
        }
        val blob = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (blob == null) {
            azzeraPassphrase()
            toast(R.string.cipher_backup_illeggibile)
            return
        }
        // A **copy** for the dialog, then the original is zeroized immediately.
        //
        // Sharing the same array between the dialog and `passphraseInAttesa`
        // gave two owners deciding when to destroy one object; the import failed
        // whenever one zeroized first. With a copy the lifetimes are separate:
        // `eseguiImport` zeroizes this one when done, the original goes here.
        dialogo = Dialogo.ConfermaImport(blob, pass.copyOf())
        azzeraPassphrase()
    }

    private fun eseguiImport(blob: ByteArray, pass: ByteArray) {
        val esito = CipherIdentity.importBackup(this, blob, pass)
        // `pass` is the dialog's copy and its owner (this function) zeroizes it.
        // `azzeraPassphrase` covers the original, already gone at this point:
        // calling it anyway costs nothing.
        pass.fill(0)
        azzeraPassphrase()
        if (esito == EsitoImport.Riuscito) {
            // ## "Imported" is not enough
            //
            // A backup does not prove where it comes from (see [ImportFatto]). It
            // cannot be prevented, but it can be made VISIBLE: the fingerprint and
            // the number of verified contacts are shown.
            val impronta = CipherCore.nativeMyFingerprint().orEmpty()
            val verificati = CipherCore.nativeListPeers()
                ?.let { PeerList.parse(it) }
                ?.count { it.verified }
                ?: 0
            dialogo = Dialogo.ImportFatto(impronta, verificati)
            ricarica()
        } else {
            // Wrong passphrase and tampered file give the same message:
            // distinguishing them would tell a guesser when they got it right. See
            // EsitoImport for the other causes.
            val messaggio = when (esito) {
                EsitoImport.Passphrase -> getString(R.string.cipher_backup_passphrase_errata)
                EsitoImport.NonBackup -> getString(R.string.cipher_backup_non_valido)
                EsitoImport.Versione -> getString(R.string.cipher_backup_versione)
                EsitoImport.Contenuto -> getString(R.string.cipher_backup_contenuto)
                EsitoImport.Keystore -> getString(R.string.cipher_backup_keystore)
                EsitoImport.Storage -> getString(R.string.cipher_backup_storage)
                EsitoImport.NonDisponibile -> getString(R.string.cipher_unavailable)
                is EsitoImport.Interno -> getString(R.string.cipher_backup_errore_codice, esito.codice)
                EsitoImport.Riuscito -> return
            }
            toast(messaggio)
        }
    }

    private fun azzeraPassphrase() {
        passphraseInAttesa?.fill(0)
        passphraseInAttesa = null
    }

    override fun onDestroy() {
        // If the user leaves halfway, the passphrase must not stay on the heap
        // waiting for GC.
        azzeraPassphrase()
        super.onDestroy()
    }

    // ========================================================================
    // Unreadable identity
    // ========================================================================

    /**
     * The only way out of [CipherState.Unreadable], here and not in
     * `DecryptActivity` on purpose: there it would appear in front of a user just
     * trying to read a message, and be pressed to get rid of the error.
     */
    @Composable
    private fun NonLeggibile(part: CipherPart) {
        Titolo(stringResource(R.string.cipher_unreadable_title))
        Riquadro {
            Text(
                text = stringResource(
                    if (part == CipherPart.IDENTITY) R.string.cipher_unreadable_identity
                    else R.string.cipher_unreadable_keyring
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp),
            )
            Divisore()
            Voce(stringResource(R.string.cipher_reset_identity), distruttiva = true) {
                dialogo = Dialogo.Reset
            }
        }
    }

    // ========================================================================
    // Utilities
    // ========================================================================

    private fun ricarica() {
        dialogo = Dialogo.Nessuno
        revisione++
    }

    private fun fingerprintOf(peer: Peer): String =
        CipherCore.nativeFingerprintOf(peer.key).orEmpty()

    private fun formatDate(unix: Long): String =
        DateFormat.getDateFormat(this).format(Date(unix * 1000))

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Which dialog is open.
     *
     * One state instead of a handful of booleans: two dialogs open at once is not
     * representable, and closing one by opening another is an explicit step.
     */
    private sealed interface Dialogo {
        data object Nessuno : Dialogo
        data class Scheda(val peer: Peer) : Dialogo
        data class Nome(val peer: Peer) : Dialogo
        data class Verifica(val peer: Peer) : Dialogo
        data class Rogo(val peer: Peer) : Dialogo
        data class Oblio(val peer: Peer) : Dialogo

        /** Same name, a key different from one you had forgotten. */
        data class Sostituzione(
            val peer: Peer,
            val nome: String,
            val nomeVecchio: String,
        ) : Dialogo
        data object Qr : Dialogo
        data class Passphrase(val esporta: Boolean) : Dialogo
        data object Reset : Dialogo

        // Plain classes, not `data`: they carry arrays, and a `data class` with a
        // `ByteArray` generates an `equals` comparing references.
        class Conflitto(
            val peer: Peer,
            val nome: String,
            val chiaveEsistente: ByteArray,
            val improntaEsistente: String,
            val improntaNuova: String,
        ) : Dialogo

        class ConfermaImport(val blob: ByteArray, val pass: ByteArray) : Dialogo

        /**
         * What was adopted. Not a receipt: a backup does not prove where it comes
         * from, so this screen is the only moment the user can notice having
         * adopted someone else's state.
         */
        data class ImportFatto(val impronta: String, val verificati: Int) : Dialogo
    }

    companion object {
        private const val EXTRA_DISTRUGGI = "cipher_distruggi"
        private const val EXTRA_ALLEGATO = "cipher_allegato"
        private const val EXTRA_SOLO_MEDIA = "cipher_solo_media"
        private const val EXTRA_NOMINA = "cipher_nomina"

        /**
         * The screen in "choose whom to send the file to" mode.
         *
         * An extra rather than a new activity: same list, same `FLAG_SECURE`, same
         * keyring.
         */
        fun intentAllegato(context: android.content.Context, soloMedia: Boolean): Intent =
            Intent(context, ContactsActivity::class.java).apply {
                putExtra(EXTRA_ALLEGATO, true)
                putExtra(EXTRA_SOLO_MEDIA, soloMedia)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        fun intent(context: android.content.Context): Intent =
            Intent(context, ContactsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        /** The list with the full-destruction confirmation already on screen. */
        fun intentDistruggi(context: android.content.Context): Intent =
            intent(context).apply { putExtra(EXTRA_DISTRUGGI, true) }

        /** The list with the "give a name" dialog already open on [peer]. */
        fun intentNomina(context: android.content.Context, peer: ByteArray): Intent =
            intent(context).apply { putExtra(EXTRA_NOMINA, peer) }
    }
}

/**
 * Preview of the screen, with fake data.
 *
 * The real screen has `FLAG_SECURE`, so it cannot be captured (the capture is
 * black, as intended). This preview is the way to look at it.
 *
 * The fingerprints here are made up and come from no keyring.
 *
 * Not `private` because `PreviewActivity` looks it up by name via reflection,
 * and the `debug` variant goes through R8: without a reference it would be
 * removed.
 */
@Preview
@Composable
internal fun AnteprimaContatti() {
    // The device theme rather than a fixed one: both light and dark need
    // checking.
    Theme {
        Surface {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Titolo("La tua identita'")
                Riquadro {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                        Impronta("ybnd rfg8 ejkm cpqx ot1u wisz", selezionabile = true)
                        Didascalia(
                            "Questo codice ti identifica. Leggilo a voce a chi vuoi contattare: " +
                                "se combacia, nessuno si e' interposto.",
                            Modifier.padding(top = 6.dp),
                        )
                    }
                    Divisore()
                    Voce("Mostra il codice QR") { }
                    Divisore()
                    Voce("Salva una copia dell'identita'") { }
                    Divisore()
                    Voce("Ripristina da una copia") { }
                }
                Titolo("Contatti")
                Riquadro {
                    Contatto("Giulia ✓", "8ejk mcpq xot1 uwis zybn drfg", "Visto la prima volta il 3 mar 2026") { }
                    Divisore()
                    Contatto("Senza nome", "qxot 1uwi szyb ndrf g8ej kmcp", "Visto la prima volta il 11 ago 2026") { }
                }
                Titolo("Azioni sul contatto")
                Riquadro {
                    Voce("Dai un nome") { }
                    Divisore()
                    Voce("Brucia la conversazione", distruttiva = true) { }
                    Divisore()
                    Vuoto("Nessun contatto. Ne comparira' uno appena ricevi un messaggio cifrato.")
                }
                Avviso("Cifratura non disponibile")
            }
        }
    }
}
