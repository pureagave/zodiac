package org.pureagave.zodiac.beacon

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Set by the caller when a start has a reason to believe it's happening in the
 * background: a boot start (B3), or a manual re-delivery. A `START_STICKY`
 * process restart delivers a null intent, which [onStartCommand] treats the
 * same way without needing this extra at all. */
const val EXTRA_FROM_BACKGROUND = "from_background"

private const val TAG = "ZodiacBeacon"

/**
 * Foreground service that runs [TelemetryBroadcaster] so the beacon keeps
 * broadcasting with the screen off — it's a headless box bolted to the vehicle,
 * not something anyone looks at.
 *
 * `startForeground` and the broadcaster start live in [onStartCommand], not
 * [onCreate]: only `onStartCommand` receives the intent, and the intent (null,
 * or carrying [EXTRA_FROM_BACKGROUND]) is the only signal for *why* this start
 * is happening — which [safeForegroundTypes] needs to pick a legal type set
 * (see AUDIT-2026-08-09 B5).
 */
class TelemetryService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    // FLEET-1 version emit — its own scope + broadcaster, entirely separate from
    // TelemetryBroadcaster so a fault here can never touch the GNSS transmit path.
    private val versionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var versionBroadcaster: VersionBroadcaster? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val fromBackground = intent == null || intent.getBooleanExtra(EXTRA_FROM_BACKGROUND, false)
        val micUsable = hasRecordAudio() && !fromBackground
        val types = safeForegroundTypes(Build.VERSION.SDK_INT, hasLocationPermission(), hasRecordAudio(), fromBackground)
        if (types == null) {
            startForeground(NOTIFICATION_ID, buildNotification(degradedText()))
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(degradedText()), types)
        }
        acquireWakeLock()
        // The wake lock is taken first (the odometer persist inside stop() needs
        // the CPU awake), which means a throw in start() leaves this service
        // alive, holding a wake lock, showing its notification — and broadcasting
        // nothing, forever, on a phone bolted to a vehicle. Observed 2026-08-11
        // after a cold boot. Never let that be silent again.
        runCatching { TelemetryBroadcaster.start(this, micEnabled = micUsable) }
            .onFailure { Log.e(TAG, "beacon: start() failed — the service is up but will not transmit", it) }
        // Separate from the GNSS start above and swallowed on failure: the version
        // announce is a nice-to-have, never worth risking the fleet's only GNSS.
        runCatching { startVersionBroadcast() }
            .onFailure { Log.w(TAG, "beacon: version announce failed to start (non-fatal)", it) }
        return START_STICKY
    }

    override fun onDestroy() {
        // Stop first: the odometer's final persist happens inside stop(), and it
        // must run with the CPU still awake, not race a wake lock release.
        TelemetryBroadcaster.stop()
        runCatching { versionBroadcaster?.stop() }
        versionBroadcaster = null
        versionScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    /**
     * Announce this beacon's build on the FLEET-1 version bus. Idempotent for a
     * `START_STICKY` restart or a repeated manual start — a second call must not
     * stack broadcasters. The sentence is fixed for the process; the broadcaster
     * re-sends it every 10 s and re-resolves its subnet-broadcast target from the
     * live DHCP lease (the phone can boot before the router has one).
     */
    private fun startVersionBroadcast() {
        if (versionBroadcaster != null) return
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val sentence = beaconVersionSentence(androidId, Build.MODEL)
        val wifi = getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val broadcaster =
            VersionBroadcaster(
                scope = versionScope,
                dhcp = {
                    @Suppress("DEPRECATION")
                    val info = wifi?.dhcpInfo
                    DhcpLease(info?.ipAddress ?: 0, info?.netmask ?: 0)
                },
            )
        broadcaster.start(sentence)
        versionBroadcaster = broadcaster
        Log.i(TAG, "beacon: announcing version ${sentence.trim()}")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * A foreground service does not by itself keep the CPU awake — Doze stalls
     * the tick loop otherwise (AUDIT-2026-08-09 B4). Idempotent: a `START_STICKY`
     * restart or repeated manual start must not stack up wake lock objects.
     * No timeout: the service lifecycle *is* the timeout for a 14-day
     * unattended run — a sleeping beacon is operationally identical to a dead
     * one, and `$ZBCN`'s battery percentage gives hours of warning either way.
     */
    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock =
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zodiac:beacon").apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasRecordAudio(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun degradedText(): String =
        if (!hasLocationPermission()) {
            "Degraded: location permission missing — broadcasting sensors only"
        } else {
            "Broadcasting vehicle telemetry"
        }

    private fun buildNotification(text: String): Notification {
        // minSdk is 29, so the pre-O "no notification channels" branch was dead
        // code (Lint ObsoleteSdkInt). Channel creation is idempotent, and it must
        // stay unconditional: without the channel the foreground notification is
        // dropped and startForeground() kills the service.
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Zodiac Beacon", NotificationManager.IMPORTANCE_LOW),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zodiac Beacon")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "zodiac_beacon"
        const val NOTIFICATION_ID = 1
    }
}
