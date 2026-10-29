package helium314.keyboard.cipher

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.core.content.edit
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.latin.utils.prefs

/**
 * What can be known, and said, when the phone stops the keyboard.
 *
 * Many vendors' battery managers perform a real force-stop: the package becomes
 * `stopped` and the system falls back to another default keyboard. The user only
 * sees that "it closed by itself and the other one came back".
 *
 * It cannot be fixed from inside: reselecting ourselves as default needs a
 * system permission, and after a force-stop nothing runs until someone reopens
 * the app. Three things remain, all here: knowing whether the battery
 * restriction is present, noticing that the default slot was lost, and reading
 * from the system why the process died, so diagnosis comes from a screenshot
 * rather than a cable.
 *
 * No permissions: battery state and one's own exit reasons are readable without
 * asking.
 */
object CipherFermata {

    /**
     * True once the keyboard has served at least once as default.
     *
     * Tells "not chosen yet" from "chosen and no longer": otherwise the notice
     * would appear to someone still setting it up.
     */
    const val PREF_ERA_PREDEFINITA = "cipher_era_predefinita"

    /** From `LatinIME.onCreate`, which runs only when the system uses us as keyboard. */
    @JvmStatic
    fun segnaPredefinita(context: Context) {
        val prefs = context.prefs()
        if (!prefs.getBoolean(PREF_ERA_PREDEFINITA, false))
            prefs.edit { putBoolean(PREF_ERA_PREDEFINITA, true) }
    }

    /** We were the default keyboard and no longer are. */
    fun predefinitaPersa(context: Context): Boolean = runCatching {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        context.prefs().getBoolean(PREF_ERA_PREDEFINITA, false)
            && !UncachedInputMethodManagerUtils.isThisImeCurrent(context, imm)
    }.getOrDefault(false)

    /**
     * Is the app already exempt from battery optimisation?
     *
     * Also true before Android 6, where that restriction does not exist.
     */
    fun senzaRestrizioni(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    /**
     * The screens from which the restriction is removed, in order of preference.
     *
     * The state can be READ without permissions; asking through a dialog would
     * need one. So the system screen is opened: one more tap for the user, one
     * less permission in the manifest. Not every phone has the first; the second
     * is the app's page, from which battery settings are reachable.
     */
    fun intentBatteria(context: Context): List<Intent> = listOfNotNull(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        else null,
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ),
    )

    fun apriBatteria(context: Context) {
        for (intent in intentBatteria(context))
            if (runCatching { context.startActivity(intent) }.isSuccess) return
    }

    enum class Motivo { FERMATA, AGGIORNATA, MEMORIA, CRASH, ALTRO }

    /**
     * A process death, as the system reports it.
     *
     * `descrizione` is the system text ("stop <package> due to ..."): it says who
     * requested the stop, the piece needed to identify the battery manager.
     */
    data class Chiusura(val quando: Long, val motivo: Motivo, val codice: Int, val descrizione: String?)

    /**
     * Groups system reasons by what changes the answer.
     *
     * `FERMATA` is a forced stop: by the battery manager, but also by the user
     * pressing "Force stop" in settings. The system does not distinguish them,
     * and the text shown must not pretend to.
     */
    fun classifica(codice: Int): Motivo = when (codice) {
        ApplicationExitInfo.REASON_USER_REQUESTED,
        ApplicationExitInfo.REASON_USER_STOPPED -> Motivo.FERMATA
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> Motivo.AGGIORNATA
        ApplicationExitInfo.REASON_LOW_MEMORY -> Motivo.MEMORIA
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> Motivo.CRASH
        else -> Motivo.ALTRO
    }

    /**
     * The latest process deaths, newest first. Empty before Android 11, where the
     * system does not keep them.
     */
    fun ultimeChiusure(context: Context, quante: Int = 3): List<Chiusura> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getHistoricalProcessExitReasons(context.packageName, 0, quante).map {
                Chiusura(it.timestamp, classifica(it.reason), it.reason, it.description)
            }
        }.getOrDefault(emptyList())
    }
}
