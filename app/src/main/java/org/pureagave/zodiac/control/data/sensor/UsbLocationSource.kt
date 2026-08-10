package org.pureagave.zodiac.control.data.sensor

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineDispatcher
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
 * Seam over the USB-serial plumbing, mirroring [SystemLocationManagerHandle],
 * [MulticastLockHandle] and [BluetoothSppHandle]: a JVM unit test can construct
 * neither a [Context] nor a [UsbSerialPort], so without this the start/stop
 * lifecycle — the part that leaks the port and resurrects state — could not be
 * tested at all.
 */
interface UsbSerialHandle {
    /** Find the first attached USB-serial GPS dongle. */
    fun attach(): UsbAttach
}

/** Outcome of [UsbSerialHandle.attach]. */
sealed interface UsbAttach {
    /**
     * Found and permitted, but deliberately *not* opened yet: the port must be
     * reachable by [UsbLocationSource.stop] before it is opened, so a close can
     * interrupt an open/read that never returns.
     */
    data class Attached(val link: UsbSerialLink) : UsbAttach

    data class Unavailable(val detail: String, val kind: LocationSourceError) : UsbAttach
}

/** One opened USB-serial port. [read] blocks in the real implementation. */
interface UsbSerialLink {
    suspend fun open(baudRate: Int)

    /** Bytes read this tick — empty when the read timed out with nothing to say. */
    suspend fun read(): ByteArray

    /** Idempotent: called from both [UsbLocationSource.stop] and the listener's own teardown. */
    fun close()
}

private class AndroidUsbSerialHandle(
    private val applicationContext: Context,
) : UsbSerialHandle {
    override fun attach(): UsbAttach {
        val manager = applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver =
            UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
                ?: return UsbAttach.Unavailable(NO_DEVICE_MSG, LocationSourceError.NO_DEVICE_FOUND)
        val connection =
            manager.openDevice(driver.device)
                ?: return UsbAttach.Unavailable(NO_PERMISSION_MSG, LocationSourceError.PERMISSION_DENIED)
        val port = driver.ports.firstOrNull()
        return if (port == null) {
            // Release the connection we just opened. Without this, a driver that
            // reports no ports leaks a USB file descriptor on every retry — and
            // retrying is exactly what the operator does with a flaky cable.
            runCatching { connection.close() }
            UsbAttach.Unavailable(NO_DEVICE_MSG, LocationSourceError.NO_DEVICE_FOUND)
        } else {
            UsbAttach.Attached(AndroidUsbSerialLink(port, connection))
        }
    }
}

