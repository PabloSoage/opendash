package com.varuna.opendash.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import com.varuna.opendash.MainActivity
import com.varuna.opendash.R
import com.varuna.opendash.Session

/**
 * Keeps a live session alive while the phone is in a pocket.
 *
 * A recording ends when the car is switched off, not when the screen does, and
 * until now it ended when the screen did. Three separate things had to be true
 * for that, and none of them were:
 *
 * * **A foreground service.** Plain threads in a backgrounded process are
 *   frozen. The bridge already had one; the live session, which is the part
 *   that writes a file, had nothing.
 * * **A Wi-Fi lock.** Android powers the radio down when the screen goes off,
 *   and the adapter's access point is an ordinary Wi-Fi network as far as the
 *   phone is concerned. `WIFI_MODE_FULL_HIGH_PERF` is what keeps a socket to it
 *   open, and it is the difference between a twenty-minute drive recorded and a
 *   twenty-minute drive lost.
 * * **A wake lock.** Partial: the CPU stays up, the screen does not. The
 *   polling thread has to keep running to have anything to write.
 *
 * All three are released together when the run ends, because a Wi-Fi lock left
 * held is somebody's battery.
 */
class LiveService : LifecycleService() {

    private var wifi: WifiManager.WifiLock? = null
    private var wifiFallback: WifiManager.WifiLock? = null
    private var cpu: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // The Mark button on the notification. The service is already in the
        // foreground by then, so there is nothing else to do.
        if (intent?.action == ACTION_MARK) {
            runCatching { onMark?.invoke() }
            return START_STICKY
        }

        // First, because Android gives a service a few seconds to show a
        // notification and kills the process if it does not.
        try {
            startForeground(NOTIFICATION_ID, build(this, getString(R.string.live_notification_text), false))
        } catch (e: Exception) {
            Session.noteBridgeFault(e.message ?: e.javaClass.simpleName)
            stopSelf()
            return START_NOT_STICKY
        }

        if (wifi == null) {
            val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            // Low latency from Android 10, which is what replaced high
            // performance and is what this actually needs: a socket to an
            // adapter, kept responsive rather than merely awake. Below that,
            // high performance is the only one there is.
            @Suppress("DEPRECATION")
            val mode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
            wifi = manager.createWifiLock(mode, TAG).also {
                it.setReferenceCounted(false)
                runCatching { it.acquire() }
            }
            // And high performance beside it, from Android 10 on as well.
            // Low latency only takes effect while the screen is on and the app
            // is in front; with the phone locked in a pocket the system treats
            // it as no lock at all, and the radio goes back to power saving in
            // the middle of a drive. A second lock of the older kind costs
            // nothing where it still does something and nothing where it does
            // not.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                wifiFallback = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$TAG:fallback").also {
                    it.setReferenceCounted(false)
                    runCatching { it.acquire() }
                }
            }
        }
        if (cpu == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            cpu = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).also {
                it.setReferenceCounted(false)
                runCatching { it.acquire() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { wifi?.release() }
        runCatching { wifiFallback?.release() }
        runCatching { cpu?.release() }
        wifi = null
        wifiFallback = null
        cpu = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "live"
        private const val TAG = "opendash:live"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_MARK = "com.varuna.opendash.MARK"

        /**
         * What the Mark button does. Set by whoever is recording, cleared when
         * it stops; null means the button does nothing.
         */
        @Volatile
        var onMark: (() -> Unit)? = null

        /**
         * The notification, with what the run is doing in it.
         *
         * It used to be one fixed sentence, which says the service exists and
         * nothing about whether anything is being recorded. With the phone
         * locked in a mount, the notification is the only screen there is, and
         * a recording that stopped half an hour ago looked exactly like one
         * that was going. Now it carries the rows, the rate and the last thing
         * that went wrong, and a button that puts a mark in the file.
         */
        private fun build(context: Context, text: String, recording: Boolean): Notification {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        context.getString(R.string.live_notification_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
            }
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = Notification.Builder(context, CHANNEL)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
            if (recording) {
                val mark = PendingIntent.getService(
                    context,
                    1,
                    Intent(context, LiveService::class.java).setAction(ACTION_MARK),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                builder.addAction(
                    Notification.Action.Builder(
                        null,
                        context.getString(R.string.live_mark),
                        mark,
                    ).build()
                )
            }
            return builder.build()
        }

        /**
         * Replace the notification's text. Cheap, and never allowed to throw:
         * it is called from the thread that reads the module.
         */
        fun status(context: Context, text: String, recording: Boolean) {
            runCatching {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, build(context, text, recording))
            }
        }

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, LiveService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LiveService::class.java)) }
        }
    }
}
