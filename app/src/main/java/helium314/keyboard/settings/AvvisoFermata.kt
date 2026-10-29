// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import helium314.keyboard.cipher.CipherFermata
import helium314.keyboard.latin.R

fun dataChiusura(context: Context, quando: Long): String = DateUtils.formatDateTime(
    context, quando,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL
)

/**
 * The notice for whoever reopens the app after the phone stopped the keyboard.
 *
 * Says what happened and what to do, and below lists the latest closures as the
 * system reports them: the part to screenshot and send to whoever needs to
 * understand which phone does what, without connecting anything.
 *
 * The two buttons are optional because in the wizard those actions are the steps
 * themselves. In the wizard the notice is also `compatto`, one closure only, so
 * the steps below stay on screen.
 */
@Composable
fun AvvisoFermata(
    modifier: Modifier = Modifier,
    onRiseleziona: (() -> Unit)? = null,
    onBatteria: (() -> Unit)? = null,
    compatto: Boolean = false,
) {
    val ctx = LocalContext.current
    val chiusure = remember { CipherFermata.ultimeChiusure(ctx) }
    val fermata = chiusure.firstOrNull { it.motivo == CipherFermata.Motivo.FERMATA }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.cipher_lost_title, stringResource(ctx.applicationInfo.labelRes)),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (fermata != null)
                    stringResource(R.string.cipher_lost_text_stopped, dataChiusura(ctx, fermata.quando))
                else stringResource(R.string.cipher_lost_text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (chiusure.isNotEmpty()) {
                Text(
                    stringResource(R.string.cipher_stop_list),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                for (c in if (compatto) listOf(fermata ?: chiusure.first()) else chiusure) {
                    val motivo = stringResource(when (c.motivo) {
                        CipherFermata.Motivo.FERMATA -> R.string.cipher_stop_forced
                        CipherFermata.Motivo.AGGIORNATA -> R.string.cipher_stop_updated
                        CipherFermata.Motivo.MEMORIA -> R.string.cipher_stop_memory
                        CipherFermata.Motivo.CRASH -> R.string.cipher_stop_crash
                        CipherFermata.Motivo.ALTRO -> R.string.cipher_stop_other
                    })
                    Text(
                        listOfNotNull(dataChiusura(ctx, c.quando), "$motivo (${c.codice})", c.descrizione?.takeIf { it.isNotBlank() })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            if (onRiseleziona != null || onBatteria != null)
                FlowRow(Modifier.padding(top = 4.dp)) {
                    if (onRiseleziona != null)
                        TextButton(onClick = onRiseleziona) { Text(stringResource(R.string.cipher_lost_reselect)) }
                    if (onBatteria != null)
                        TextButton(onClick = onBatteria) { Text(stringResource(R.string.cipher_lost_battery)) }
                }
        }
    }
}
