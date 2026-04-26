package ai.openclaw.zodiaccontrol.data.sensor

import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceState
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType
import ai.openclaw.zodiaccontrol.data.sensor.nmea.NmeaParser
import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
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

    private val _state = MutableStateFlow<LocationSourceState>(LocationSourceState.Disconnected)
    override val state: StateFlow<LocationSourceState> = _state.asStateFlow()

    private var job: Job? = null
    private var port: UsbSerialPort? = null

    override suspend fun start() {
        job?.cancel()
        _state.value = LocationSourceState.Searching
        job = scope.launch(Dispatchers.IO) { runConnection() }
    }

    override suspend fun stop() {
        job?.cancel()
        job = null
        withContext(Dispatchers.IO) {
            runCatching { port?.close() }
            port = null
        }
        _state.value = LocationSourceState.Disconnected
    }

    private fun runConnection() {
        val manager = applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
        if (driver == null) {
            _state.value = LocationSourceState.Error(detail = NO_DEVICE_MSG)
            return
        }
        val connection = manager.openDevice(driver.device)
        if (connection == null) {
            _state.value = LocationSourceState.Error(detail = NO_PERMISSION_MSG)
            return
        }
        try {
            val sp = driver.ports.first()
            port = sp
            sp.open(connection)
            sp.setParameters(baudRate, DATA_BITS, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            pumpNmea(sp)
        } catch (io: IOException) {
            runCatching { port?.close() }
            port = null
            _state.value = LocationSourceState.Error(detail = "USB I/O: ${io.message}")
        }
    }

    private fun pumpNmea(sp: UsbSerialPort) {
        val buf = ByteArray(BUFFER_BYTES)
        val line = StringBuilder(LINE_PREALLOC)
        while (scope.coroutineContext.isActive) {
            val n = sp.read(buf, READ_TIMEOUT_MS)
            if (n > 0) ingestBytes(buf, n, line)
        }
    }

    private fun ingestBytes(
        buf: ByteArray,
        count: Int,
        line: StringBuilder,
    ) {
        for (i in 0 until count) {
            val ch = buf[i].toInt().toChar()
            when (ch) {
                '\n' -> emitLine(line)
                '\r' -> Unit
                else -> line.append(ch)
            }
        }
    }

    private fun emitLine(line: StringBuilder) {
        NmeaParser.parse(line.toString())?.let { _state.value = LocationSourceState.Active(it) }
        line.clear()
    }

    companion object {
        const val DEFAULT_BAUD: Int = 9600
        private const val DATA_BITS: Int = 8
        private const val BUFFER_BYTES: Int = 256
        private const val LINE_PREALLOC: Int = 96
        private const val READ_TIMEOUT_MS: Int = 1_000
        private const val NO_DEVICE_MSG: String = "No USB serial device found"
        private const val NO_PERMISSION_MSG: String = "USB device permission not granted"
    }
}
