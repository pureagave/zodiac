package org.pureagave.zodiac.control

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * A minimal foreground service whose only job is to keep the cockpit **process**
 * at foreground priority, so the NET receivers (`NetworkLocationSource`,
 * `NetworkThreatSource`, `NavShareReceiver`, `FleetVersionReceiver` — all owned
 * by [ZodiacApplication]'s process-lifetime scope) keep receiving fleet multicast
 * even when the Activity is backgrounded.
 *
 * **Why** (measured 2026-08-21, Samsung): with the app behind the notification
 * shade it received *nothing* on any `239.7.7.x` group or the `/24` broadcast,
 * despite holding the `WifiMulticastLock`; the instant it came to the foreground
 * it received everything. A kiosked tablet (S9+/A54 as Home) stays foreground so
 * it is covered, but the **un-kioskable passenger Fires** would silently lose GPS
 * (they have no own GNSS) and the threat feed the moment the app drops behind a
 * dialog or the launcher. A foreground service is the standard fix — it lifts the
 * process out of the background-network throttle.
 *
 * Deliberately owns **no receivers**: they already run in [ZodiacApplication]'s
 * scope and work; this is purely the process-priority anchor, so there is no
 * lifecycle to get wrong and nothing to double-start.
 */
class FleetLinkService : Service() {
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForegroundSafely()
        // Sticky: if the OS kills the process under memory pressure, restart the
        // service (null intent) so the link comes back without needing the Activity.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Start in the foreground with the `location` type where the platform allows
     * it (API 29+ and the location permission granted), falling back to the
     * untyped call otherwise. On API 34+ a `location`-typed FGS demands the
     * location permission at start; a Fire that hasn't granted it yet would throw,
     * so we retry untyped rather than crash — the process still gets FGS priority,
     * which is the only thing the multicast throttle cares about.
     */
    private fun startForegroundSafely() {
        val notification = buildNotification()
        val hasLocation =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val startedTyped =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                hasLocation &&
                runCatching {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                }.isSuccess
        if (!startedTyped) {
            runCatching { startForeground(NOTIFICATION_ID, notification) }
                .onFailure { Log.e(TAG, "fleet-link: startForeground failed — RX may throttle when backgrounded", it) }
        }
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Idempotent; must be unconditional or startForeground() drops the service.
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Zodiac Fleet Link", NotificationManager.IMPORTANCE_LOW),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zodiac cockpit")
            .setContentText("Fleet link active — GPS + threats")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ZodiacFleetLink"
        private const val CHANNEL_ID = "zodiac_fleet_link"
        private const val NOTIFICATION_ID = 2

        /** Start (or no-op if already running) the link-keeper. Call from a foreground Activity. */
        fun start(context: Context) {
            val intent = Intent(context, FleetLinkService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
