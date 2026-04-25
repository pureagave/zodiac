package ai.openclaw.zodiaccontrol.core.geo

/**
 * A point in the playa-local Cartesian frame, in meters from the Golden Spike.
 * +east is geographic east, +north is geographic north. Rotation to BRC clock
 * orientation (12:00 = ~north) is the renderer's responsibility.
 */
data class PlayaPoint(val eastM: Double, val northM: Double)
