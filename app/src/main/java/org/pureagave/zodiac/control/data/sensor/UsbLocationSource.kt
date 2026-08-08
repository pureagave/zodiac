package org.pureagave.zodiac.control.data.sensor

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
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
import org.pureagave.zodiac.control.core.sensor.NmeaLineAssembler
import org.pureagave.zodiac.control.data.sensor.nmea.NmeaParser

/**
 * USB-serial source for NMEA GPS dongles. Uses
 * [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android)
 * to handle per-chipset bulk-transfer protocols (CH340, FTDI, CP210x, PL2303,
 * CDC-ACM, u-blox).
 *
 * Permission flow: declared the device-attach intent filter +
 * `usb_gps_device_filter.xml` in the manifest, so plugging a known dongle
 * prompts the OS to grant access automatically. If permission isn't granted
 * (manual filter mismatch, denied dialog), the source emits
 * [LocationSourceState.Error].
 *
 * NMEA parsing is line-buffered: bytes are accumulated until `\n`, then the
 * line is fed to [NmeaParser]. `\r` is dropped.
 */
class UsbLocationSource(
    private val applicationContext: Context,
    private val scope: CoroutineScope,
    private val baudRate: Int = DEFAULT_BAUD,
) : LocationSource {
    override val type: LocationSourceType = LocationSourceType.USB

    private val freshness = FixFreshness()

    private val _state = MutableStateFlow<LocationSourceState>(LocationSourceState.Disconnected)
    override val state: StateFlow<LocationSourceState> = _state.asStateFlow()

    private var job: Job? = null
    private var watchdog: Job? = null
    private var port: UsbSerialPort? = null

    override suspend fun start() {
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
            runCatching { port?.close() }
            port = null
        }
        _state.value = LocationSourceState.Disconnected
    }

    // Broad catch is deliberate: any driver/IO failure must surface as an
    // Error state, never crash the IO coroutine.
    @Suppress("TooGenericExceptionCaught")
    private fun runConnection(connectionScope: CoroutineScope) {
        val manager = applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
        if (driver == null) {
            _state.value = LocationSourceState.Error(NO_DEVICE_MSG, LocationSourceError.NO_DEVICE_FOUND)
            return
        }
        val connection = manager.openDevice(driver.device)
        if (connection == null) {
            _state.value = LocationSourceState.Error(NO_PERMISSION_MSG, LocationSourceError.PERMISSION_DENIED)
            return
        }
        val sp = driver.ports.firstOrNull()
        if (sp == null) {
            _state.value = LocationSourceState.Error(NO_DEVICE_MSG, LocationSourceError.NO_DEVICE_FOUND)
            return
        }
        try {
            port = sp
            sp.open(connection)
            sp.setParameters(baudRate, DATA_BITS, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            pumpNmea(sp, connectionScope)
        } catch (ex: Exception) {
            runCatching { port?.close() }
            port = null
            // A read/close throwing after stop() cancelled this job is a normal
            // shutdown, not an error — stop() already set Disconnected.
            if (connectionScope.isActive) {
                _state.value = LocationSourceState.Error("USB: ${ex.message}", LocationSourceError.IO_ERROR)
            }
        }
    }

    private fun pumpNmea(
        sp: UsbSerialPort,
        connectionScope: CoroutineScope,
    ) {
        val buf = ByteArray(BUFFER_BYTES)
        val assembler = NmeaLineAssembler()
        while (connectionScope.isActive) {
            val n = sp.read(buf, READ_TIMEOUT_MS)
            if (n > 0) assembler.append(buf, n).forEach(::ingestLine)
        }
    }

    private fun ingestLine(line: String) {
        NmeaParser.parse(line)?.let {
            freshness.onFix()
            _state.value = LocationSourceState.Active(it)
        }
    }

    companion object {
        const val DEFAULT_BAUD: Int = 9600
        private const val DATA_BITS: Int = 8
        private const val BUFFER_BYTES: Int = 256
        private const val READ_TIMEOUT_MS: Int = 1_000
        private const val NO_DEVICE_MSG: String = "No USB serial device found"
        private const val NO_PERMISSION_MSG: String = "USB device permission not granted"
    }
}
