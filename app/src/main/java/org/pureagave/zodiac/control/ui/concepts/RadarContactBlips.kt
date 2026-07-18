package org.pureagave.zodiac.control.ui.concepts

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import org.pureagave.zodiac.control.core.geo.PlayaProjection
import org.pureagave.zodiac.control.core.geo.PlayaViewport
import org.pureagave.zodiac.control.core.ops.PlayaPoi
import org.pureagave.zodiac.control.core.ops.PoiKind
import org.pureagave.zodiac.control.core.ops.contactPulse
import org.pureagave.zodiac.control.core.ops.contactsWithinRange
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Plot discovery POIs as M41A scope contacts. The scope centre is the canvas
 * centre (the RADAR pins the ego there); the visible range in metres is the
 * scope radius over the current zoom, which doubles as the proximity filter
 * fed to [contactsWithinRange]. Art draws as a diamond, camps as a dot, and
 * the active drive-to target as a distinct ringed blip.
 *
 * When [ContactsOverlay.sweepDeg] is supplied the blips pulse with the sweep
 * arm — the angle and pulse are read inside the draw scope so the frame ticker
 * invalidates draw, not composition (the same trick the sweep arm uses).
 */
internal fun DrawScope.drawContacts(
    pois: List<PlayaPoi>,
    projection: PlayaProjection,
    viewport: PlayaViewport,
    overlay: ContactsOverlay,
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val scopeRadiusPx = size.width.coerceAtMost(size.height) / 2f
    val rangeM = scopeRadiusPx / viewport.pixelsPerMeter
    val sweepDeg = overlay.sweepDeg?.invoke()

    contactsWithinRange(pois, viewport.center, rangeM, overlay.maxContacts).forEach { contact ->
        val point = contact.poi.point ?: return@forEach
        val screen = viewport.toScreen(point)
        val sx = screen.x.toFloat()
        val sy = screen.y.toFloat()
        val dx = sx - cx
        val dy = sy - cy
        // The scope is circular-clipped by the panel; skip corner blips so the
        // pulse/shape work only runs for contacts actually on the glass.
        if (hypot(dx, dy) > scopeRadiusPx) return@forEach
        val alpha =
            if (sweepDeg != null) {
                val blipAngle = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + QUARTER_TURN_DEG).toFloat()
                contactPulse(sweepDeg, blipAngle)
            } else {
                1f
            }
        when (contact.poi.kind) {
            PoiKind.ART -> drawContactDiamond(sx, sy, overlay.artColor.copy(alpha = alpha))
            PoiKind.CAMP -> drawContactDot(sx, sy, overlay.campColor.copy(alpha = alpha))
        }
    }

    // Active drive-to target: always full-bright with a ring so it reads as
    // the destination among the ambient contacts.
    overlay.target?.let { target ->
        val screen = viewport.toScreen(projection.project(target))
        drawTargetBlip(screen.x.toFloat(), screen.y.toFloat(), overlay.targetColor)
    }
}

private fun DrawScope.drawContactDot(
    x: Float,
    y: Float,
    color: Color,
) = drawCircle(color = color, radius = CONTACT_RADIUS, center = Offset(x, y))

private fun DrawScope.drawContactDiamond(
    x: Float,
    y: Float,
    color: Color,
) {
    val r = CONTACT_RADIUS * DIAMOND_MULT
    val path =
        Path().apply {
            moveTo(x, y - r)
            lineTo(x + r, y)
            lineTo(x, y + r)
            lineTo(x - r, y)
            close()
        }
    drawPath(path = path, color = color)
}

private fun DrawScope.drawTargetBlip(
    x: Float,
    y: Float,
    color: Color,
) {
    drawCircle(color = color, radius = CONTACT_RADIUS, center = Offset(x, y))
    drawCircle(color = color, radius = TARGET_RING_RADIUS, center = Offset(x, y), style = Stroke(width = 2f))
}

private const val CONTACT_RADIUS: Float = 5f
private const val DIAMOND_MULT: Float = 1.3f
private const val TARGET_RING_RADIUS: Float = 12f
private const val QUARTER_TURN_DEG: Double = 90.0
