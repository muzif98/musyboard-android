package helium314.keyboard.cipher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.utils.Theme

/**
 * The encryption UI pieces, in one place.
 *
 * The fork's three screens (contacts, recipient choice, decrypted text) show the
 * same things: names, fingerprints, tappable rows. Keeping the pieces here gives
 * them the same look by construction.
 *
 * None of them touches the keyring or knows what it shows: they take strings.
 * That is also what makes previews possible, since the real windows have
 * `FLAG_SECURE` and capture as blank.
 */

/** Section title, as in settings. */
@Composable
internal fun Titolo(testo: String) {
    Text(
        text = testo,
        color = MaterialTheme.colorScheme.secondary,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** The rounded container grouping a section. */
@Composable
internal fun Riquadro(contenuto: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { contenuto() }
    }
}

/** A tappable row, with the same dimensions as settings entries. */
@Composable
internal fun Voce(testo: String, distruttiva: Boolean = false, quando: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { quando() }
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = testo,
            style = MaterialTheme.typography.bodyLarge,
            color = if (distruttiva) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
    }
}

/** A contact list row: name, fingerprint, when it appeared. */
@Composable
internal fun Contatto(nome: String, impronta: String, visto: String?, quando: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { quando() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(text = nome, style = MaterialTheme.typography.bodyLarge)
        // Here the fingerprint is NOT selectable: selectable text consumes the
        // tap, and covering most of the row it would make the contact openable
        // only from the thin name strip.
        Impronta(impronta, selezionabile = false, Modifier.padding(top = 2.dp))
        if (visto != null) Didascalia(visto, Modifier.padding(top = 2.dp))
    }
}

@Composable
internal fun Divisore() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** A short message inside a box: states, errors, "nothing here". */
@Composable
internal fun Avviso(testo: String) {
    Riquadro {
        Text(
            text = testo,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
internal fun Vuoto(testo: String) {
    Didascalia(testo, Modifier.padding(12.dp))
}

@Composable
internal fun Didascalia(testo: String, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(text = testo, style = MaterialTheme.typography.bodySmall, modifier = modifier)
    }
}

/**
 * Monospaced: 24 characters two people read aloud or compare on screen, and a
 * proportional font makes that comparison harder than needed.
 *
 * @param selezionabile enable only where the row is not clickable: selectable
 * text consumes the tap.
 */
@Composable
internal fun Impronta(testo: String, selezionabile: Boolean, modifier: Modifier = Modifier) {
    val riga = @Composable {
        Text(
            text = testo,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier,
        )
    }
    if (selezionabile) SelectionContainer { riga() } else riga()
}

/**
 * The decrypted-text screens, with fake data.
 *
 * They are `FLAG_SECURE`, so captures come out blank and previews are the way
 * to look at them. Nothing here touches the keyring; text and fingerprints are
 * made up.
 */
@Preview
@Composable
internal fun AnteprimaDecifrato() {
    Theme {
        Surface {
            Column(Modifier.padding(12.dp)) {
                Titolo("Messaggio decifrato")
                Riquadro {
                    Column(Modifier.padding(12.dp)) {
                        Didascalia("Scritto il 13/08/2026 02:14")
                        Spacer(Modifier.height(10.dp))
                        Text("ci vediamo alle sette sotto casa", style = MaterialTheme.typography.bodyLarge)
                    }
                    Divisore()
                    Voce("Copia") { }
                    Divisore()
                    Voce("Contatti") { }
                }
                Titolo("Presentazione ricevuta")
                Riquadro {
                    Column(Modifier.padding(12.dp)) {
                        Impronta("8ejk mcpq xot1 uwis zybn drfg", selezionabile = false)
                        Spacer(Modifier.height(6.dp))
                        Didascalia("Questa chiave e' ora fra i tuoi contatti.")
                    }
                    Divisore()
                    Voce("Scrivi a questo contatto") { }
                }
                Titolo("Avviso")
                Avviso("Questo testo non e' cifrato")
            }
        }
    }
}
