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

    suspend fun start()

    suspend fun stop()
}
