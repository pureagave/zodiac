package ai.openclaw.zodiaccontrol.core.model

/**
 * Whether the cockpit camera tracks the ego or holds an absolute world
 * position.
 *
 * - [TRACK_UP] (default): the camera centres on the GPS fix every frame
 *   and the display rotation matches the ego's heading, so the ego stays
 *   pinned to the viewport anchor pointing straight up.
 * - [FREE]: the camera is parked at a user-chosen world position and the
 *   display rotation is also user-controlled. The ego marker still draws
 *   at its real-world location, so as the GPS fix moves the marker slides
 *   across the screen while the map underneath stays put. Auto-reverts
 *   to [TRACK_UP] after [AUTO_RECENTER_MS] milliseconds without a map
 *   gesture, or immediately when the recenter button is tapped.
 */
enum class FollowMode { TRACK_UP, FREE }

/** Idle time after which a cockpit in [FollowMode.FREE] auto-recenters. */
const val AUTO_RECENTER_MS: Long = 60_000L
