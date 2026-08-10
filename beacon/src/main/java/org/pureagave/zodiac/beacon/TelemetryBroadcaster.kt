package org.pureagave.zodiac.beacon

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Seam over the location-manager calls that actually need `ACCESS_FINE_LOCATION`,
 * so [TelemetryBroadcaster.start] can be exercised in a plain JVM test without a
 * real `LocationManager` and without risking an unguarded `SecurityException`
 * escaping `onCreate`/`onStartCommand` (see AUDIT-2026-08-09 B5 — that exact
 * unguarded call was the crash-loop).
 */
internal interface BeaconGpsHandle {
    fun hasFineLocation(): Boolean

    @Throws(SecurityException::class)
    fun wire(
        onLocation: LocationListener,
        onNmea: OnNmeaMessageListener,
    )

    fun unwire(
        onLocation: LocationListener,
        onNmea: OnNmeaMessageListener,
    )
}

/** Test seam: set to a fake before calling [TelemetryBroadcaster.start] to avoid touching a real `LocationManager`. */
internal var gpsHandleOverride: BeaconGpsHandle? = null

private const val GPS_MIN_INTERVAL_MS = 1000L

/**
 * The $GPHDT sentence, or null when no compass reading has ever arrived — a
 * device with no rotation-vector sensor must emit NOTHING, not 0.0, because
 * 0.0 is a valid heading and the tablets cannot tell the difference.
 */
internal fun hdtSentenceOrNull(headingDeg: Double?): String? = headingDeg?.let { Nmea.hdt(it) }

/** The $ZENV sentence, or null when no lux reading has ever arrived. */
internal fun zenvSentenceOrNull(lux: Double?): String? = lux?.let { Nmea.zenv(it) }

private class AndroidBeaconGpsHandle(
    private val context: Context,
    private val locationManager: LocationManager,
) : BeaconGpsHandle {
    override fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    // hasFineLocation() is checked by the caller before wire() is invoked; this
    // call is still wrapped in a try/catch there for the race where permission
    // is revoked between the check and this call (permission can be pulled at
    // any time on API 23+), which is exactly the crash this seam exists to guard.
    override fun wire(
        onLocation: LocationListener,
        onNmea: OnNmeaMessageListener,
    ) {
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            GPS_MIN_INTERVAL_MS,
            0f,
            onLocation,
            Looper.getMainLooper(),
        )
        locationManager.addNmeaListener(onNmea, Handler(Looper.getMainLooper()))
    }

    override fun unwire(
        onLocation: LocationListener,
        onNmea: OnNmeaMessageListener,
    ) {
        locationManager.removeUpdates(onLocation)
        locationManager.removeNmeaListener(onNmea)
    }
}

/**
 * The Zodiac Beacon engine: reads the phone's GNSS (raw NMEA) and magnetometer
 * heading and sends them over UDP to the vehicle LAN — to the fixed fleet
 * multicast group (239.7.7.10:10110, DHCP-independent) with a /24 subnet-directed
 * broadcast fallback for APs that drop multicast — so every tablet's
 * `NetworkLocationSource` picks them up. GNSS
 * sentences are forwarded verbatim; a true-heading `HDT` is synthesized from the
 * compass at a steady rate so heading updates even when the vehicle is stopped
 * (where GPS course is meaningless).
 *
 * A singleton so the foreground [TelemetryService] drives it while
 * [BeaconActivity] observes [status] / [isRunning].
 */
object TelemetryBroadcaster : SensorEventListener {
    const val PORT = 10110

    // Fixed telemetry multicast group (mirrors app FleetBus.TELEMETRY_GROUP) —
    // DHCP-independent. We also send to the limited broadcast as a fallback for
    // APs that rate-limit/drop multicast.
    const val GROUP = "239.7.7.10"
    private const val LIMITED_BROADCAST = "255.255.255.255"
    private const val TTL = 1
    private const val HDT_INTERVAL_MS = 250L
    private const val ROTATION_MATRIX_SIZE = 9
    private const val ORIENTATION_SIZE = 3
    private const val PITCH_INDEX = 1
    private const val ROLL_INDEX = 2
    private const val MPS_TO_KPH = 3.6
    private const val FULL_CIRCLE = 360.0

