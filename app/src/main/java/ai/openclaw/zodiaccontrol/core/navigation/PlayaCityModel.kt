package ai.openclaw.zodiaccontrol.core.navigation

import ai.openclaw.zodiaccontrol.core.geo.PlayaPoint
import ai.openclaw.zodiaccontrol.core.geo.PlayaProjection
import ai.openclaw.zodiaccontrol.core.model.PlayaMap
import ai.openclaw.zodiaccontrol.core.model.StreetKind
import kotlin.math.hypot

/**
 * One street pre-projected into playa metres so the navigator can run all
 * its geometry without re-projecting per frame. Source data carries each
 * logical street as several `LineString` segments — we keep [pointsM] as the
 * concatenated polyline for that segment.
 */
data class PlayaStreet(
    val name: String,
    val kind: StreetKind,
    val pointsM: List<PlayaPoint>,
)

/**
 * Static, projected representation of a single year's BRC layout, ready for
 * the navigator to query. Built once when the [PlayaMap] loads. Carries:
 *   - the trash-fence ring as the ray-cast target,
 *   - every named street with kind tagged,
 *   - arcs sorted inner-to-outer by mean radius from the Man (so we can
 *     answer "which arc did we just pass" with a single lookup).
 *
 * @property axisBearingDeg true-north bearing of the BRC 12:00 axis for this
 *   layout — held here so the navigator stays year-agnostic.
 */
data class PlayaCityModel(
    val trashFenceM: List<PlayaPoint>,
    val streetsM: List<PlayaStreet>,
    val arcsInnerToOuter: List<String>,
    val arcRadiiM: Map<String, Double>,
    val cityOuterRadiusM: Double,
    val axisBearingDeg: Double,
)

/**
 * Build a [PlayaCityModel] from the loaded [PlayaMap]. Streets without a
 * name or kind are dropped — they can't anchor a nav cue. Arc radii are the
 * mean of |point - origin| across all source points carrying that arc name.
 */
fun PlayaMap.toCityModel(
    projection: PlayaProjection,
    axisBearingDeg: Double = BRC_AXIS_BEARING_DEG_2025,
): PlayaCityModel {
    val fenceM = trashFence.firstOrNull()?.ring.orEmpty().map(projection::project)

    val streetsM =
        streetLines
            .filter { !it.name.isNullOrEmpty() && it.kind != null && it.points.isNotEmpty() }
            .map { sl ->
                PlayaStreet(
                    name = sl.name!!,
                    kind = sl.kind!!,
                    pointsM = sl.points.map(projection::project),
                )
            }

    val arcRadii =
        streetsM
            .filter { it.kind == StreetKind.Arc }
            .groupBy { it.name }
            .mapValues { (_, segs) ->
                segs.flatMap { it.pointsM }
                    .map { hypot(it.eastM, it.northM) }
                    .average()
            }

    val arcsInnerToOuter = arcRadii.entries.sortedBy { it.value }.map { it.key }
    val outerRadius = arcRadii.values.maxOrNull() ?: Double.MAX_VALUE

    return PlayaCityModel(
        trashFenceM = fenceM,
        streetsM = streetsM,
        arcsInnerToOuter = arcsInnerToOuter,
        arcRadiiM = arcRadii,
        cityOuterRadiusM = outerRadius,
        axisBearingDeg = axisBearingDeg,
    )
}
