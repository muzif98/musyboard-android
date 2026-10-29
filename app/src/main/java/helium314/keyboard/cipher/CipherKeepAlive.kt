package helium314.keyboard.cipher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.IBinder
import androidx.core.app.NotificationCompat
import helium314.keyboard.latin.R

/**
 * A foreground service whose only purpose is to **exist**.
 *
 * ## Why
 *
 * Recognising a blob on the clipboard lives in `ClipboardHistoryManager`, i.e.
 * in the keyboard process. If that process is gone, copying does nothing, and
 * there is no way to notice: **Android has no clipboard event**. No manifest
 * broadcast, no observer, no permission that unlocks one. The only hook is
 * `OnPrimaryClipChangedListener`, which works only while the process is alive
 * and registered.
 *
 * The keyboard is usually stopped by the vendor battery manager, which performs
 * a real force-stop (the package becomes `stopped=true` and the system even
 * falls back to another default keyboard). A foreground service is the one thing
 * those managers usually respect, because it has a visible notification.
 *
 * ## What it does NOT do
 *
 * No loops, no CPU wakeups, no wakelocks, no clipboard reads, no network.
 * `onStartCommand` posts the notification and returns. The useful work is the
 * process staying up, so the listener registered by the IME stays registered.
 *
 * ## The declared price
 *
 * A permanent status bar notification and the permission to show it. Hence
 * **off by default**, and the settings entry suggests first removing the battery
 * restriction, which achieves the same for free.
 */
class CipherKeepAlive : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Watches for changes of the default keyboard.
     *
     * Without it the notification's status line would be blind in the worst
     * case: losing default status, `LatinIME.onCreate` no longer runs and the
     * clipboard listener no longer fires, so nothing would update the text,
     * which would keep saying "the keyboard stays alive" forever.
     *
     * An observer rather than polling: it is an event, and this service must
     * keep doing no work.
     */
    private val osservatore = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            runCatching { mostraNotifica() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
                false,
                osservatore,
            )
        }
    }

    override fun onDestroy() {
        runCatching { contentResolver.unregisterContentObserver(osservatore) }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The user may have turned the switch off while the system restarted us:
        // then get out of the way instead of reappearing.
        if (!CipherSettings.isKeepAlive(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching { mostraNotifica() }.onFailure { stopSelf() }
        // START_STICKY: if the system kills us for memory we want to come back,
        // which is the point of the service.
        return START_STICKY
    }

    private fun mostraNotifica() {
        creaCanale()
        // The text states the STATUS, not a fixed reassurance.
        //
        // Keeping the process alive is not enough to watch the clipboard: that
        // permission comes from being the **default keyboard package**, not from
        // a running process. Default status can be lost (a battery force-stop
        // and the system falls back to another keyboard), while the service
        // restarts anyway (`START_STICKY`). Without this check the notification
        // would claim the keyboard is watching while it is not.
        val stato = if (sonoLaPredefinita()) {
            getString(R.string.cipher_keep_alive_notice_text)
        } else {
            getString(R.string.cipher_keep_alive_not_default)
        }
        val copia = when (ultimaCopiaEraNostra) {
            null -> getString(R.string.cipher_keep_alive_none)
            true -> getString(R.string.cipher_keep_alive_blob)
            false -> getString(R.string.cipher_keep_alive_other)
        }
        val testo = "$stato\n$copia"
        val notifica = NotificationCompat.Builder(this, CANALE)
            .setSmallIcon(R.drawable.ic_cipher_encrypt)
            .setContentTitle(getString(R.string.cipher_keep_alive_notice))
            .setContentText(testo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(testo))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            // Nothing confidential to hide (the notification says nothing beyond
            // its existence), but it stays off the lock screen to avoid clutter.
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID, notifica, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notifica)
        }
    }

    /**
     * Are we the default keyboard right now?
     *
     * Read from secure settings, readable without permissions. Compared by
     * package rather than component: clipboard access is per package.
     */
    private fun sonoLaPredefinita(): Boolean = runCatching {
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
    }.getOrDefault(false)

    private fun creaCanale() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CANALE) != null) return
        val canale = NotificationChannel(
            CANALE,
            getString(R.string.cipher_keep_alive_channel),
            // MIN: the notification must exist, not stand out. It is a side effect
            // of the mechanism, not a message for the user.
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        runCatching { manager.createNotificationChannel(canale) }
    }

    companion object {
        private const val CANALE = "cipher_keep_alive"

        /**
         * Result of the last copy seen, for the notification status line. `null`
         * until the listener has seen anything: that `null` tells "does not fire"
         * from "fires but does not recognise".
         */
        @Volatile
        private var ultimaCopiaEraNostra: Boolean? = null

        /** Called by the clipboard path for every text that passes. */
        fun segnalaCopia(context: Context, eraNostra: Boolean) {
            ultimaCopiaEraNostra = eraNostra
            aggiorna(context)
        }
        private const val ID = 4712

        /**
         * Aligns the service with the preference. Can be called any number of
         * times: starting an already running service goes through
         * `onStartCommand` again and duplicates nothing.
         *
         * Silent if the system refuses to start it: since Android 12 a foreground
         * service cannot be started from the background, and this call also
         * comes from background paths. Turning the switch on happens from a
         * visible screen, the case that matters.
         */
        fun aggiorna(context: Context) {
            val intent = Intent(context.applicationContext, CipherKeepAlive::class.java)
            if (CipherSettings.isKeepAlive(context)) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.applicationContext.startForegroundService(intent)
                    } else {
                        context.applicationContext.startService(intent)
                    }
                }
            } else {
                runCatching { context.applicationContext.stopService(intent) }
            }
        }
    }
}