    // Slow channels ride the 250 ms loop on a tick divisor so they don't spam the
    // bus: light + odometer every ~2 s, the health heartbeat every ~5 s. Shock is
    // event-driven from the accelerometer, not on the loop.
    private const val ENV_EVERY_TICKS = 8
    private const val ODO_EVERY_TICKS = 8
    private const val HEALTH_EVERY_TICKS = 20
    private const val STATUS_EVERY_TICKS = 4 // rebuild the on-device readout ~1 Hz

    private const val BATTERY_SCALE_PCT = 100
    private const val MS_PER_SEC = 1000L
    private const val NANOS_PER_MS = 1_000_000L

    // internal: BeaconActivity (battery-optimization-prompt flag, B4) and
    // BootReceiver (PREF_AUTO_START, B3) share this same preferences file.
    internal const val PREFS_NAME = "zodiac_beacon"
    private const val PREF_TOTAL_METERS = "odometer_total_m"

    private const val TAG = "ZodiacBeacon"

    // Mic capture: 16 kHz mono 16-bit, 1024-sample frames → ~15 Hz $ZAUD frames,
    // fast enough for sound-reactive lighting. Only a level/beat number leaves the
    // phone — no audio is stored or transmitted.
    private const val AUDIO_SAMPLE_RATE = 16_000
    private const val AUDIO_FRAME_SAMPLES = 1024
    private const val BYTES_PER_SAMPLE = 2

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _running = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _running.asStateFlow()

    private var scope: CoroutineScope? = null
    private var socket: DatagramSocket? = null
    private var targets: List<InetAddress> = emptyList()
    private var sensorManager: SensorManager? = null
    private var nmeaListener: OnNmeaMessageListener? = null
    private var locationListener: LocationListener? = null
    private var gpsHandle: BeaconGpsHandle? = null

    @Volatile private var gpsWired: Boolean = false

    @Volatile private var headingDeg: Double? = null

    @Volatile private var pitchDeg: Double = 0.0

    @Volatile private var rollDeg: Double = 0.0

    @Volatile private var lastLocation: Location? = null

    @Volatile private var luxValue: Double? = null

    @Volatile private var fixQuality: Int = 0

    @Volatile private var satellites: Int = 0

    @Volatile private var tripMeters: Double = 0.0

    @Volatile private var totalMeters: Double = 0.0

    @Volatile private var sentences: Long = 0

    @Volatile private var lastShockG: Double = 0.0

    @Volatile private var audioRms: Double = 0.0

    @Volatile private var audioBeat: Boolean = false

    @Volatile private var audioActive: Boolean = false

    // Tick-loop health (B6): the loop's own last-run timestamp/error tally, and
    // the most recent full readout it managed to compute — so the watchdog
    // coroutine can report the loop's death even though the dead thing can't
    // report on itself.
    @Volatile private var lastTickAtMs: Long = 0L

    @Volatile private var tickErrors: Long = 0L

    @Volatile private var lastTickError: String? = null

    @Volatile private var lastGoodStatus: String = ""

    private var startElapsedMs: Long = 0
    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private var shockDetector: ShockDetector? = null
    private var odometer: TripOdometer? = null
    private var audioRecord: AudioRecord? = null

