package org.pureagave.zodiac.beacon

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Accumulates travelled distance from successive GNSS fixes via the haversine
 * formula. Three independent guards stand between a raw fix and the odometer,
 * because [totalMeters] is persisted forever (AUDIT-2026-08-09 C8) — a wrong
 * accumulation there is not recoverable, so every gate below is written to
 * fail closed (hold the anchor, add nothing) whenever a fix is doubtful:
 *
 * 1. **Jitter floor** ([jitterFloorM]): a step shorter than the floor is
 *    ignored and the anchor is held, so GPS wander while parked doesn't clock
 *    miles a metre at a time (slow creep still accumulates once it clears the
 *    floor).
 * 2. **Accuracy gate** ([maxAccuracyM]): a fix with no reported accuracy, or a
 *    worse accuracy circle than the ceiling, is not trusted to seed the anchor
 *    *or* to advance it. This is the primary defense for the parked-among-RVs
 *    multipath case: a chipset's accuracy estimate degrades right along with
 *    a multipath-corrupted solution, and a cold-start fix (the "hundreds of
 *    kilometres away" failure mode) reports a huge accuracy circle before the
 *    almanac loads — so gating the very first anchor on accuracy, not just
 *    later steps, stops a bad cold start from ever becoming the reference
 *    point everything else is measured against.
 * 3. **Max-step gate** ([maxPlausibleSpeedMps]): even an accurate-looking step
 *    is rejected if the distance implies a speed nothing could have driven in
 *    the elapsed time since the last *accepted* fix — a teleport, not travel.
 *    The ceiling deliberately reuses this project's own vehicle-speed envelope
 *    (`SetSpeed` validation caps speed at 160 kph, see
 *    `core/model/VehicleCommand` in `:app`) rather than the playa's much lower
 *    posted limit, specifically so a legitimate fast highway drive to the
 *    event is never rejected — only something no drive, on or off the playa,
 *    could produce.
 *
 * On rejection the anchor (position *and* timestamp) is held, never advanced,
 * so a bad fix can never poison the comparison for the next one. Because the
 * elapsed-time term is measured against the last accepted fix rather than the
 * last raw sample, a real position change that arrives after a run of
 * rejected fixes self-heals once enough wall-clock time has passed to make it
 * plausible — no special-case recovery path needed.
 *
 * Tracks [tripMeters] (this session) and [totalMeters] (seeded from the
 * persisted lifetime value). Pure + stateful.
 */
class TripOdometer(
    totalSeedM: Double = 0.0,
    private val jitterFloorM: Double = DEFAULT_JITTER_FLOOR_M,
    private val maxAccuracyM: Float = DEFAULT_MAX_ACCURACY_M,
    private val maxPlausibleSpeedMps: Double = DEFAULT_MAX_PLAUSIBLE_SPEED_MPS,
) {
    var tripMeters: Double = 0.0
        private set
    var totalMeters: Double = totalSeedM
        private set

    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastTimestampMs: Long = 0L

    /**
     * Add a fix. [accuracyMeters] is the fix's reported horizontal accuracy
     * (radius in metres; `null` if the platform didn't supply one — treated as
     * untrustworthy, not as "unknown and fine"). [timestampMs] is a monotonic
     * clock reading for the fix (e.g. `Location.elapsedRealtimeNanos`, not
     * GPS/wall-clock time, which can jump backwards). Returns the metres added
     * — 0 on the first fix, a sub-floor step, a low-accuracy fix, or a step
     * whose implied speed exceeds the plausible envelope.
     */
    fun add(
        lat: Double,
        lon: Double,
        accuracyMeters: Float?,
        timestampMs: Long,
    ): Double {
        val pLat = lastLat
        val pLon = lastLon
        return if (pLat == null || pLon == null) {
            seedAnchorIfAccurate(lat, lon, accuracyMeters, timestampMs)
            0.0
        } else if (!isAccurateEnough(accuracyMeters)) {
            0.0 // hold anchor; don't trust a poor fix
        } else {
            acceptStepOrHoldAnchor(pLat, pLon, lat, lon, timestampMs)
        }
    }

    /** First fix only: a poor-accuracy fix (e.g. a cold start) must never become the anchor everything else is measured against. */
    private fun seedAnchorIfAccurate(
        lat: Double,
        lon: Double,
        accuracyMeters: Float?,
        timestampMs: Long,
    ) {
        if (!isAccurateEnough(accuracyMeters)) return
        lastLat = lat
        lastLon = lon
        lastTimestampMs = timestampMs
    }

    /** Anchor is already accuracy-gated; this applies the jitter-floor and max-step (teleport) gates. */
    private fun acceptStepOrHoldAnchor(
        pLat: Double,
        pLon: Double,
        lat: Double,
        lon: Double,
        timestampMs: Long,
    ): Double {
        val d = haversineMeters(pLat, pLon, lat, lon)
        val dtSec = (timestampMs - lastTimestampMs) / MS_PER_SEC
        val impliedSpeedMps = d / dtSec // Infinity/negative for dtSec <= 0; caught by the dtSec check below
        val implausible =
            d < jitterFloorM || // hold the anchor; wait for real movement
                dtSec <= 0.0 || // clock confusion (out-of-order/duplicate fix timestamps) — can't judge plausibility
                impliedSpeedMps > maxPlausibleSpeedMps // teleport, not travel
        if (implausible) return 0.0
        lastLat = lat
        lastLon = lon
        lastTimestampMs = timestampMs
        tripMeters += d
        totalMeters += d
        return d
    }

    private fun isAccurateEnough(accuracyMeters: Float?): Boolean = accuracyMeters != null && accuracyMeters <= maxAccuracyM

    private companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
        const val DEFAULT_JITTER_FLOOR_M = 5.0

        // A "good" consumer GPS fix in open sky is typically 3-8 m; multipath
        // (RVs/art cars parked close together, exactly this vehicle's resting
        // state) and cold starts routinely inflate the platform's own accuracy
        // estimate well past that. 20 m is the commonly used "acceptable fix"
        // boundary — tight enough to reject the degraded-DOP fixes multipath
        // produces, loose enough not to starve the odometer in normal driving.
        const val DEFAULT_MAX_ACCURACY_M = 20.0f

        // 160 kph = this project's own validated vehicle-speed ceiling
        // (`SetSpeed`, `core/model/VehicleCommand`), reused here so "plausible
        // speed" has one definition app-wide. Deliberately far above the
        // playa's posted limit so a real highway drive to the event — even
        // floored — is never mistaken for a teleport; a multipath/cold-start
        // jump implies speeds many multiples of this, so the distinction stays
        // unambiguous even with a generous ceiling.
        const val DEFAULT_MAX_PLAUSIBLE_SPEED_MPS = 160.0 / 3.6
        const val MS_PER_SEC = 1_000.0

        fun haversineMeters(
            lat1: Double,
            lon1: Double,
            lat2: Double,
            lon2: Double,
        ): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a =
                sin(dLat / 2) * sin(dLat / 2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
