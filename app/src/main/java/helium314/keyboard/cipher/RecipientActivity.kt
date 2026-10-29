package helium314.keyboard.cipher

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Theme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import helium314.keyboard.settings.dialogs.TextInputDialog
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog

/**
 * "Who am I writing to?": explicit recipient choice.
 *
 * ## Why it is needed
 *
 * The recipient is set in three ways: by decrypting (whoever reads and then
 * replies has chosen by reading), by per-app memory, or explicitly. Without the
 * third there was an easy dead end: you pin someone's key from their identity
 * card, go to the chat, press the lock, and nothing happens, because **that
 * app** never had a recipient.
 *
 * ## Why an activity rather than a keyboard menu
 *
 * It shows fingerprints, which must not end up in the Recents thumbnail: it
 * needs `FLAG_SECURE`, which an IME window does not have. In exchange focus on
 * the field is lost, which is why this screen **does not encrypt**: it only
 * chooses, then the user goes back and presses the lock.
 *
 * ## From which app
 *
 * From the [CipherHandoff] token, as for [DecryptActivity]: the only
 * attribution an external app cannot forge. Without a token nothing is chosen:
 * attributing the choice to the wrong app would redirect whom you encrypt for.
 *
 * ## Appearance
 *
 * The same dialog as the contact card, with the same components. Hence the
 * transparent window: [ThreeButtonAlertDialog] draws its own frame, and a dialog
 * theme underneath would add a second one.
 */
internal class RecipientActivity : ComponentActivity() {

    private var appDiProvenienza: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        // Same switch as the other screens: contact names are shown here, i.e.
        // whom you talk to.
        if (CipherSettings.isBlockScreenshot(this)) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        super.onCreate(savedInstanceState)

        appDiProvenienza = CipherHandoff
            .consume(intent.getStringExtra(CipherHandoff.extraName()))
            .orEmpty()

        // Without a token the keyring is not even read: we would not know which
        // app we are choosing for.
        val stato = if (appDiProvenienza.isEmpty()) null else CipherIdentity.ensureReady(this)