    /**
     * @param micEnabled whether the mic channel ($ZAUD) should even attempt to
     * start — false on a background start (see [TelemetryService.onStartCommand]),
     * where the foreground service has no microphone type declared and starting
     * capture anyway would run outside what the OS was told this service does.
     */
    fun start(
        context: Context,
        micEnabled: Boolean = true,
    ) {
        if (_running.value) return
        val app = context.applicationContext
        appContext = app
        startElapsedMs = SystemClock.elapsedRealtime()
        lastTickAtMs = 0L
        tickErrors = 0L
        lastTickError = null
        lastGoodStatus = ""
        gpsWired = false
        val store = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = store
        val seededTotal = store.getFloat(PREF_TOTAL_METERS, 0f).toDouble()
        val odo = TripOdometer(totalSeedM = seededTotal)
        odometer = odo
        totalMeters = seededTotal
        shockDetector = ShockDetector()
        // Primary: the fixed fleet multicast group. Fallback: the /24
        // subnet-directed broadcast (reliably delivered by consumer APs, unlike
        // the limited 255.255.255.255). Belt-and-suspenders → the fleet gets the
        // telemetry whether or not the AP forwards multicast.
        targets = listOf(InetAddress.getByName(GROUP), subnetBroadcast(app.getSystemService(Context.WIFI_SERVICE) as WifiManager))
        socket =
            MulticastSocket().apply {
                timeToLive = TTL
                broadcast = true
            }
        val running = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = running

        val onLoc =
            LocationListener { loc ->
                lastLocation = loc
                odo.add(loc.latitude, loc.longitude)
                tripMeters = odo.tripMeters
                totalMeters = odo.totalMeters
            }
        locationListener = onLoc
        val onNmea = OnNmeaMessageListener { message, _ -> forward(message) }
        nmeaListener = onNmea
        gpsWired = wireGps(app, onLoc, onNmea)

        val sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager = sm
        registerSensors(sm)
        if (micEnabled) startAudioCapture(app, running)

        launchTickLoop(running)
        launchDeadmanWatchdog(running)

        _running.value = true
        // Reflect the real GPS/mic state immediately rather than a generic
        // "Broadcasting" banner — a degraded start (no location permission, GPS
        // wiring failure) must be visible on the phone the instant it happens,
        // not just once the tick loop's first status refresh lands.
        _status.value = statusText()
    }

    /**
     * GPS is optional, never fatal: no permission → skip wiring outright; a
     * `SecurityException` from the actual registration call (permission can be
     * revoked between the check and the call) is caught, not propagated. Either
     * way every other channel keeps broadcasting — a permissionless beacon still
     * sends `$ZTLM`/`$ZENV`/`$ZBCN`, so the fleet sees "alive, no fix" instead of
     * a crash-looping service (AUDIT-2026-08-09 B5).
     */
    private fun wireGps(
        app: Context,
        onLoc: LocationListener,
        onNmea: OnNmeaMessageListener,
    ): Boolean {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val handle = gpsHandleOverride ?: AndroidBeaconGpsHandle(app, lm)
        gpsHandle = handle
        if (!handle.hasFineLocation()) return false
        return runCatching { handle.wire(onLoc, onNmea) }
            .onFailure { e -> Log.e(TAG, "beacon: GPS wiring failed, broadcasting sensors only", e) }
            .isSuccess
    }

