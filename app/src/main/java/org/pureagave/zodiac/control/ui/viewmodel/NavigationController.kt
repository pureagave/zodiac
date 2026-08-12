package org.pureagave.zodiac.control.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.pureagave.zodiac.control.core.geo.PlayaPoint
import org.pureagave.zodiac.control.core.geo.PlayaProjection
import org.pureagave.zodiac.control.core.model.PlayaMap
import org.pureagave.zodiac.control.core.navigation.ClockTime
import org.pureagave.zodiac.control.core.navigation.NavigationCue
import org.pureagave.zodiac.control.core.navigation.PlayaCityModel
import org.pureagave.zodiac.control.core.navigation.computeNavigationCue
import org.pureagave.zodiac.control.core.navigation.nextWaypoint
import org.pureagave.zodiac.control.core.navigation.routeTo
import org.pureagave.zodiac.control.core.navigation.streetLabel
import org.pureagave.zodiac.control.core.navigation.toCityModel
import org.pureagave.zodiac.control.core.ops.AnnouncementCooldown
import org.pureagave.zodiac.control.core.ops.NavTarget
import org.pureagave.zodiac.control.core.ops.PlayaPoi
import org.pureagave.zodiac.control.core.ops.PoiKind
import org.pureagave.zodiac.control.core.ops.addressTarget
import org.pureagave.zodiac.control.core.ops.contactsWithinRange
import org.pureagave.zodiac.control.ui.state.CockpitUiState

/**
 * Where we are and where we're going: the drive-to target (preset / BATH /
 * typed address), the street-aware route to it, the turn-by-turn nav cue, and
 * the two transient announcements derived from the same inputs (street-name
 * flash, passing-art callout).
 *
 * Split out of [CockpitViewModel] (which owns it and forwards to it) because
 * every one of these reads the same (ego fix, heading, city model, target)
 * tuple and recomputes off the same two triggers — a new GPS fix and a target
 * change. Keeping them together is what lets [recomputeNavCue] and
 * [recomputeRoute] share the city model and the cooldown state instead of the
 * ViewModel holding six loose fields for them.
 *
 * [scope] is the owning ViewModel's `viewModelScope`, so the popup-clearing
 * timers die with the ViewModel exactly as they did when they lived there.
 *
 * @param state the cockpit's single state holder, shared with the ViewModel.
 */
