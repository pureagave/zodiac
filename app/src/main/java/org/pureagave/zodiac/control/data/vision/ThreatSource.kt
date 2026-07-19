package org.pureagave.zodiac.control.data.vision

import kotlinx.coroutines.flow.StateFlow
import org.pureagave.zodiac.control.core.vision.DriverThreat

/**
 * A source of thermal contacts for the DRIVER night HUD, mirroring the
 * `LocationSource` shape so the cockpit can route between a real network feed
 * (the Jetson edge box) and a synthetic one the same way it routes GPS. The
 * current contact list is exposed as a [StateFlow]; an empty list means "all
 * clear."
 */
interface ThreatSource {
    val threats: StateFlow<List<DriverThreat>>

    /**
     * Whether a live feed is currently delivering frames — distinct from
     * [threats] being empty. A running edge box that explicitly reports "all
     * clear" has [feedAlive] = true with an empty [threats]; only a genuinely
     * *absent* feed (never seen, or gone stale) reports false. This is the only
     * signal a routed source should treat as "fall back to the demo" — falling
     * back on an empty-but-live feed would paint fabricated contacts (and fake
     * collision alarms) over a real all-clear.
     */
    val feedAlive: StateFlow<Boolean>

    suspend fun start()

    suspend fun stop()
}
