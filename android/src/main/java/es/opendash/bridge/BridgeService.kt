package es.opendash.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.LifecycleService
import es.opendash.R

/**
 * Keeps the ELM327 socket alive with the screen off.
 *
 * Diagnostics happen while driving, so the phone spends most of a session
 * locked in a pocket. A plain background thread would be killed; a foreground
 * service with a notification is the only thing Android leaves running.
 */
class BridgeService : LifecycleService() {

    private var server: ElmServer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val s = server ?: ElmServer(LoopbackBridge()).also { server = it }
        s.start()
        startForeground(NOTIFICATION_ID, notification(s.port))
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun notification(port: Int): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.bridge_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.bridge_notification_text, port))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
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
