package au.josh.unifiphone.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import au.josh.unifiphone.App
import au.josh.unifiphone.MainActivity
import au.josh.unifiphone.core.SipForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val pendingResult = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = app.settingsRepo.settings.first()
                if (settings.doorbellEnabled || settings.kioskEnabled) {
                    val activityIntent = Intent(app, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    app.startActivity(activityIntent)
                }
                // Some Android versions restrict phone-call foreground services at boot.
                // MainActivity starts it again after a kiosk/doorbell launch.
                runCatching { SipForegroundService.start(app) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
