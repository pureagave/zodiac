package org.pureagave.zodiac.control.data.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.pureagave.zodiac.control.core.geo.LatLon
import org.pureagave.zodiac.control.core.sensor.FixFreshness
import org.pureagave.zodiac.control.core.sensor.GpsFix
import org.pureagave.zodiac.control.core.sensor.LocationSourceError
import org.pureagave.zodiac.control.core.sensor.LocationSourceState
import org.pureagave.zodiac.control.core.sensor.LocationSourceType
import timber.log.Timber

/**
 * Thin wrapper over the parts of [LocationManager] this source needs.
 * Lives on the constructor so unit tests can substitute a fake without
 * Robolectric. The default [AndroidSystemLocationManagerHandle] talks to
 * the real platform service.
 *
 * Fixes are delivered through [GpsCallbacks] rather than a bare
 * `android.location.LocationListener` for two reasons: `Location` cannot be
 * constructed in this project's JVM unit tests, so a fake handle needs a
 * seam that hands over an already-converted [GpsFix]; and a SAM-lambda
 * `LocationListener` silently no-ops `onProviderDisabled`/`onProviderEnabled`,
 * which is one of the two bugs this reshape fixes.
 */
interface SystemLocationManagerHandle {
    fun hasFineLocationPermission(): Boolean

    fun requestGpsUpdates(
        intervalMs: Long,
        distanceM: Float,
        callbacks: GpsCallbacks,
    )

    fun removeUpdates()

    interface GpsCallbacks {
        fun onFix(fix: GpsFix)

        fun onProviderDisabled()

        fun onProviderEnabled()
    }
}

private class AndroidSystemLocationManagerHandle(
    private val applicationContext: Context,
) : SystemLocationManagerHandle {
    private var listener: LocationListener? = null

    override fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override fun requestGpsUpdates(
        intervalMs: Long,
        distanceM: Float,
        callbacks: SystemLocationManagerHandle.GpsCallbacks,
    ) {
        val manager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val newListener =
            object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    callbacks.onFix(location.toGpsFix())
                }

                override fun onProviderDisabled(provider: String) {
                    callbacks.onProviderDisabled()
                }

                override fun onProviderEnabled(provider: String) {
                    callbacks.onProviderEnabled()
                }
            }
        listener = newListener
        manager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            intervalMs,
            distanceM,
            newListener,
            Looper.getMainLooper(),
        )
    }

    override fun removeUpdates() {
        val current = listener ?: return
        val manager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager.removeUpdates(current)
        listener = null
    }

    private fun Location.toGpsFix(): GpsFix =
        GpsFix(
            location = LatLon(lon = longitude, lat = latitude),
            headingDeg = if (hasBearing()) bearing.toDouble() else null,
            speedKph = if (hasSpeed()) speed.toDouble() * MPS_TO_KPH else null,
            fixQualityM = if (hasAccuracy()) accuracy.toDouble() else null,
        )

    companion object {
        private const val MPS_TO_KPH: Double = 3.6
    }
}

/**
 * GPS via Android's built-in [LocationManager], requesting updates from
 * `GPS_PROVIDER`. Works for any Mock-Locations / phone-tether / fused-provider
 * setup that publishes through the system. The Activity is responsible for
 * obtaining `ACCESS_FINE_LOCATION` before calling [start]; if the permission
 * isn't granted the source emits [LocationSourceState.Error] and stays idle.
 *
 * SYSTEM is the fallback target inside [FailoverLocationSource], which judges
 * the fallback usable by its state being [LocationSourceState.Active]. Like
 * [BleLocationSource] and [UsbLocationSource], this source therefore runs a
 * [FixFreshness] watchdog so a receiver that has quietly lost sky demotes to
 * `Searching` instead of holding its last fix forever — see [FixFreshness]'s
 * doc for why a frozen `Active` is worse than an honest `Searching`.
 *
 * Note: Fire tablets do NOT have a built-in GPS receiver. Selecting this
 * source on a Fire device is only useful when location is being mocked or
 * forwarded from another app.
 */
