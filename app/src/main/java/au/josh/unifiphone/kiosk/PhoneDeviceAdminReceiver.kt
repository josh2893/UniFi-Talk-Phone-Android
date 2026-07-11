package au.josh.unifiphone.kiosk

import android.app.admin.DeviceAdminReceiver

/** Marker receiver; required for device-owner provisioning via adb dpm. */
class PhoneDeviceAdminReceiver : DeviceAdminReceiver()
