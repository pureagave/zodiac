package ai.openclaw.zodiaccontrol.data.sensor

import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType

/**
 * Holds every available [LocationSource] keyed by [LocationSourceType] so the
 * cockpit can switch between providers at runtime. Same shape as
 * [ai.openclaw.zodiaccontrol.data.transport.TransportRegistry].
 */
class LocationSourceRegistry(sources: List<LocationSource>) {
    private val byType: Map<LocationSourceType, LocationSource> = sources.associateBy { it.type }

    val available: Set<LocationSourceType> = byType.keys

    fun sourceFor(type: LocationSourceType): LocationSource = byType[type] ?: error("No LocationSource registered for $type")
}
