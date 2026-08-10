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
import org.pureagave.zodiac.control.core.sensor.matchGpsDeviceName
import org.pureagave.zodiac.control.core.sensor.noGpsDeviceMessage
import org.pureagave.zodiac.control.data.sensor.nmea.NmeaParser
import timber.log.Timber
import java.io.BufferedReader
import java.util.UUID

/**
 * Seam over the Bluetooth Classic SPP plumbing this source needs, mirroring
 * [SystemLocationManagerHandle] and [MulticastLockHandle] for the same reason:
 * a JVM unit test cannot construct a [Context], a [BluetoothAdapter] or a
 * [BluetoothSocket], so without this the whole start/stop lifecycle — the part
 * that leaks sockets and resurrects state — was untestable and untested.
 *
 * Deliberately split into "find the device" and "open a link to it": the SPP
 * connect blocks for ~12 s on a receiver that is out of range, and the only
 * way to abort it is to close the socket from another thread. So the link
 * object must exist *before* it is connected, which is exactly how the real
 * Android path behaves.
 */
interface BluetoothSppHandle {
    fun hasConnectPermission(): Boolean

    fun isAdapterEnabled(): Boolean

    /** Names of every bonded device, in adapter order. */
    fun pairedDeviceNames(): List<String>

    /** An unconnected SPP link to the paired device called [deviceName]. */
    fun createLink(deviceName: String): BluetoothSppLink
}

/**
 * One SPP link. [readLine] is *blocking* in the real implementation — a
 * Bluetooth socket read has no timeout and only returns when a line arrives or
 * the socket is closed — so cancelling the coroutine around it does not unblock
 * it. That is why [BleLocationSource.stop] closes the link before it joins.
 */
interface BluetoothSppLink {
    /** Blocking RFCOMM connect. Aborted by a concurrent [close]. */
    suspend fun connect()

    /** Next NMEA line, or null at end of stream. */
    suspend fun readLine(): String?

    /** Idempotent: called from both [BleLocationSource.stop] and the listener's own teardown. */
    fun close()
}

@SuppressLint("MissingPermission")
private class AndroidBluetoothSppHandle(
    private val applicationContext: Context,
) : BluetoothSppHandle {
    override fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            // Pre-Android 12 BLUETOOTH / BLUETOOTH_ADMIN are install-time.
            true
        }

    override fun isAdapterEnabled(): Boolean = adapter()?.isEnabled == true

    override fun pairedDeviceNames(): List<String> = adapter()?.bondedDevices.orEmpty().map { it.name.orEmpty() }

    override fun createLink(deviceName: String): BluetoothSppLink {
        val device =
            checkNotNull(adapter()?.bondedDevices.orEmpty().firstOrNull { it.name.orEmpty() == deviceName }) {
                "paired device '$deviceName' is gone"
            }
        return AndroidBluetoothSppLink(device.createRfcommSocketToServiceRecord(BleLocationSource.SPP_UUID))
    }

    private fun adapter(): BluetoothAdapter? =
        (applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
}

// BLUETOOTH_CONNECT is checked by hasConnectPermission() before start() ever
// asks for a link, and a revoked-mid-session SecurityException surfaces as
// LocationSourceState.Error like any other connect failure.
@SuppressLint("MissingPermission")
private class AndroidBluetoothSppLink(
    private val socket: BluetoothSocket,
) : BluetoothSppLink {
    private var reader: BufferedReader? = null

    override suspend fun connect() {
        socket.connect()
        reader = socket.inputStream.bufferedReader()
    }

    override suspend fun readLine(): String? = reader?.readLine()

    override fun close() {
        // Close the socket, not just the reader: closing the socket is also what
        // aborts a connect() still blocked on an out-of-range receiver.
        runCatching { socket.close() }
        reader = null
    }
}

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
 *
 * Lifecycle discipline matches [NetworkLocationSource] (AUDIT-2026-08-09 C5) —
 * see [start] and [stop]. The selector chip is tappable as fast as a finger
 * moves and `CockpitViewModel` re-selects the persisted source on launch, so
 * both are entered redundantly in normal use.
 */
