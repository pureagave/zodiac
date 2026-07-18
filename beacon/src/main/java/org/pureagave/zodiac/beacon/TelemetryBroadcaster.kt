package org.pureagave.zodiac.beacon

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
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
    private const val BYTE_MASK = 0xFF
    private const val OCTET2_SHIFT = 8
    private const val OCTET3_SHIFT = 16
    private const val TTL = 1
    private const val HDT_INTERVAL_MS = 250L
    private const val GPS_MIN_INTERVAL_MS = 1000L
    private const val ROTATION_MATRIX_SIZE = 9
    private const val ORIENTATION_SIZE = 3
    private const val PITCH_INDEX = 1
    private const val ROLL_INDEX = 2
    private const val MPS_TO_KPH = 3.6
    private const val FULL_CIRCLE = 360.0

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

    @Volatile private var sentences: Long = 0

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (_running.value) return
        val app = context.applicationContext
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
        val onLoc = LocationListener { loc -> lastLocation = loc }
        locationListener = onLoc
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_MIN_INTERVAL_MS, 0f, onLoc, Looper.getMainLooper())
        val onNmea = OnNmeaMessageListener { message, _ -> forward(message) }
        nmeaListener = onNmea
        lm.addNmeaListener(onNmea, Handler(Looper.getMainLooper()))

        val sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager = sm
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }

        running.launch {
            while (isActive) {
                send(Nmea.hdt(headingDeg))
                send(Nmea.ztlm(pitchDeg, rollDeg, speedKph()))
                delay(HDT_INTERVAL_MS)
            }
        }
        _running.value = true
        _status.value = "Broadcasting → $GROUP:$PORT"
    }

    fun stop() {
        nmeaListener?.let { locationManager?.removeNmeaListener(it) }
        locationListener?.let { locationManager?.removeUpdates(it) }
        sensorManager?.unregisterListener(this)
        scope?.cancel()
        scope = null
        socket?.close()
        socket = null
        _running.value = false
        _status.value = "Stopped"
    }

    /** GNSS sentence from the OS → forward verbatim and refresh the status text. */
    private fun forward(nmea: String) {
        send(if (nmea.endsWith("\n")) nmea else "$nmea\r\n")
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
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
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

    private fun speedKph(): Double = (lastLocation?.speed?.toDouble() ?: 0.0) * MPS_TO_KPH

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
        val a = ip and BYTE_MASK
        val b = (ip shr OCTET2_SHIFT) and BYTE_MASK
        val c = (ip shr OCTET3_SHIFT) and BYTE_MASK
        return runCatching { InetAddress.getByName("$a.$b.$c.255") }.getOrDefault(InetAddress.getByName(LIMITED_BROADCAST))
    }
}