        setContent {
            Theme {
                when (stato) {
                    CipherState.Ready -> Scelta()
                    CipherState.Locked -> Avviso(stringResource(R.string.cipher_locked))
                    else -> Avviso(stringResource(R.string.cipher_unavailable))
                }
            }
        }
    }

    @Composable
    private fun Scelta() {
        val peers = remember {
            // Sorted by recent use: people you write to often are at the top. No
            // preselection (see CipherUsage): the order does not claim who the
            // recipient is.
            CipherUsage.ordinati(
                this,
                CipherCore.nativeListPeers()?.let { PeerList.parse(it) }.orEmpty(),
            )
        }
        var revisione by remember { mutableIntStateOf(0) }
        val gruppi = remember(revisione) { CipherGroups.tutti(this) }
        var multiplo by remember { mutableStateOf(false) }
        val scelti = remember { mutableStateListOf<Int>() }
        var chiediNome by remember { mutableStateOf(false) }

        val senzaNome = stringResource(R.string.cipher_unnamed_peer)
        val righe = peers.map { peer ->
            val nome = peer.label ?: senzaNome
            VoceDestinatario(
                nome = if (peer.verified) stringResource(R.string.cipher_sender_verified, nome) else nome,
                impronta = CipherCore.nativeFingerprintOf(peer.key).orEmpty(),
            )
        }

        if (chiediNome) {
            TextInputDialog(
                onDismissRequest = { chiediNome = false },
                onConfirmed = { nome -> salvaGruppo(nome, scelti.map { peers[it].key }) },
                title = { Text(stringResource(R.string.cipher_group_name_title)) },
                description = { Text(stringResource(R.string.cipher_group_name_hint)) },
                checkTextValid = { it.isNotBlank() },
            )
            return
        }

        SceltaDestinatario(
            righe = righe,
            gruppi = gruppi.map {
                stringResource(R.string.cipher_recipient_group, it.nome, it.membri.size)
            },
            multiplo = multiplo,
            scelti = scelti.toSet(),
            // Do NOT close if we are moving on to the group name.
            // `ThreeButtonAlertDialog` calls `onConfirmed()` and RIGHT AFTER
            // `onDismissRequest()` on the same tap: without this check "Next"
            // closed the activity before the name dialog could appear, and groups
            // could not be saved at all.
            onChiudi = { if (!chiediNome) finish() },
            onScegli = { indice ->
                if (multiplo) {
                    // The cap applies HERE, not when saving: letting ten names be
                    // ticked and then refusing everything would mean redoing the
                    // selection. Removing is always possible, even when full.
                    if (!scelti.remove(indice) && scelti.size < CipherGroups.MAX_MEMBRI) {
                        scelti.add(indice)
                    }
                } else {
                    scegli(peers[indice])
                }
            },
            onScegliGruppo = { indice -> scegliGruppo(gruppi[indice].nome) },
            onDimenticaGruppo = { indice ->
                CipherGroups.dimentica(this, gruppi[indice].nome)
                revisione++
            },
            onMultiplo = {
                multiplo = true
                scelti.clear()
            },
            onConferma = { chiediNome = true },
        )
    }

    /**
     * Saves the group and selects it right away as recipient.
     *
     * Selecting it right away is the point: someone who just ticked three people
     * and named them wants to write to those three, not choose them again.
     */
    private fun salvaGruppo(nome: String, membri: List<ByteArray>) {
        if (!CipherGroups.salva(this, nome, membri)) {
            Toast.makeText(this, R.string.cipher_group_not_saved, Toast.LENGTH_SHORT).show()
            return
        }
        scegliGruppo(nome)
    }

    private fun scegliGruppo(nome: String) {
        if (!CipherGroups.scegli(this, appDiProvenienza, nome)) {
            Toast.makeText(this, R.string.cipher_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        finish()
    }

    @Composable
    private fun Avviso(testo: String) {
        ThreeButtonAlertDialog(
            onDismissRequest = { finish() },
            onConfirmed = { },
            confirmButtonText = null,
            cancelButtonText = stringResource(android.R.string.ok),
            reducePadding = true,
            content = { Text(testo) },
        )
    }

    private fun scegli(peer: Peer) {
        if (CipherCore.nativeSetCurrentPeer(appDiProvenienza, peer.key) != CipherCore.OK) {
            Toast.makeText(this, R.string.cipher_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        // To disk, otherwise the choice dies at the first service restart.
        // `remember` clears the group by itself; here only the outcome is
        // checked. If it fails, the choice just made does NOT apply, and saying
        // nothing would let the message go to the group anyway.
        if (!CipherRecipients.remember(this, appDiProvenienza, peer.key)) {
            Toast.makeText(this, R.string.cipher_group_still_selected, Toast.LENGTH_LONG).show()
            return
        }
        CipherUsage.nota(this, peer.key)
        // No notice: "you now encrypt for this contact in this app" would not be
        // true, since the last message decrypted in that app also sets the current
        // recipient.
        finish()
    }

    companion object {
        /** Ready-made intent, with the token saying which app we come from. */
        fun intent(context: android.content.Context, appPackage: String): Intent =
            Intent(context, RecipientActivity::class.java).apply {
                putExtra(CipherHandoff.extraName(), CipherHandoff.issue(appPackage))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}

/** A row of the list, ready to be shown. */
internal class VoceDestinatario(val nome: String, val impronta: String)

/**
 * The dialog, knowing nothing about the keyring.
 *
 * Outside the activity so that [AnteprimaDestinatario] can exist: this window
 * has `FLAG_SECURE`, so captures come out blank.
 */
@Composable
private fun SceltaDestinatario(
    righe: List<VoceDestinatario>,
    onChiudi: () -> Unit,
    onScegli: (Int) -> Unit,
    gruppi: List<String> = emptyList(),
    multiplo: Boolean = false,
    scelti: Set<Int> = emptySet(),
    onScegliGruppo: (Int) -> Unit = {},
    onDimenticaGruppo: (Int) -> Unit = {},
    onMultiplo: () -> Unit = {},
    onConferma: () -> Unit = {},
) {
    ThreeButtonAlertDialog(
        onDismissRequest = onChiudi,
        onConfirmed = onConferma,
        // With single choice there is nothing to confirm: the choice is the tap on
        // the name. With multiple selection the button is needed, because the
        // taps are many and the user decides when they are done.
        confirmButtonText = if (multiplo && scelti.size >= 2) {
            stringResource(R.string.cipher_group_save)
        } else {
            null
        },
        cancelButtonText = stringResource(android.R.string.cancel),
        scrollContent = true,
        reducePadding = true,
        title = { Text(stringResource(R.string.cipher_pick_recipient)) },
        content = {
            Column {
                if (righe.isEmpty()) {
                    // Not an error: the first start. Someone without contacts yet
                    // must know what to do, not read "empty".
                    CompositionLocalProvider(
                        LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Text(
                            text = stringResource(R.string.cipher_pick_no_contacts),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                } else {
                    // Groups at the top, and only outside multiple selection: while
                    // building a group, choosing another would discard the selection.
                    if (!multiplo) {
                        gruppi.forEachIndexed { indice, nome ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onScegliGruppo(indice) }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = nome,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { onDimenticaGruppo(indice) }) {
                                    Text(stringResource(R.string.cipher_group_forget))
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        TextButton(onClick = onMultiplo) {
                            Text(stringResource(R.string.cipher_group_new))
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    } else {
                        // Say what is happening: otherwise a screen with ticks instead
                        // of a direct choice looks like the old one, broken.
                        CompositionLocalProvider(
                            LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            Text(
                                text = stringResource(R.string.cipher_group_pick_members),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    righe.forEachIndexed { indice, riga ->
                        if (indice > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onScegli(indice) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = if (multiplo && indice in scelti) "✓  ${riga.nome}" else riga.nome,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            // The fingerprint under the name, always: two unnamed
                            // contacts can only be told apart by it, and it is what
                            // is compared in person. Not selectable, or it would take
                            // the tap meant for the row.
                            Text(
                                text = riga.impronta,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        },
    )
}

/** See the note on [SceltaDestinatario]. The fingerprints here are made up. */
@Preview
@Composable
internal fun AnteprimaDestinatario() {
    Theme {
        Surface {
            Spacer(Modifier.height(240.dp))
            SceltaDestinatario(
                righe = listOf(
                    VoceDestinatario("Giulia ✓", "8ejk mcpq xot1 uwis zybn drfg"),
                    VoceDestinatario("Senza nome", "qxot 1uwi szyb ndrf g8ej kmcp"),
                ),
                onChiudi = { },
                onScegli = { },
            )
        }
    }
}