class BleLocationSource(
    private val handle: BluetoothSppHandle,
    private val scope: CoroutineScope,
    private val deviceNamePattern: Regex = DEFAULT_NAME_PATTERN,
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
        deviceNamePattern: Regex = DEFAULT_NAME_PATTERN,
    ) : this(AndroidBluetoothSppHandle(applicationContext), scope, deviceNamePattern)

    override val type: LocationSourceType = LocationSourceType.BLE

    private val freshness = FixFreshness(staleMs, nowMs)

    private val _state = MutableStateFlow<LocationSourceState>(LocationSourceState.Disconnected)
    override val state: StateFlow<LocationSourceState> = _state.asStateFlow()

    private var job: Job? = null
    private var watchdog: Job? = null

    @Volatile private var link: BluetoothSppLink? = null

    override suspend fun start() {
        if (job?.isActive == true) {
            // Already running — a genuine no-op, not a relaunch
            // (AUDIT-2026-08-09 C5). `CockpitViewModel` init calls
            // `select(saved)`, which already started this source, then calls
            // `start()` again unconditionally; the old code answered that by
            // cancelling the live listener (dropping a working fix back to
            // Searching) and leaving its SPP socket open, since the cancel
            // never unblocks a blocked socket read and nothing closed it.
            return
        }
        if (!handle.hasConnectPermission()) {
            _state.value = LocationSourceState.Error(MISSING_PERMISSION_MSG, LocationSourceError.PERMISSION_DENIED)
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
        // 2. close the link *before* joining. An SPP read blocks with no
        //    timeout, so cancellation alone never unblocks it; joining first
        //    would hang stop() — and with it RoutedLocationSource's whole
        //    select() mutex — for as long as the receiver stays quiet.
        withContext(ioDispatcher) { runCatching { link?.close() } }
        // 3. only now join. This is cancelAndJoin split around the close, and
        //    the join is the point: cancellation is cooperative, so a line
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

    // Broad catch is deliberate: any radio/permission/IO failure must surface
    // as an Error state, never crash the IO coroutine.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runConnection(connectionScope: CoroutineScope) {
        var opened: BluetoothSppLink? = null
        try {
            if (!handle.isAdapterEnabled()) {
                _state.value = LocationSourceState.Error(ADAPTER_OFF_MSG, LocationSourceError.ADAPTER_UNAVAILABLE)
                return
            }
            val paired = handle.pairedDeviceNames()
            val match = matchGpsDeviceName(paired, deviceNamePattern)
            if (match == null) {
                // Say what IS paired — "no device matched" alone leaves the
                // operator unable to tell an unpaired receiver from one named
                // something we didn't anticipate. See noGpsDeviceMessage.
                _state.value =
                    LocationSourceState.Error(
                        noGpsDeviceMessage(paired),
                        LocationSourceError.NO_DEVICE_FOUND,
                    )
                return
            }
            Timber.i("gps: BLE selected '%s' of %d paired", match, paired.size)
            val fresh = handle.createLink(match)
            opened = fresh
            // Published before connect() so stop() can abort a connect that is
            // blocked on an out-of-range receiver.
            link = fresh
            fresh.connect()
            pumpNmea(fresh, connectionScope)
        } catch (ex: Exception) {
            // A read/close throwing after stop() cancelled this job is a normal
            // shutdown, not an error — stop() already set Disconnected.
            if (connectionScope.isActive) {
                _state.value = LocationSourceState.Error("BT: ${ex.message}", LocationSourceError.IO_ERROR)
            }
        } finally {
            // Every exit path releases the socket: a failed connect, a receiver
            // that ended the stream, and cancellation all used to leave the SPP
            // link open, and Android will not hand out a second one to the same
            // device — so one failed attempt made the source unusable until the
            // process restarted.
            runCatching { opened?.close() }
            if (link === opened) link = null
        }
    }

    private suspend fun pumpNmea(
        sppLink: BluetoothSppLink,
        connectionScope: CoroutineScope,
    ) {
        while (connectionScope.isActive) {
            val line = sppLink.readLine() ?: break
            NmeaParser.parse(line)?.let {
                freshness.onFix()
                _state.value = LocationSourceState.Active(it)
            }
        }
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val DEFAULT_NAME_PATTERN: Regex =
            Regex(".*(?i:GPS|Garmin|Bad ?Elf|XGPS|Holux|Qstarz|GNSS).*")
        private const val NANOS_PER_MS: Long = 1_000_000L
        private const val MISSING_PERMISSION_MSG: String = "BLUETOOTH_CONNECT not granted"
        private const val ADAPTER_OFF_MSG: String = "Bluetooth adapter unavailable or off"
    }
}
