package ai.openclaw.zodiacbeacon

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

/**
 * The Zodiac Beacon engine: reads the phone's GNSS (raw NMEA) and magnetometer
 * heading and broadcasts them over UDP to the vehicle LAN (subnet broadcast,
 * port 10110) so every tablet's `NetworkLocationSource` picks them up. GNSS
 * sentences are forwarded verbatim; a true-heading `HDT` is synthesized from the
 * compass at a steady rate so heading updates even when the vehicle is stopped
 * (where GPS course is meaningless).
 *
 * A singleton so the foreground [TelemetryService] drives it while
 * [BeaconActivity] observes [status] / [isRunning].
 */
object TelemetryBroadcaster : SensorEventListener {
    const val PORT = 10110
    private const val HDT_INTERVAL_MS = 250L
    private const val GPS_MIN_INTERVAL_MS = 1000L
    private const val ROTATION_MATRIX_SIZE = 9
    private const val ORIENTATION_SIZE = 3
    private const val FULL_CIRCLE = 360.0
    private const val OCTETS = 4
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _running = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _running.asStateFlow()

    private var scope: CoroutineScope? = null
    private var socket: DatagramSocket? = null
    private var target: InetAddress? = null
    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null
    private var nmeaListener: OnNmeaMessageListener? = null
    private var locationListener: LocationListener? = null

    @Volatile private var headingDeg: Double = 0.0

    @Volatile private var lastLocation: Location? = null

    @Volatile private var sentences: Long = 0

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (_running.value) return
        val app = context.applicationContext
        target = broadcastAddress(app.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        socket = DatagramSocket().apply { broadcast = true }
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
                delay(HDT_INTERVAL_MS)
            }
        }
        _running.value = true
        _status.value = "Broadcasting → ${target?.hostAddress}:$PORT"
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
                append("\n→ ${target?.hostAddress}:$PORT")
            }
    }

    private fun send(line: String) {
        val sock = socket ?: return
        val dst = target ?: return
        scope?.launch {
            runCatching {
                val bytes = line.toByteArray(Charsets.US_ASCII)
                sock.send(DatagramPacket(bytes, bytes.size, dst, PORT))
                sentences++
            }
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
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) = Unit

    /**
     * Subnet-directed broadcast address from the current DHCP lease
     * (`ip | ~netmask`) — recomputed each start so it survives dynamic DHCP.
     * Falls back to the limited broadcast if DHCP info is unavailable.
     */
    private fun broadcastAddress(wifi: WifiManager): InetAddress {
        @Suppress("DEPRECATION")
        val dhcp = wifi.dhcpInfo
        val bcast = (dhcp?.ipAddress ?: 0) and (dhcp?.netmask ?: 0) or (dhcp?.netmask ?: 0).inv()
        val bytes = ByteArray(OCTETS) { i -> (bcast shr (i * BITS_PER_BYTE) and BYTE_MASK).toByte() }
        return runCatching { InetAddress.getByAddress(bytes) }.getOrDefault(InetAddress.getByName("255.255.255.255"))
    }
}
