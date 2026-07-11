package au.josh.unifiphone.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * Single-app kiosk via Android Lock Task Mode.
 *
 * Full (unescapable) kiosk requires the app to be Device Owner:
 *   adb shell dpm set-device-owner au.josh.unifiphone/.kiosk.PhoneDeviceAdminReceiver
 * (device must have no Google/other accounts added yet — factory reset,
 * skip account setup, sideload, run the command).
 *
 * Without device owner, startLockTask() falls back to screen pinning,
 * which the user can exit with a button hold — fine for testing.
 */
object KioskManager {

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    fun enterKiosk(activity: Activity) {
        val dpm = activity.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(activity, PhoneDeviceAdminReceiver::class.java)
        if (dpm.isDeviceOwnerApp(activity.packageName)) {
            dpm.setLockTaskPackages(admin, arrayOf(activity.packageName))
            // Allow system info bar (clock/battery) + keyguard off for a desk phone
            dpm.setLockTaskFeatures(
                admin,
                DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                    DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
            )
            dpm.setKeyguardDisabled(admin, true)
        }
        runCatching { activity.startLockTask() }
    }

    fun exitKiosk(activity: Activity) {
        runCatching { activity.stopLockTask() }
    }
}
