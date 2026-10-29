// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.cipher.CipherFermata
import helium314.keyboard.cipher.CipherKeepAlive
import helium314.keyboard.cipher.CipherSettings
import helium314.keyboard.cipher.ContactsActivity
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.dataChiusura
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SwitchPreference

/**
 * Everything about encryption in one place, so the feature that defines this
 * keyboard does not look like a configuration detail scattered across menus.
 */
@Composable
fun CipherScreen(
    onClickBack: () -> Unit,
) {
    val prefs = LocalContext.current.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val enabled = CipherSettings.isEnabled(prefs)
    val items = listOf(
        CipherSettings.PREF_ENABLED,
        // The other entries exist only when encryption is on: showing them off and
        // inert is the quickest way to make them look broken.
        // Only with the row active: without it auto-send has no moment to fire.
        if (enabled) CipherSettings.PREF_AUTO_SEND else null,
        // Same reason: without the row the plaintext is already in the app's
        // field, and a ban on copying it would protect nothing.
        if (enabled) CipherSettings.PREF_BLOCK_COPY else null,
        // NOT conditional on `enabled`: the decryption screen shows plaintext even
        // with the row off, so a switch disappearing with the row would hide a
        // protection still active.
        CipherSettings.PREF_BLOCK_SCREENSHOT,
        // Next to the copy ban because they answer the same question (where does
        // plaintext end up besides the app's field), and this is the invisible
        // path: the personal dictionary is part of Android's backup.
        if (enabled) CipherSettings.PREF_LEARN else null,
        if (enabled) CipherSettings.PREF_AUTO_OPEN else null,
        if (enabled) CipherSettings.PREF_FORWARD_SECRECY else null,
        // The two answers to "the phone stops the keyboard and copying no longer
        // does anything", in the right order: first the one that costs nothing.
        if (enabled) SettingsWithoutKey.CIPHER_BATTERY else null,
        if (enabled) CipherSettings.PREF_KEEP_ALIVE else null,
        if (enabled) SettingsWithoutKey.CIPHER_CONTACTS else null,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.cipher_settings_category),
        settings = items
    )
}

fun createCipherSettings(context: Context) = listOf(
    Setting(
        context, CipherSettings.PREF_ENABLED,
        R.string.cipher_enabled, R.string.cipher_enabled_summary
    ) {
        // setThemeNeedsReload rather than reloadKeyboard: toolbar keys are built
        // ONCE, when the suggestion strip is created, and reloadKeyboard rebuilds
        // the keyboard leaving the strip as it was (the new key appeared only
        // after switching keyboard and back).
        SwitchPreference(it, CipherSettings.DEFAULT_ENABLED) {
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(
        context, CipherSettings.PREF_AUTO_SEND,
        R.string.cipher_auto_send, R.string.cipher_auto_send_summary
    ) {
        SwitchPreference(it, CipherSettings.DEFAULT_AUTO_SEND)
    },
    Setting(
        context, CipherSettings.PREF_BLOCK_COPY,
        R.string.cipher_block_copy, R.string.cipher_block_copy_summary
    ) {
        SwitchPreference(it, CipherSettings.DEFAULT_BLOCK_COPY)
    },
    Setting(
        context, CipherSettings.PREF_BLOCK_SCREENSHOT,
        R.string.cipher_block_screenshot, R.string.cipher_block_screenshot_summary
    ) {
        SwitchPreference(it, CipherSettings.DEFAULT_BLOCK_SCREENSHOT)
    },
    Setting(
        context, CipherSettings.PREF_LEARN,
        R.string.cipher_learn, R.string.cipher_learn_summary
    ) {
        SwitchPreference(it, CipherSettings.DEFAULT_LEARN)
    },
    Setting(
        context, CipherSettings.PREF_AUTO_OPEN,
        R.string.cipher_auto_open, R.string.cipher_auto_open_summary
    ) {
        // The permission is for the fallback notification shown when the phone
        // refuses to open the screen by itself. Asked when turning the switch on,
        // not at the first copy: by then the keyboard is closed and an IME cannot
        // open a permission dialog. Refusing loses the fallback, not the feature.
        val chiediNotifiche = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }
        SwitchPreference(it, CipherSettings.DEFAULT_AUTO_OPEN) { acceso ->
            if (acceso && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { chiediNotifiche.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    },
    Setting(
        context, CipherSettings.PREF_FORWARD_SECRECY,
        R.string.cipher_forward_secrecy, R.string.cipher_forward_secrecy_summary
    ) {
        SwitchPreference(it, CipherSettings.DEFAULT_FORWARD_SECRECY)
    },
    Setting(
        context, SettingsWithoutKey.CIPHER_BATTERY,
        R.string.cipher_battery, R.string.cipher_battery_summary
    ) {
        val ctx = LocalContext.current
        val gia = CipherFermata.senzaRestrizioni(ctx)
        // The last forced stop stays listed here even after the keyboard was
        // reselected: the place where the user can check whether the phone really
        // stopped it.
        val fermata = CipherFermata.ultimeChiusure(ctx, 16)
            .firstOrNull { it.motivo == CipherFermata.Motivo.FERMATA }
        val stato = if (gia) stringResource(R.string.cipher_battery_ok) else it.description
        Preference(
            name = it.title,
            description = if (fermata == null) stato else
                stato + "\n" + stringResource(R.string.cipher_battery_last_stop, dataChiusura(ctx, fermata.quando)),
            onClick = { CipherFermata.apriBatteria(ctx) },
        ) { NextScreenIcon() }
    },
    Setting(
        context, CipherSettings.PREF_KEEP_ALIVE,
        R.string.cipher_keep_alive, R.string.cipher_keep_alive_summary
    ) {
        val ctx = LocalContext.current
        // The permission is asked when turning on, not at the first notification:
        // otherwise the service would start without its notification, which is
        // what makes battery managers respect it.
        val chiediNotifiche = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { CipherKeepAlive.aggiorna(ctx) }
        SwitchPreference(it, CipherSettings.DEFAULT_KEEP_ALIVE) { acceso ->
            if (acceso && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { chiediNotifiche.launch(Manifest.permission.POST_NOTIFICATIONS) }
            } else {
                CipherKeepAlive.aggiorna(ctx)
            }
        }
    },
    Setting(context, SettingsWithoutKey.CIPHER_CONTACTS, R.string.cipher_contacts) {
        // An activity rather than a Compose destination: ContactsActivity needs
        // FLAG_SECURE on its own window (it shows fingerprints, which must not end
        // up in Recents screenshots), and a destination inside the navigation
        // graph would share the window with all settings.
        val ctx = LocalContext.current
        Preference(
            name = it.title,
            onClick = { ctx.startActivity(Intent(ctx, ContactsActivity::class.java)) },
        ) { NextScreenIcon() }
    },
)