internal class NavigationController(
    private val state: MutableStateFlow<CockpitUiState>,
    private val scope: CoroutineScope,
    private val projection: PlayaProjection,
) {
    /**
     * Built once when the BRC GIS finishes loading; used to feed every
     * subsequent [computeNavigationCue] call. Held outside the UI state
     * because the model is large and never has to round-trip through the
     * Composable layer — only the resulting [NavigationCue] does.
     */
    private var cityModel: PlayaCityModel? = null

    /** Last street the ego was on, and the timer that clears its flash popup. */
    private val streetCooldown = AnnouncementCooldown(STREET_COOLDOWN_MS)
    private val passingCooldown = AnnouncementCooldown(PASSING_COOLDOWN_MS)

    private var lastStreetLabel: String? = null
    private var streetPopupJob: Job? = null

    /** Last art whose passing was announced, and the timer that clears its callout. */
    private var lastPassingUid: String? = null
    private var passingJob: Job? = null

    /**
     * The BRC GIS finished loading: derive the city model everything here
     * routes against, then refresh both derived outputs so the cockpit isn't
     * showing an "unknown" cue until the next fix arrives.
     */
    fun onMapLoaded(map: PlayaMap) {
        cityModel = map.toCityModel(projection)
        recomputeNavCue()
        recomputeRoute()
    }

    /** Set the active "drive to" preset (HOME / MAN / TEMPLE); clears a BATH lock. Session state. */
    fun setNavTarget(target: NavTarget) {
        state.update { it.copy(navTarget = target, driveToBath = false, customTarget = null) }
        recomputeRoute()
    }

    /**
     * Drive to the nearest toilet bank. Session state; the target re-resolves
     * from [CockpitUiState.activeDriveTarget] as the ego moves, so it always
     * points at the closest one.
     */
    fun driveToNearestToilet() {
        state.update { it.copy(driveToBath = true, customTarget = null) }
        recomputeRoute()
    }

    /** Show/hide the full-screen address-entry keypad. */
    fun setAddressEntryOpen(open: Boolean) {
        state.update { it.copy(addressEntryOpen = open) }
    }

    /**
     * Drive to a typed-in city address (clock + ring letter, e.g. 2:15 & H).
     * Resolves it to a point on the polar grid and makes it the active custom
     * target so the chevron + route guide there. No-op on an unknown ring.
     * Returns whether it actually applied — [NavShareController.userSet] only
     * publishes an `ADDR` message once this proves the target resolved, so a
     * bad ring name never gets broadcast to the fleet as though it landed.
     */
    fun driveToAddress(
        clock: ClockTime,
        ringName: String,
    ): Boolean {
        val target = addressTarget(clock, ringName, projection) ?: return false
        state.update { it.copy(customTarget = target, driveToBath = false) }
        recomputeRoute()
        return true
    }

    /**
     * Re-derive the nav cue from the current (ego, heading, city) triple
     * and stash it in state. Called from every collector / setter that
     * affects an input — keeps the cue cheap to read in the Composable.
     * No-ops when the city model isn't loaded yet or there's no GPS fix.
     */
    fun recomputeNavCue() {
        val cm = cityModel
        val snapshot = state.value
        val ego = snapshot.egoFix?.location?.let(projection::project)
        val cue =
            if (cm != null && ego != null) {
                computeNavigationCue(ego, snapshot.headingDeg, cm)
            } else {
                NavigationCue.Unknown
            }
        if (cue != snapshot.navCue) state.update { it.copy(navCue = cue) }
        flashStreetOnCrossing(cue)
        if (ego != null) announcePassingArt(ego, snapshot.pois)
    }

    /**
     * Recompute the street-aware route to the active drive-to target and the
     * next corner to steer toward. Same inputs as the nav cue (fix / city model
     * / target). Clears to an empty route when any input is missing. The router
     * is cheap vector math, so this runs on every fix without a cache.
     */
    fun recomputeRoute() {
        val cm = cityModel
        val snapshot = state.value
        val ego = snapshot.egoFix?.location?.let(projection::project)
        val target = snapshot.activeDriveTarget?.location?.let(projection::project)
        if (cm == null || ego == null || target == null) {
            if (snapshot.routeWaypointsM.isNotEmpty() || snapshot.nextWaypoint != null) {
                state.update { it.copy(routeWaypointsM = emptyList(), nextWaypoint = null, entranceRadial = null) }
            }
            return
        }
        val route = routeTo(ego, target, cm)
        val next = nextWaypoint(route.waypointsM, ego)?.let(projection::unproject)
        state.update {
            it.copy(routeWaypointsM = route.waypointsM, nextWaypoint = next, entranceRadial = route.entranceRadial)
        }
    }

    /**
     * Flash the street name whenever the ego moves onto a new street (or, out
     * a radial, crosses into a new ring).
     */
    private fun flashStreetOnCrossing(cue: NavigationCue) {
        val street = cue.streetLabel()
        // Cooldown rather than "differs from last": a label flickering to null
        // and back at a block edge would otherwise re-flash every time.
        if (street != null && street != lastStreetLabel && streetCooldown.shouldAnnounce(street)) {
            state.update { it.copy(streetPopup = street) }
            streetPopupJob?.cancel()
            streetPopupJob =
                scope.launch {
                    delay(STREET_POPUP_MS)
                    state.update { it.copy(streetPopup = null) }
                }
        }
        lastStreetLabel = street
    }

    /**
     * Passing callout: flash the nearest notable art the ego is within range
     * of (passenger flavour). New art only, and cleared on a timer.
     */
    private fun announcePassingArt(
        ego: PlayaPoint,
        pois: List<PlayaPoi>,
    ) {
        val nearest =
            contactsWithinRange(pois.filter { it.kind == PoiKind.ART }, ego, PASS_RADIUS_M, max = 1)
                .firstOrNull()
        val uid = nearest?.poi?.uid
        // Cooldown rather than "differs from last": two pieces at similar
        // range take turns being nearest as the ego jitters, and the pair
        // would otherwise re-announce on every flip.
        if (uid != null && uid != lastPassingUid && passingCooldown.shouldAnnounce(uid)) {
            state.update { it.copy(passingCallout = nearest.poi.name) }
            passingJob?.cancel()
            passingJob =
                scope.launch {
                    delay(PASSING_CALLOUT_MS)
                    state.update { it.copy(passingCallout = null) }
                }
        }
        lastPassingUid = uid
    }
}

/**
 * How long the same street or art piece is suppressed from re-announcing.
 * Long enough that a flapping nearest-contact, or a street label flickering at
 * a block edge, cannot spam the display; short enough that a genuine second
 * pass later in the night still calls out.
 */
private const val STREET_COOLDOWN_MS = 60_000L
private const val PASSING_COOLDOWN_MS = 120_000L

/** How long a street-crossing name stays flashed before it clears. */
private const val STREET_POPUP_MS: Long = 2_500L

/** Proximity (metres) at which we announce passing a notable art piece, and how long the callout stays. */
private const val PASS_RADIUS_M: Double = 120.0
private const val PASSING_CALLOUT_MS: Long = 3_000L
