package ai.openclaw.zodiaccontrol.data.sensor

import ai.openclaw.zodiaccontrol.core.geo.LatLon
import ai.openclaw.zodiaccontrol.core.sensor.GpsFix
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceState
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin wrapper over the parts of [LocationManager] this source needs.
 * Lives on the constructor so unit tests can substitute a fake without
 * Robolectric. The default [AndroidSystemLocationManagerHandle] talks to
 * the real platform service.
 */
interface SystemLocationManagerHandle {
    fun hasFineLocationPermission(): Boolean

    fun requestGpsUpdates(
        intervalMs: Long,
        distanceM: Float,
        listener: LocationListener,
    )

    fun removeUpdates(listener: LocationListener)
}

private class AndroidSystemLocationManagerHandle(
    private val applicationContext: Context,
) : SystemLocationManagerHandle {
    override fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override fun requestGpsUpdates(
        intervalMs: Long,
        distanceM: Float,
        listener: LocationListener,
    ) {
        val manager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            intervalMs,
            distanceM,
            listener,
            Looper.getMainLooper(),
        )
    }

    override fun removeUpdates(listener: LocationListener) {
        val manager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager.removeUpdates(listener)
    }
}

/**
 * GPS via Android's built-in [LocationManager], requesting updates from
 * `GPS_PROVIDER`. Works for any Mock-Locations / phone-tether / fused-provider
 * setup that publishes through the system. The Activity is responsible for
 * obtaining `ACCESS_FINE_LOCATION` before calling [start]; if the permission
 * isn't granted the source emits [LocationSourceState.Error] and stays idle.
 *
 * Note: Fire tablets do NOT have a built-in GPS receiver. Selecting this
 * source on a Fire device is only useful when location is being mocked or
 * forwarded from another app.
 */
class SystemLocationSource(
    private val managerHandle: SystemLocationManagerHandle,
) : LocationSource {
    constructor(applicationContext: Context) : this(AndroidSystemLocationManagerHandle(applicationContext))

    override val type: LocationSourceType = LocationSourceType.SYSTEM

    private val _state = MutableStateFlow<LocationSourceState>(LocationSourceState.Disconnected)
    override val state: StateFlow<LocationSourceState> = _state.asStateFlow()

    private val listener: LocationListener =
        LocationListener { loc -> _state.value = LocationSourceState.Active(loc.toGpsFix()) }

    private var listenerRegistered: Boolean = false

    override suspend fun start() {
        if (listenerRegistered) return
        if (!managerHandle.hasFineLocationPermission()) {
            _state.value = LocationSourceState.Error(detail = MISSING_PERMISSION_MSG)
            return
        }
        _state.value = LocationSourceState.Searching
        managerHandle.requestGpsUpdates(MIN_INTERVAL_MS, MIN_DISTANCE_M, listener)
        listenerRegistered = true
    }

    override suspend fun stop() {
        if (listenerRegistered) {
            managerHandle.removeUpdates(listener)
            listenerRegistered = false
        }
        _state.value = LocationSourceState.Disconnected
    }

    private fun Location.toGpsFix(): GpsFix =
        GpsFix(
            location = LatLon(lon = longitude, lat = latitude),
            headingDeg = if (hasBearing()) bearing.toDouble() else null,
            speedKph = if (hasSpeed()) speed.toDouble() * MPS_TO_KPH else null,
            fixQualityM = if (hasAccuracy()) accuracy.toDouble() else null,
        )

    companion object {
        const val MIN_INTERVAL_MS: Long = 1_000L
        const val MIN_DISTANCE_M: Float = 1f
        private const val MPS_TO_KPH: Double = 3.6
        private const val MISSING_PERMISSION_MSG: String = "ACCESS_FINE_LOCATION not granted"
    }
}
