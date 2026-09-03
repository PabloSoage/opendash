package com.varuna.opendash.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.LifecycleService
import com.varuna.opendash.MainActivity
import com.varuna.opendash.R
import com.varuna.opendash.Session
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
        val port = intent?.getIntExtra(EXTRA_PORT, 35000) ?: 35000
        val host = intent?.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"

        // The notification comes first. Android gives a service started into
        // the foreground a few seconds to show one and kills the process if it
        // does not, and this call is also where a missing permission surfaces —
        // as an exception, not a return code. Reported rather than thrown: a
        // bridge that will not start is a message, not a crash.
        try {
            startForeground(NOTIFICATION_ID, notification(port))
        } catch (e: Exception) {
            Session.noteBridgeFault(e.message ?: e.javaClass.simpleName)
            stopSelf()
            return START_NOT_STICKY
        }

        val s = server ?: ElmServer(Sm3Bridge(), port, host).also { server = it }
        try {
            s.start()
        } catch (e: Exception) {
            Session.noteBridgeFault(e.message ?: e.javaClass.simpleName)
            server = null
            stopSelf()
            return START_NOT_STICKY
        }

        Session.noteBridgeUp(s.port)

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
        Session.noteBridgeDown()
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
            Session.State.CHANNEL_OPEN -> getString(R.string.link_channel_open)
            Session.State.CONNECTED -> getString(R.string.link_connected)
            Session.State.CONNECTING -> getString(R.string.link_connecting)
            Session.State.DISCONNECTED -> getString(R.string.link_disconnected)
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
        private const val EXTRA_PORT = "port"
        private const val EXTRA_HOST = "host"
        private const val NOTIFICATION_ID = 1

        /** [port] and [host] define where the phone app connects; the notification shows it back. */
        fun start(context: Context, port: Int = 35000, host: String = "127.0.0.1") {
            context.startForegroundService(
                Intent(context, BridgeService::class.java)
                    .putExtra(EXTRA_PORT, port)
                    .putExtra(EXTRA_HOST, host)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }
}