class SystemLocationSource(
    private val managerHandle: SystemLocationManagerHandle,
    private val scope: CoroutineScope,
    private val staleMs: Long = FixFreshness.STALE_MS,
    nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) : LocationSource {
    constructor(
        applicationContext: Context,
        scope: CoroutineScope,
    ) : this(AndroidSystemLocationManagerHandle(applicationContext), scope)

    override val type: LocationSourceType = LocationSourceType.SYSTEM

    private val _state = MutableStateFlow<LocationSourceState>(LocationSourceState.Disconnected)
    override val state: StateFlow<LocationSourceState> = _state.asStateFlow()

    private val freshness = FixFreshness(staleMs, nowMs)

    private val callbacks =
        object : SystemLocationManagerHandle.GpsCallbacks {
            override fun onFix(fix: GpsFix) {
                freshness.onFix()
                _state.value = LocationSourceState.Active(fix)
            }

            override fun onProviderDisabled() {
                // Actionable and will not self-heal (unlike staleness, which
                // may resolve on its own) — surfaced as a fault, not Searching.
                _state.value = LocationSourceState.Error(PROVIDER_DISABLED_MSG, LocationSourceError.ADAPTER_UNAVAILABLE)
            }

            override fun onProviderEnabled() {
                _state.value = LocationSourceState.Searching
            }
        }

    @Volatile
    private var listenerRegistered: Boolean = false

    private var watchdog: Job? = null

    // Broad catch is deliberate: a missing GPS_PROVIDER (Fire tablets) or a
    // revoked permission must surface as an Error state, never crash start().
    @Suppress("TooGenericExceptionCaught")
    override suspend fun start() {
        if (listenerRegistered) return
        if (!managerHandle.hasFineLocationPermission()) {
            _state.value = LocationSourceState.Error(MISSING_PERMISSION_MSG, LocationSourceError.PERMISSION_DENIED)
            return
        }
        _state.value = LocationSourceState.Searching
        freshness.reset()
        try {
            managerHandle.requestGpsUpdates(MIN_INTERVAL_MS, MIN_DISTANCE_M, callbacks)
        } catch (ex: Exception) {
            // GPS_PROVIDER may not exist (Fire tablets) or permission may have
            // been revoked — surface as Error rather than stalling in Searching.
            _state.value = LocationSourceState.Error("GPS unavailable: ${ex.message}", LocationSourceError.ADAPTER_UNAVAILABLE)
            return
        }
        listenerRegistered = true
        watchdog?.cancel()
        watchdog =
            scope.launch {
                // A receiver that loses sky goes quiet; nothing else demotes a
                // frozen Active. See FixFreshness and A3 in docs/AUDIT-2026-08-09.md.
                while (isActive) {
                    _state.value = freshness.demoteIfStale(_state.value)
                    delay(staleMs / 2)
                }
            }
    }

    // Broad catch is deliberate, mirroring start(): a throwing removeUpdates()
    // (platform listener already torn down, provider gone) must not strand
    // this source Active with a frozen fix that FailoverLocationSource would
    // keep presenting as a live NET position.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun stop() {
        watchdog?.cancel()
        watchdog = null
        if (listenerRegistered) {
            try {
                managerHandle.removeUpdates()
            } catch (ex: Exception) {
                Timber.w(ex, "removeUpdates() threw during stop(); proceeding to Disconnected anyway")
            } finally {
                listenerRegistered = false
            }
        }
        _state.value = LocationSourceState.Disconnected
    }

    companion object {
        const val MIN_INTERVAL_MS: Long = 1_000L
        const val MIN_DISTANCE_M: Float = 1f
        private const val NANOS_PER_MS: Long = 1_000_000L
        private const val MISSING_PERMISSION_MSG: String = "ACCESS_FINE_LOCATION not granted"
        private const val PROVIDER_DISABLED_MSG: String = "GPS_PROVIDER disabled"
    }
}
