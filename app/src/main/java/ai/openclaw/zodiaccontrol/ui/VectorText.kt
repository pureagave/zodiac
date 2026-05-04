package ai.openclaw.zodiaccontrol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit

/**
 * Atari-vector aesthetic text. Renders [text] as outlined glyph
 * strokes (Compose's `drawStyle = Stroke(...)` on the underlying text
 * style) instead of filled letters, with two extras layered behind:
 *
 *   1. A wide / dim halo pass — same glyph paths re-stroked at ~3× the
 *      sharp width, low alpha. Fakes the phosphor bloom you'd see
 *      around every letter on a real vector monitor.
 *   2. A faint baseline-level trail line spanning the full width of
 *      the rendered text. On a vector monitor the electron beam
 *      sweeps along the same horizontal-ish path as it moves between
 *      glyphs and isn't perfectly blanked, leaving a ghost line —
 *      this is the cheapest way to reproduce that visual cue without
 *      tracking per-glyph beam paths.
 *
 * Colour is applied at draw time. Text is measured once via
 * [rememberTextMeasurer] and remembered keyed on `(text, style)`, so
 * recompositions caused by unrelated state never re-measure glyphs.
 */
@Composable
fun vectorText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight = FontWeight.Bold,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val style =
        remember(fontSize, fontWeight) {
            TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize,
                fontWeight = fontWeight,
            )
        }
    val layout = remember(text, style) { measurer.measure(AnnotatedString(text), style) }
    val density = LocalDensity.current
    val widthDp = with(density) { layout.size.width.toDp() }
    val heightDp = with(density) { layout.size.height.toDp() }

    Canvas(modifier = modifier.size(width = widthDp, height = heightDp)) {
        // Halo: same glyph outlines, much wider stroke, low alpha. The
        // bloom band that surrounds the sharp letters.
        drawText(
            textLayoutResult = layout,
            color = color.copy(alpha = HALO_ALPHA),
            drawStyle = Stroke(width = HALO_STROKE_PX, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        // Sharp core: thin stroke, full alpha.
        drawText(
            textLayoutResult = layout,
            color = color,
            drawStyle = Stroke(width = SHARP_STROKE_PX, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        // Baseline trail: a faint horizontal line at the layout's first
        // baseline, spanning the full width of the rendered text. Reads
        // as the beam ghost-trail between letters.
        val baselineY = layout.firstBaseline
        drawLine(
            color = color.copy(alpha = TRAIL_ALPHA),
            start = Offset(x = 0f, y = baselineY),
            end = Offset(x = layout.size.width.toFloat(), y = baselineY),
            strokeWidth = TRAIL_STROKE_PX,
        )
    }
}

private const val HALO_ALPHA = 0.40f
private const val HALO_STROKE_PX = 4.0f
private const val SHARP_STROKE_PX = 1.0f
private const val TRAIL_ALPHA = 0.45f
private const val TRAIL_STROKE_PX = 0.7f
