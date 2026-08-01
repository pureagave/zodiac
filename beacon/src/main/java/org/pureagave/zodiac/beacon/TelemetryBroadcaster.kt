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
    private const val GPS_MIN_INTERVAL_MS = 1000L
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

    private const val BATTERY_SCALE_PCT = 100
    private const val MS_PER_SEC = 1000L
    private const val NANOS_PER_MS = 1_000_000L
    private const val PREFS_NAME = "zodiac_beacon"
    private const val PREF_TOTAL_METERS = "odometer_total_m"

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
    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null
    private var nmeaListener: OnNmeaMessageListener? = null
    private var locationListener: LocationListener? = null

    @Volatile private var headingDeg: Double = 0.0

    @Volatile private var pitchDeg: Double = 0.0

    @Volatile private var rollDeg: Double = 0.0

    @Volatile private var lastLocation: Location? = null

    @Volatile private var luxValue: Double = 0.0

    @Volatile private var fixQuality: Int = 0

    @Volatile private var satellites: Int = 0

    @Volatile private var tripMeters: Double = 0.0

    @Volatile private var totalMeters: Double = 0.0

    @Volatile private var sentences: Long = 0

    private var startElapsedMs: Long = 0
    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private var shockDetector: ShockDetector? = null
    private var odometer: TripOdometer? = null
    private var audioRecord: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (_running.value) return
        val app = context.applicationContext
        appContext = app
        startElapsedMs = SystemClock.elapsedRealtime()
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

        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm
        val onLoc =
            LocationListener { loc ->
                lastLocation = loc
                odo.add(loc.latitude, loc.longitude)
                tripMeters = odo.tripMeters
                totalMeters = odo.totalMeters
            }
        locationListener = onLoc
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_MIN_INTERVAL_MS, 0f, onLoc, Looper.getMainLooper())
        val onNmea = OnNmeaMessageListener { message, _ -> forward(message) }
        nmeaListener = onNmea
        lm.addNmeaListener(onNmea, Handler(Looper.getMainLooper()))

        val sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager = sm
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        // Ambient light (slow) drives the fleet's day/night switch; linear
        // acceleration (gravity removed, fast) feeds the shock detector.
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        startAudioCapture(app, running)

        running.launch {
            var tick = 0L
            while (isActive) {
                send(Nmea.hdt(headingDeg))
                send(Nmea.ztlm(pitchDeg, rollDeg, speedKph()))
                if (tick % ENV_EVERY_TICKS == 0L) send(Nmea.zenv(luxValue))
                if (tick % ODO_EVERY_TICKS == 0L) send(Nmea.zodo(tripMeters, totalMeters))
                if (tick % HEALTH_EVERY_TICKS == 0L) {
                    send(Nmea.zbcn(batteryPct(), fixQuality, satellites, uptimeSec()))
                    persistTotal()
                }
                tick++
                delay(HDT_INTERVAL_MS)
            }
        }
        _running.value = true
        _status.value = "Broadcasting → $GROUP:$PORT"
    }

    fun stop() {
        persistTotal()
        nmeaListener?.let { locationManager?.removeNmeaListener(it) }
        locationListener?.let { locationManager?.removeUpdates(it) }
        sensorManager?.unregisterListener(this)
        scope?.cancel()
        scope = null
        audioRecord?.let { rec ->
            runCatching { rec.stop() }
            rec.release()
        }
        audioRecord = null
        socket?.close()
        socket = null
        _running.value = false
        _status.value = "Stopped"
    }

    /** GNSS sentence from the OS → forward verbatim and refresh the status text. */
    private fun forward(nmea: String) {
        send(if (nmea.endsWith("\n")) nmea else "$nmea\r\n")
        updateFixHealth(nmea)
        val loc = lastLocation
        _status.value =
            buildString {
                append(if (loc != null) "GPS %.5f, %.5f".format(loc.latitude, loc.longitude) else "GPS acquiring…")
                append("\nHeading ${headingDeg.toInt()}°   •   sent $sentences")
                append("\n→ $GROUP:$PORT")
            }
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
        shockDetector?.sample(magnitude, nowMs)?.let { peakG -> send(Nmea.zshk(peakG)) }
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
        val levels = AudioLevels()
        record.startRecording()
        loopScope.launch {
            val buf = ShortArray(AUDIO_FRAME_SAMPLES)
            while (isActive) {
                val n = runCatching { record.read(buf, 0, buf.size) }.getOrDefault(-1)
                if (n <= 0) break // stopped/released or read error → end the loop
                val f = levels.analyze(buf, n)
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
