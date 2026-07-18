package org.pureagave.zodiac.control.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke

private val ScanlineColor = Color(0x1100FF66)
private const val SCANLINE_STEP_PX = 4f
private const val SCANLINE_STROKE_PX = 1f

/**
 * Full-screen CRT scanline chrome — a faint horizontal-line lattice that
 * sits over every concept. Static given the canvas size, so we build the
 * lattice once into a single multi-subpath [Path] via [drawWithCache] and
 * blit it as one `drawPath` call per frame. Previously each frame stamped
 * ~270 individual `drawLine` calls at 1080p, even when nothing on screen
 * was moving — this drops it to one Skia call regardless of resolution.
 *
 * The cache rebuilds only when the canvas size changes (i.e. effectively
 * never, the cockpit is landscape full-screen).
 */
@Composable
fun scanlineOverlay() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val path =
                        Path().apply {
                            var y = 0f
                            while (y <= size.height) {
                                moveTo(0f, y)
                                lineTo(size.width, y)
                                y += SCANLINE_STEP_PX
                            }
                        }
                    onDrawBehind {
                        drawPath(path = path, color = ScanlineColor, style = Stroke(width = SCANLINE_STROKE_PX))
                    }
                },
    )
}
