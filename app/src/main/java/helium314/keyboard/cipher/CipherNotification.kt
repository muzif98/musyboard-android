package helium314.keyboard.cipher

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.R

/**
 * The last resort: a notification to tap, when the copied message was
 * recognised but the system does not let us open the screen.
 *
 * ## Why it exists
 *
 * When a blob is copied the keyboard tries to open [DecryptActivity]. Since
 * Android 10 an app without visible windows should not be able to, but on some
 * devices the default IME is allowed anyway. On others the launch is refused
 * **silently** (`startActivity` does not report it): the listener fires, the
 * blob is recognised, and nothing opens. This notification covers that case. The
 * permission is already declared for the keep-alive service.
 *
 * ## What does not go in here
 *
 * Title and text are fixed: no blob, no plaintext. A notification can be read by
 * anyone looking at the screen, and the status bar has none of the protections
 * [DecryptActivity] runs under. For the same reason `VISIBILITY_SECRET`: it does
 * not appear on the lock screen.
 *
 * The blob travels in the `PendingIntent` because it is encrypted and already on
 * the system clipboard. It stays in `system_server` memory as long as that
 * `PendingIntent` lives, which is why [dismiss] really cancels it.
 */
internal object CipherNotification {

    private const val CHANNEL = "cipher_clipboard"
    private const val ID = 4711
    private const val REQUEST = 4711

    /** Removed by itself after five minutes: the blob is on the clipboard anyway. */
    private const val TIMEOUT_MS = 5L * 60L * 1000L

    /**
     * The last `PendingIntent` posted, to really cancel it.
     *
     * `cancel()` on the notification removes it from the bar but leaves the
     * `PendingIntent` (and the blob in its extras) alive in `system_server`.
     */
    @Volatile
    private var pending: PendingIntent? = null

    // The permission is checked by [allowed], one line below. Lint does not follow
    // it out of the method and blocks the release build: the only reason for the
    // suppression.
    /**
     * @return whether the notification was posted. Without permission, or with
     *   notifications off, nothing is posted: the caller must know, otherwise the
     *   copied message opens in no way and nothing says so.
     */
    @SuppressLint("MissingPermission")
    fun offer(context: Context, blob: String): Boolean {
        if (!allowed(context)) return false
        ensureChannel(context)

        val intent = Intent(context, DecryptActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, blob)
            // No CipherHandoff token: there is no app being typed in, and a token
            // issued now would stay pending until the tap. DecryptActivity falls
            // back to "no app", the right way to fail: it asks instead of guessing.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        val tocco = PendingIntent.getActivity(context, REQUEST, intent, flags)
        pending = tocco

        val avviso = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_cipher_decrypt)
            .setContentTitle(context.getString(R.string.cipher_notif_title))
            .setContentText(context.getString(R.string.cipher_notif_text))
            .setContentIntent(tocco)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            // It must come to the front (otherwise "I copied and nothing happened"
            // stays true) but must not make a sound: a convenience, not an alarm.
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()

        return runCatching { NotificationManagerCompat.from(context).notify(ID, avviso) }.isSuccess
    }

    /**
     * Removes the notification and the blob it carried. Called by
     * [DecryptActivity] as soon as the message is on screen.
     */
    fun dismiss(context: Context) {
        pending?.cancel()
        pending = null
        runCatching { NotificationManagerCompat.from(context).cancel(ID) }
    }

    private fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return runCatching {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(false)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        val canale = NotificationChannel(
            CHANNEL,
            context.getString(R.string.cipher_notif_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.cipher_notif_channel_desc)
            setSound(null, null)
            enableVibration(false)
            // The badge would outlive the notification: the app would keep
            // "something to read" after the message was read.
            setShowBadge(false)
            lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
        }
        runCatching { manager.createNotificationChannel(canale) }
    }
}
