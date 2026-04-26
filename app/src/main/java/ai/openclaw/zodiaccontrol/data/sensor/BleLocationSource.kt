package ai.openclaw.zodiaccontrol.data.sensor

import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceState
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType
import ai.openclaw.zodiaccontrol.data.sensor.nmea.NmeaParser
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth Classic SPP source for consumer NMEA GPS receivers (Garmin GLO,
 * Bad Elf, Dual XGPS, etc.). Steps:
 *
 * 1. The user pairs the receiver in Android settings (out-of-band).
 * 2. [start] picks the first paired device whose name matches
 *    [deviceNamePattern], opens an SPP socket, and reads NMEA line-by-line.
 * 3. Each parsed `$GPGGA` / `$GPRMC` becomes [LocationSourceState.Active].
 *
 * Permissions handled inline: pre-Android 12 uses install-time BLUETOOTH /
 * BLUETOOTH_ADMIN; Android 12+ requires runtime BLUETOOTH_CONNECT, which is
 * checked here. Missing permission, no paired devices, or socket failures all
 * surface as [LocationSourceState.Error] without crashing the cockpit.
 */
class BleLocationSource(
    private val applicationContext: Context,
    private val scope: CoroutineScope,
    private val deviceNamePattern: Regex = DEFAULT_NAME_PATTERN,
) : LocationSource {
    override val type: LocationSourceType = LocationSourceType.BLE

    private val _state = MutableStateFlow<LocationSourceState>(LocationSourceState.Disconnected)
    override val state: StateFlow<LocationSourceState> = _state.asStateFlow()

    private var job: Job? = null
    private var socket: BluetoothSocket? = null

    override suspend fun start() {
        if (!hasBluetoothConnectPermission()) {
            _state.value = LocationSourceState.Error(detail = MISSING_PERMISSION_MSG)
            return
        }
        job?.cancel()
        _state.value = LocationSourceState.Searching
        job = scope.launch(Dispatchers.IO) { runConnection() }
    }

    override suspend fun stop() {
        job?.cancel()
        job = null
        withContext(Dispatchers.IO) {
            runCatching { socket?.close() }
            socket = null
        }
        _state.value = LocationSourceState.Disconnected
    }

    @SuppressLint("MissingPermission")
    private suspend fun runConnection() {
        val adapter = bluetoothAdapter()
        if (adapter == null || !adapter.isEnabled) {
            _state.value = LocationSourceState.Error(detail = ADAPTER_OFF_MSG)
            return
        }
        val device = adapter.bondedDevices.firstOrNull { deviceNamePattern.matches(it.name.orEmpty()) }
        if (device == null) {
            _state.value = LocationSourceState.Error(detail = NO_DEVICE_MSG)
            return
        }
        try {
            val sppSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket = sppSocket
            sppSocket.connect()
            pumpNmea(sppSocket)
        } catch (io: IOException) {
            runCatching { socket?.close() }
            socket = null
            _state.value = LocationSourceState.Error(detail = "BT I/O: ${io.message}")
        }
    }

    private suspend fun pumpNmea(sppSocket: BluetoothSocket) {
        sppSocket.inputStream.bufferedReader().use { reader ->
            while (scope.coroutineContext.isActive) {
                val line = reader.readLine() ?: break
                NmeaParser.parse(line)?.let { _state.value = LocationSourceState.Active(it) }
            }
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? {
        val manager = applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    private fun hasBluetoothConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val DEFAULT_NAME_PATTERN: Regex =
            Regex(".*(?i:GPS|Garmin|Bad ?Elf|XGPS|Holux|Qstarz|GNSS).*")
        private const val MISSING_PERMISSION_MSG: String = "BLUETOOTH_CONNECT not granted"
        private const val ADAPTER_OFF_MSG: String = "Bluetooth adapter unavailable or off"
        private const val NO_DEVICE_MSG: String = "No paired Bluetooth GPS device matched"
    }
}