    /** Ambient light (slow) drives the fleet's day/night switch; linear
     * acceleration (gravity removed, fast) feeds the shock detector. */
    private fun registerSensors(sm: SensorManager) {
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    private fun launchTickLoop(loopScope: CoroutineScope) {
        loopScope.launch {
            TickLoop(
                body = { tick ->
                    lastTickAtMs = SystemClock.elapsedRealtime()
                    hdtSentenceOrNull(headingDeg)?.let { send(it) }
                    send(Nmea.ztlm(pitchDeg, rollDeg, speedKph()))
                    if (tick % ENV_EVERY_TICKS == 0L) zenvSentenceOrNull(luxValue)?.let { send(it) }
                    if (tick % ODO_EVERY_TICKS == 0L) send(Nmea.zodo(tripMeters, totalMeters))
                    if (tick % HEALTH_EVERY_TICKS == 0L) {
                        send(Nmea.zbcn(batteryPct(), fixQuality, satellites, uptimeSec()))
                        persistTotal()
                    }
                    if (tick % STATUS_EVERY_TICKS == 0L) _status.value = statusText()
                },
                onError = { t ->
                    tickErrors++
                    lastTickError = t.message ?: t::class.simpleName
                    Log.e(TAG, "tick loop error (tick errors so far: $tickErrors)", t)
                },
            ).run(HDT_INTERVAL_MS)
        }
    }

    /**
     * The tick loop above is the only thing that can report its own death,
     * which is exactly what makes that reporting untrustworthy when it dies.
     * This second, independent coroutine is the one that actually screams —
     * straight to `_status`, bypassing `statusText()`'s normal cadence. Note
     * the honest limitation: a Doze stall freezes this coroutine too (B4's
     * territory); the banner appears on wake.
     */
    private fun launchDeadmanWatchdog(loopScope: CoroutineScope) {
        loopScope.launch {
            while (isActive) {
                delay(TICK_DEAD_MS / 2)
                tickHealthLine(SystemClock.elapsedRealtime(), lastTickAtMs, tickErrors, lastTickError)?.let { health ->
                    _status.value = "$health\n$lastGoodStatus"
                }
            }
        }
    }

    fun stop() {
        persistTotal()
        if (gpsWired) {
            val loc = locationListener
            val nmea = nmeaListener
            if (loc != null && nmea != null) gpsHandle?.unwire(loc, nmea)
        }
        gpsWired = false
        gpsHandle = null
        sensorManager?.unregisterListener(this)
        scope?.cancel()
        scope = null
        audioRecord?.let { rec ->
            runCatching { rec.stop() }
            rec.release()
        }
        audioRecord = null
        audioActive = false
        socket?.close()
        socket = null
        _running.value = false
        _status.value = "Stopped"
    }

    /** GNSS sentence from the OS → forward it verbatim + refresh fix health. The
     * full status readout is rebuilt on the broadcast loop (see [statusText]). */
    private fun forward(nmea: String) {
        send(if (nmea.endsWith("\n")) nmea else "$nmea\r\n")
        updateFixHealth(nmea)
    }

    /**
     * Everything the beacon is currently broadcasting, for the on-device readout —
     * one line per channel family: position (GNSS), heading/tilt/speed (HDT/ZTLM),
     * ambient light (ZENV), last shock (ZSHK), health (ZBCN), odometer (ZODO), and
     * the mic level (ZAUD). Refreshed ~1 Hz from the broadcast loop.
     */
    private fun statusText(): String {
        val loc = lastLocation
        val up = uptimeSec()
        val mic = if (audioActive) "rms %.2f%s".format(audioRms, if (audioBeat) "  ♪BEAT" else "") else "off (grant mic → \$ZAUD)"
        val body =
            buildString {
                append(
                    when {
                        loc != null -> "GPS    %.5f, %.5f".format(loc.latitude, loc.longitude)
                        gpsWired -> "GPS    acquiring…"
                        else -> "GPS OFF — grant location permission"
                    },
                )
                val hdgText = headingDeg?.let { "${it.toInt()}°" } ?: "--"
                append("\nHDG    $hdgText   TILT p${pitchDeg.toInt()} r${rollDeg.toInt()}   SPD ${speedKph().toInt()} kph")
                val lightText = luxValue?.let { "${it.toInt()} lx" } ?: "-- lx (no sensor)"
                append("\nLIGHT  $lightText    SHOCK %.1f g peak".format(lastShockG))
                append(
                    "\nHEALTH ${batteryPct()}%%  fix q$fixQuality/$satellites sat  up %02d:%02d:%02d".format(
                        up / 3600,
                        (up % 3600) / 60,
                        up % 60,
                    ),
                )
                append("\nODO    %.2f km trip / %.1f km total".format(tripMeters / 1000.0, totalMeters / 1000.0))
                append("\nMIC    $mic")
                append("\n→ $GROUP:$PORT   ·   sent $sentences")
            }
        lastGoodStatus = body
        val health = tickHealthLine(SystemClock.elapsedRealtime(), lastTickAtMs, tickErrors, lastTickError)
        return if (health != null) "$health\n$body" else body
    }

    private fun send(line: String) {
        val sock = socket ?: return
        if (targets.isEmpty()) return
        scope?.launch {
            val bytes = line.toByteArray(Charsets.US_ASCII)
            var anySent = false
            // Each target independently — a failing multicast send must not block
            // the broadcast fallback (or vice versa).
            targets.forEach { dst ->
                runCatching {
                    sock.send(DatagramPacket(bytes, bytes.size, dst, PORT))
                    anySent = true
                }
            }
            if (anySent) sentences++
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> onRotationVector(event)
            Sensor.TYPE_LIGHT -> luxValue = event.values[0].toDouble()
            Sensor.TYPE_LINEAR_ACCELERATION -> onLinearAcceleration(event)
        }
    }

    private fun onRotationVector(event: SensorEvent) {
        val rotation = FloatArray(ROTATION_MATRIX_SIZE)
        val orientation = FloatArray(ORIENTATION_SIZE)
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        SensorManager.getOrientation(rotation, orientation)
        var az = Math.toDegrees(orientation[0].toDouble()) // magnetic, −180..180
        lastLocation?.let { loc ->
            val field = GeomagneticField(loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), loc.time)
            az += field.declination // magnetic → true north
        }
        headingDeg = ((az % FULL_CIRCLE) + FULL_CIRCLE) % FULL_CIRCLE
        pitchDeg = Math.toDegrees(orientation[PITCH_INDEX].toDouble())
        rollDeg = Math.toDegrees(orientation[ROLL_INDEX].toDouble())
    }

