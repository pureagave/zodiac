package ai.openclaw.zodiaccontrol.ui.concepts

import ai.openclaw.zodiaccontrol.core.geo.PlayaPoint
import ai.openclaw.zodiaccontrol.core.geo.PlayaProjection
import ai.openclaw.zodiaccontrol.core.geo.PlayaViewport
import ai.openclaw.zodiaccontrol.core.sensor.GpsFix
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Draw the street-aware route: a dashed line from the ego through each corner
 * to the destination, a dot at every waypoint, and a ring on the final target.
 * Points project through the same [viewport] as the map so the route tracks
 * pan / zoom / rotate. Its own file so PlayaMapPanel stays under the per-file
 * function cap.
 */
internal fun DrawScope.drawRoute(
    egoFix: GpsFix?,
    routeM: List<PlayaPoint>,
    projection: PlayaProjection,
    viewport: PlayaViewport,
    color: Color,
) {
    if (routeM.isEmpty()) return
    val corners = routeM.map { it.toOffset(viewport) }
    val start = egoFix?.location?.let { projection.project(it).toOffset(viewport) }
    val line = listOfNotNull(start) + corners

    val dashed = PathEffect.dashPathEffect(floatArrayOf(ROUTE_DASH_ON, ROUTE_DASH_OFF))
    for (i in 0 until line.size - 1) {
        drawLine(color, line[i], line[i + 1], strokeWidth = ROUTE_STROKE, cap = StrokeCap.Round, pathEffect = dashed)
    }
    corners.forEachIndexed { i, o ->
        drawCircle(color, ROUTE_DOT, o)
        if (i == corners.lastIndex) drawCircle(color, ROUTE_DEST_RING, o, style = Stroke(width = 2f))
    }
}

private fun PlayaPoint.toOffset(viewport: PlayaViewport): Offset {
    val s = viewport.toScreen(this)
    return Offset(s.x.toFloat(), s.y.toFloat())
}

private const val ROUTE_STROKE = 3f
private const val ROUTE_DOT = 4f
private const val ROUTE_DEST_RING = 11f
private const val ROUTE_DASH_ON = 12f
private const val ROUTE_DASH_OFF = 9f
