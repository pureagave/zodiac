package org.pureagave.zodiac.control.data.sensor

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pureagave.zodiac.control.core.sensor.FixFreshness
import org.pureagave.zodiac.control.core.sensor.LocationSourceError
import org.pureagave.zodiac.control.core.sensor.LocationSourceState
import org.pureagave.zodiac.control.core.sensor.LocationSourceType
import org.pureagave.zodiac.control.core.sensor.matchGpsDeviceName
import org.pureagave.zodiac.control.core.sensor.noGpsDeviceMessage
import org.pureagave.zodiac.control.data.sensor.nmea.NmeaParser
import timber.log.Timber
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

    private val freshness = FixFreshness()

    private val _state = MutableStateFlow<LocationSourceState>(LocationSourceState.Disconnected)
    override val state: StateFlow<LocationSourceState> = _state.asStateFlow()

    private var job: Job? = null
    private var watchdog: Job? = null
    private var socket: BluetoothSocket? = null

    override suspend fun start() {
        if (!hasBluetoothConnectPermission()) {
            _state.value = LocationSourceState.Error(MISSING_PERMISSION_MSG, LocationSourceError.PERMISSION_DENIED)
            return
        }
        job?.cancel()
        _state.value = LocationSourceState.Searching
        freshness.reset()
        watchdog?.cancel()
        watchdog =
            scope.launch {
                // A receiver that loses sky goes quiet or reports "no fix"; either
                // way this source stops publishing and would otherwise hold its
                // last Active forever. See FixFreshness.
                while (isActive) {
                    _state.value = freshness.demoteIfStale(_state.value)
                    delay(FixFreshness.STALE_MS / 2)
                }
            }
        job = scope.launch(Dispatchers.IO) { runConnection(this) }
    }

    override suspend fun stop() {
        job?.cancel()
        watchdog?.cancel()
        job = null
        watchdog = null
        withContext(Dispatchers.IO) {
            runCatching { socket?.close() }
            socket = null
        }
        _state.value = LocationSourceState.Disconnected
    }

    // Broad catch is deliberate: any radio/permission/IO failure must surface
    // as an Error state, never crash the IO coroutine.
    @SuppressLint("MissingPermission")
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runConnection(connectionScope: CoroutineScope) {
        try {
            val adapter = bluetoothAdapter()
            if (adapter == null || !adapter.isEnabled) {
                _state.value = LocationSourceState.Error(ADAPTER_OFF_MSG, LocationSourceError.ADAPTER_UNAVAILABLE)
                return
            }
            val paired = adapter.bondedDevices.orEmpty().toList()
            val match = matchGpsDeviceName(paired.map { it.name.orEmpty() }, deviceNamePattern)
            val device = paired.firstOrNull { it.name.orEmpty() == match }
            if (device == null) {
                // Say what IS paired — "no device matched" alone leaves the
                // operator unable to tell an unpaired receiver from one named
                // something we didn't anticipate. See noGpsDeviceMessage.
                _state.value =
                    LocationSourceState.Error(
                        noGpsDeviceMessage(paired.map { it.name.orEmpty() }),
                        LocationSourceError.NO_DEVICE_FOUND,
                    )
                return
            }
            Timber.i("gps: BLE selected '%s' of %d paired", device.name, paired.size)
            val sppSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket = sppSocket
            sppSocket.connect()
            pumpNmea(sppSocket, connectionScope)
        } catch (ex: Exception) {
            runCatching { socket?.close() }
            socket = null
            // A read/close throwing after stop() cancelled this job is a normal
            // shutdown, not an error — stop() already set Disconnected.
            if (connectionScope.isActive) {
                _state.value = LocationSourceState.Error("BT: ${ex.message}", LocationSourceError.IO_ERROR)
            }
        }
    }

    private fun pumpNmea(
        sppSocket: BluetoothSocket,
        connectionScope: CoroutineScope,
    ) {
        sppSocket.inputStream.bufferedReader().use { reader ->
            while (connectionScope.isActive) {
                val line = reader.readLine() ?: break
                NmeaParser.parse(line)?.let {
                    freshness.onFix()
                    _state.value = LocationSourceState.Active(it)
                }
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
    }
}
