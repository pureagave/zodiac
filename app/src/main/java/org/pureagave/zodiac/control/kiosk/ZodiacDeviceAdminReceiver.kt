package org.pureagave.zodiac.control.kiosk

import android.app.admin.DeviceAdminReceiver

/**
 * The component `dpm set-device-owner` points at. Intentionally empty: we want
 * the *privilege* (lock task, keyguard, OTA control — see [KioskController]),
 * not any of the callbacks. Deleting or renaming this class breaks provisioning
 * on every already-provisioned tablet, and the only fix is a factory reset.
 */
class ZodiacDeviceAdminReceiver : DeviceAdminReceiver()
