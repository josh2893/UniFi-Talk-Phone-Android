package au.josh.unifiphone.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import au.josh.unifiphone.MainActivity
import au.josh.unifiphone.R

/**
 * Keeps the process (and the Linphone core) alive so SIP registration
 * survives the app being backgrounded / screen off on the kiosk handset.
 */
class SipForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "sip"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "SIP connection", NotificationManager.IMPORTANCE_MIN)
        )
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("UniFi Phone")
            .setContentText("SIP registration active")
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(1, notif)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, SipForegroundService::class.java))
        }
    }
}
