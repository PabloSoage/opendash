package es.opendash.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.LifecycleService
import es.opendash.MainActivity
import es.opendash.R
import es.opendash.Session
import kotlin.concurrent.thread

/**
 * Keeps the ELM327 socket alive with the screen off.
 *
 * A session is spent driving with the phone locked in a pocket, and a plain
 * background thread would be killed within minutes. A foreground service with
 * an ongoing notification is the only thing Android leaves running, so the
 * notification is not decoration — it is what keeps the bridge up.
 *
 * It doubles as the status display: whether the link is open and how many
 * requests have gone through, without unlocking the phone.
 */
class BridgeService : LifecycleService() {

    private var server: ElmServer? = null
    private var ticker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val s = server ?: ElmServer(Sm3Bridge()).also { server = it }
        s.start()
        startForeground(NOTIFICATION_ID, notification(s.port))

        // Refresh the status line so the notification says something true
        // rather than whatever was the case when it started.
        if (ticker == null) {
            ticker = thread(name = "bridge-status", isDaemon = true) {
                while (server != null) {
                    Thread.sleep(2000)
                    val port = server?.port ?: break
                    manager().notify(NOTIFICATION_ID, notification(port))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        ticker = null
        super.onDestroy()
    }

    private fun manager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun notification(port: Int): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager().createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.bridge_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val state = when (Session.state) {
            Session.State.CHANNEL_OPEN -> getString(R.string.device_channel_open)
            Session.State.CONNECTED -> getString(R.string.device_connected, Session.serial)
            Session.State.CONNECTING -> getString(R.string.device_connecting)
            Session.State.DISCONNECTED -> getString(R.string.device_disconnected)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.bridge_notification_text, port, state))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "bridge"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BridgeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }
}