private class AndroidUsbSerialLink(
    private val port: UsbSerialPort,
    private val connection: UsbDeviceConnection,
) : UsbSerialLink {
    private val buffer = ByteArray(BUFFER_BYTES)

    override suspend fun open(baudRate: Int) {
        port.open(connection)
        port.setParameters(baudRate, DATA_BITS, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
    }

    override suspend fun read(): ByteArray {
        val n = port.read(buffer, READ_TIMEOUT_MS)
        return if (n > 0) buffer.copyOf(n) else ByteArray(0)
    }

    override fun close() {
        runCatching { port.close() }
        // port.close() closes the connection for an *opened* port; an attach
        // that never got as far as open() would otherwise leak it.
        runCatching { connection.close() }
    }
}

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
 *
 * Lifecycle discipline matches [NetworkLocationSource] (AUDIT-2026-08-09 C5) —
 * see [start] and [stop]. The selector chip is tappable as fast as a finger
 * moves and `CockpitViewModel` re-selects the persisted source on launch, so
 * both are entered redundantly in normal use.
 */
class UsbLocationSource(
    private val handle: UsbSerialHandle,
    private val scope: CoroutineScope,
    private val baudRate: Int = DEFAULT_BAUD,
    // Seam for the listener's dispatcher: the read loop is blocking IO in
    // production, but a test needs it on the test scheduler to drive the
    // start/stop orderings deterministically instead of racing real threads.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val staleMs: Long = FixFreshness.STALE_MS,
    nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) : LocationSource {
    constructor(
        applicationContext: Context,
        scope: CoroutineScope,
        baudRate: Int = DEFAULT_BAUD,
    ) : this(AndroidUsbSerialHandle(applicationContext), scope, baudRate)

    override val type: LocationSourceType = LocationSourceType.USB

    private val freshness = FixFreshness(staleMs, nowMs)

    private val _state = MutableStateFlow<LocationSourceState>(LocationSourceState.Disconnected)
    override val state: StateFlow<LocationSourceState> = _state.asStateFlow()

    private var job: Job? = null
    private var watchdog: Job? = null

    @Volatile private var link: UsbSerialLink? = null

    override suspend fun start() {
        if (job?.isActive == true) {
            // Already running — a genuine no-op, not a relaunch
            // (AUDIT-2026-08-09 C5). `CockpitViewModel` init calls
            // `select(saved)`, which already started this source, then calls
            // `start()` again unconditionally; the old code answered that by
            // cancelling the live listener (dropping a working fix back to
            // Searching) and leaving the serial port open, since the cancel
            // never unblocks a blocked read and nothing closed it.
            return
        }
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
                    delay(staleMs / 2)
                }
            }
        job = scope.launch(ioDispatcher) { runConnection(this) }
    }

    override suspend fun stop() {
        val listener = job
        val monitor = watchdog
        // Three steps, in this order, and each order is load-bearing:
        //
        // 1. cancel() first, so a read that throws because of step 2 is
        //    recognised as a normal shutdown rather than reported as an IO
        //    fault the operator has to interpret.
        listener?.cancel()
        monitor?.cancel()
        // 2. close the port *before* joining. The read is a blocking bulk
        //    transfer, so cancellation alone does not unblock it; joining
        //    first would hang stop() — and with it RoutedLocationSource's
        //    whole select() mutex — until the dongle happened to say something.
        withContext(ioDispatcher) { runCatching { link?.close() } }
        // 3. only now join. This is cancelAndJoin split around the close, and
        //    the join is the point: cancellation is cooperative, so bytes
        //    already read would otherwise be parsed and published as Active
        //    *after* the Disconnected below, leaving a stopped source
        //    advertising a live fix. Joining makes the clear the last word.
        listener?.join()
        monitor?.join()
        job = null
        watchdog = null
        link = null
        _state.value = LocationSourceState.Disconnected
    }

    // Broad catch is deliberate: any driver/IO failure must surface as an
    // Error state, never crash the IO coroutine.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runConnection(connectionScope: CoroutineScope) {
        var opened: UsbSerialLink? = null
        try {
            when (val attached = handle.attach()) {
                is UsbAttach.Unavailable -> _state.value = LocationSourceState.Error(attached.detail, attached.kind)
                is UsbAttach.Attached -> {
                    opened = attached.link
                    // Published before open() so stop() can interrupt an open or
                    // read that never returns.
                    link = attached.link
                    attached.link.open(baudRate)
                    pumpNmea(attached.link, connectionScope)
                }
            }
        } catch (ex: Exception) {
            // A read/close throwing after stop() cancelled this job is a normal
            // shutdown, not an error — stop() already set Disconnected.
            if (connectionScope.isActive) {
                _state.value = LocationSourceState.Error("USB: ${ex.message}", LocationSourceError.IO_ERROR)
            }
        } finally {
            // Every exit path releases the port and its USB connection: a failed
            // open, an ended stream and cancellation all used to leave both
            // held, so one bad attempt made the dongle unopenable until the
            // process restarted.
            runCatching { opened?.close() }
            if (link === opened) link = null
        }
    }

    private suspend fun pumpNmea(
        serialLink: UsbSerialLink,
        connectionScope: CoroutineScope,
    ) {
        val assembler = NmeaLineAssembler()
        while (connectionScope.isActive) {
            val chunk = serialLink.read()
            if (chunk.isNotEmpty()) assembler.append(chunk, chunk.size).forEach(::ingestLine)
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
        private const val NANOS_PER_MS: Long = 1_000_000L
    }
}

private const val DATA_BITS: Int = 8
private const val BUFFER_BYTES: Int = 256
private const val READ_TIMEOUT_MS: Int = 1_000
private const val NO_DEVICE_MSG: String = "No USB serial device found"
private const val NO_PERMISSION_MSG: String = "USB device permission not granted"