    /** Linear-accel magnitude (gravity already removed) → shock detector → `$ZSHK`. */
    private fun onLinearAcceleration(event: SensorEvent) {
        val v = event.values
        val magnitude = sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())
        val nowMs = event.timestamp / NANOS_PER_MS
        shockDetector?.sample(magnitude, nowMs)?.let { peakG ->
            lastShockG = peakG
            send(Nmea.zshk(peakG))
        }
    }

    private fun speedKph(): Double = (lastLocation?.speed?.toDouble() ?: 0.0) * MPS_TO_KPH

    /**
     * Start the mic capture loop that emits `$ZAUD` for sound-reactive lighting.
     * RECORD_AUDIO is optional: if it's not granted (or the device has no usable
     * mic), we simply skip it and every other channel keeps broadcasting. Only a
     * level/beat number is emitted — no audio is recorded or transmitted.
     */
    private fun startAudioCapture(
        app: Context,
        loopScope: CoroutineScope,
    ) {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val record = createAudioRecord() ?: return
        audioRecord = record
        audioActive = true
        val levels = AudioLevels()
        record.startRecording()
        loopScope.launch {
            val buf = ShortArray(AUDIO_FRAME_SAMPLES)
            while (isActive) {
                val n = runCatching { record.read(buf, 0, buf.size) }.getOrDefault(-1)
                if (n <= 0) break // stopped/released or read error → end the loop
                val f = levels.analyze(buf, n)
                audioRms = f.rms
                audioBeat = f.beat
                send(Nmea.zaud(f.rms, f.peak, f.beat))
            }
        }
    }

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO before invoking
    private fun createAudioRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record =
            runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuf, AUDIO_FRAME_SAMPLES * BYTES_PER_SAMPLE),
                )
            }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            return null
        }
        return record
    }

    /** Battery percent from the sticky ACTION_BATTERY_CHANGED intent (0 if unknown). */
    private fun batteryPct(): Int {
        val intent = appContext?.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 0
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level < 0 || scale <= 0) 0 else level * BATTERY_SCALE_PCT / scale
    }

    private fun uptimeSec(): Long = (SystemClock.elapsedRealtime() - startElapsedMs) / MS_PER_SEC

    private fun persistTotal() {
        prefs?.edit()?.putFloat(PREF_TOTAL_METERS, totalMeters.toFloat())?.apply()
    }

    /** Pull fix-quality + satellite count out of a passing GGA for the heartbeat. */
    private fun updateFixHealth(nmea: String) {
        val health = BeaconNet.parseFixHealth(nmea)
        health.fixQuality?.let { fixQuality = it }
        health.satellites?.let { satellites = it }
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) = Unit

    /**
     * The /24 subnet-directed broadcast for the phone's current WiFi address
     * (e.g. 192.168.0.234 → 192.168.0.255). Android reports `ipAddress`
     * little-endian, so the low three octets are the address's first three.
     */
    private fun subnetBroadcast(wifi: WifiManager): InetAddress {
        @Suppress("DEPRECATION")
        val ip = wifi.dhcpInfo?.ipAddress ?: 0
        val host = BeaconNet.subnetBroadcastHost(ip) ?: LIMITED_BROADCAST
        return runCatching { InetAddress.getByName(host) }.getOrDefault(InetAddress.getByName(LIMITED_BROADCAST))
    }
}
