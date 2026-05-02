package ai.openclaw.zodiaccontrol.ui.playamap

import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import android.graphics.Paint as NativePaint

// Zoom thresholds (px/m) above which a layer's labels start drawing. Tuned
// against the default city-fits-tablet zoom (~0.18) so an overview stays
// clean and labels appear progressively as the user pinches in.
private const val PLAZA_LABEL_ZOOM = 0.20
private const val ART_MAJOR_LABEL_ZOOM = 0.30
private const val STREET_LABEL_ZOOM = 0.45
private const val CPN_LABEL_ZOOM = 0.65
private const val ART_MINOR_LABEL_ZOOM = 1.10

private const val PLAZA_TEXT_SP = 11f
private const val STREET_TEXT_SP = 9f
private const val CPN_TEXT_SP = 9f
private const val ART_MAJOR_TEXT_SP = 12f
private const val ART_MINOR_TEXT_SP = 8f
private const val LABEL_OFFSET_PX = 6f

/**
 * One Paint per process, mutated per call. Labels render on the Compose
 * frame thread (single-threaded), so a shared instance is safe and skips a
 * per-marker allocation.
 */
private val labelPaint =
    NativePaint().apply {
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
        textAlign = NativePaint.Align.CENTER
    }

/**
 * Zoom-gated label pass over a pre-projected map. Plaza names always show
 * once the city is zoomed past overview; arc/radial street names appear at
 * medium zoom; CPNs at higher zoom; art names tier by program (major /
 * minor). Toilets are intentionally unlabeled — the source data has no
 * per-bank name and the marker colour carries the meaning. The label
 * positions in [projected] were computed when the camera state last
 * changed; only the zoom-gating runs here, so this is cheap.
 */
fun DrawScope.drawProjectedLabels(
    projected: ProjectedMap,
    palette: MapPalette,
    pixelsPerMeter: Double,
) {
    val argb = palette.labelPrimary.toArgb()
    if (pixelsPerMeter >= PLAZA_LABEL_ZOOM) {
        projected.plazaLabels.forEach { l -> label(l.position, l.text, PLAZA_TEXT_SP, argb) }
    }
    projected.artLabels.forEach { l ->
        val gate = if (l.major) ART_MAJOR_LABEL_ZOOM else ART_MINOR_LABEL_ZOOM
        if (pixelsPerMeter >= gate) {
            val size = if (l.major) ART_MAJOR_TEXT_SP else ART_MINOR_TEXT_SP
            label(l.position, l.text, size, argb)
        }
    }
    if (pixelsPerMeter >= STREET_LABEL_ZOOM) {
        projected.streetLabels.forEach { l -> label(l.position, l.text, STREET_TEXT_SP, argb) }
    }
    if (pixelsPerMeter >= CPN_LABEL_ZOOM) {
        projected.cpnLabels.forEach { l -> label(l.position, l.text, CPN_TEXT_SP, argb) }
    }
}

private fun DrawScope.label(
    pos: Offset,
    text: String,
    sizeSp: Float,
    argb: Int,
) {
    drawIntoCanvas { canvas ->
        labelPaint.color = argb
        labelPaint.textSize = sizeSp * density
        canvas.nativeCanvas.drawText(
            text,
            pos.x,
            pos.y - LABEL_OFFSET_PX,
            labelPaint,
        )
    }
}
