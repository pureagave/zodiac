package org.pureagave.zodiac.control.ui.playamap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import org.pureagave.zodiac.control.core.model.PlayaMap

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
 * Pre-measured glyph layouts for every label site on the playa, parallel
 * by index to the corresponding `*LabelSeeds` lists on [PlayaMap]. Built
 * once when the map and screen density become available, then reused
 * frame-to-frame — even at high zoom where 100+ labels are visible the
 * per-frame cost collapses from "measure every glyph in every label" to
 * "blit pre-laid-out glyphs into the Skia text buffer."
 *
 * Layouts are color-agnostic: they're measured with the default
 * [TextStyle] color (Color.Unspecified) and the actual palette colour is
 * overridden at [drawText] time. That lets the cache survive a concept
 * switch (which only changes [MapPalette.labelPrimary]) without rebuild.
 */
@Immutable
data class LabelLayouts(
    val plaza: List<TextLayoutResult>,
    val art: List<TextLayoutResult>,
    val street: List<TextLayoutResult>,
    val cpn: List<TextLayoutResult>,
) {
    companion object {
        val Empty = LabelLayouts(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/**
 * Build (or fetch the cached) [LabelLayouts] for [map] at the current
 * screen density. Returns [LabelLayouts.Empty] when [labelsEnabled] is
 * false so concepts that don't draw labels don't pay the up-front
 * measurement cost.
 *
 * Cache key is `(map, density, labelsEnabled)`. Palette colour changes
 * (e.g. concept switch within an enabled-labels family) don't invalidate
 * — colour is applied at draw time, not measure time.
 */
@Composable
fun rememberLabelLayouts(
    map: PlayaMap?,
    labelsEnabled: Boolean,
): LabelLayouts {
    if (map == null || !labelsEnabled) return LabelLayouts.Empty
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(map, density) {
        buildLabelLayouts(map, measurer, density)
    }
}

private fun buildLabelLayouts(
    map: PlayaMap,
    measurer: TextMeasurer,
    density: Density,
): LabelLayouts {
    val style =
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = PLAZA_TEXT_SP.sp,
        )

    fun layout(
        text: String,
        sizeSp: Float,
    ): TextLayoutResult =
        measurer.measure(
            text = AnnotatedString(text),
            style = style.copy(fontSize = sizeSp.sp),
            density = density,
        )
    return LabelLayouts(
        plaza = map.plazaLabelSeeds.map { layout(it.text, PLAZA_TEXT_SP) },
        art = map.artLabelSeeds.map { layout(it.text, if (it.major) ART_MAJOR_TEXT_SP else ART_MINOR_TEXT_SP) },
        street = map.streetLabelSeeds.map { layout(it.text, STREET_TEXT_SP) },
        cpn = map.cpnLabelSeeds.map { layout(it.text, CPN_TEXT_SP) },
    )
}

/**
 * Zoom-gated label pass over a pre-projected map. Plaza names always show
 * once the city is zoomed past overview; arc/radial street names appear
 * at medium zoom; CPNs at higher zoom; art names tier by program (major /
 * minor). Toilets are intentionally unlabeled — the source data has no
 * per-bank name and the marker colour carries the meaning.
 *
 * Each label's [TextLayoutResult] was pre-measured by [rememberLabelLayouts]
 * and is just blitted here — no per-frame glyph layout, no native Paint
 * fiddling. The [color] override lets the same cache serve every concept
 * palette.
 */
fun DrawScope.drawProjectedLabels(
    projected: ProjectedMap,
    layouts: LabelLayouts,
    color: Color,
    pixelsPerMeter: Double,
) {
    if (pixelsPerMeter >= PLAZA_LABEL_ZOOM) drawLayer(projected.plazaLabels, layouts.plaza, color)
    drawArtLayer(projected.artLabels, layouts.art, color, pixelsPerMeter)
    if (pixelsPerMeter >= STREET_LABEL_ZOOM) drawLayer(projected.streetLabels, layouts.street, color)
    if (pixelsPerMeter >= CPN_LABEL_ZOOM) drawLayer(projected.cpnLabels, layouts.cpn, color)
}

private fun DrawScope.drawLayer(
    labels: List<ProjectedLabel>,
    layouts: List<TextLayoutResult>,
    color: Color,
) {
    val n = minOf(labels.size, layouts.size)
    for (i in 0 until n) drawCenteredText(labels[i].position, layouts[i], color)
}

private fun DrawScope.drawArtLayer(
    labels: List<ProjectedLabel>,
    layouts: List<TextLayoutResult>,
    color: Color,
    pixelsPerMeter: Double,
) {
    val n = minOf(labels.size, layouts.size)
    for (i in 0 until n) {
        val gate = if (labels[i].major) ART_MAJOR_LABEL_ZOOM else ART_MINOR_LABEL_ZOOM
        if (pixelsPerMeter >= gate) drawCenteredText(labels[i].position, layouts[i], color)
    }
}

/**
 * Draw [layout] horizontally centered on [pos.x] with the baseline at
 * `pos.y - LABEL_OFFSET_PX` — the same anchor the previous native
 * `Paint.Align.CENTER` + `drawText(text, x, y, paint)` produced.
 */
private fun DrawScope.drawCenteredText(
    pos: Offset,
    layout: TextLayoutResult,
    color: Color,
) {
    drawText(
        textLayoutResult = layout,
        color = color,
        topLeft =
            Offset(
                x = pos.x - layout.size.width / 2f,
                y = pos.y - LABEL_OFFSET_PX - layout.firstBaseline,
            ),
    )
}
