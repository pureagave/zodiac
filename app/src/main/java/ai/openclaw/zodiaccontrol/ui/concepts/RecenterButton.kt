package ai.openclaw.zodiaccontrol.ui.concepts

import ai.openclaw.zodiaccontrol.core.model.FollowMode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * "Where am I" button — a triangular ego icon themed to the host concept.
 * Tap to call [onClick], which the cockpit wires to
 * `viewModel.recenterPan()`. Border is the concept's accent in
 * [FollowMode.FREE] (the cockpit has detached from the ego, button is the
 * way back) and the dim color in [FollowMode.TRACK_UP] (already centred,
 * no work to do — but kept tappable so the user can resync rotation any
 * time). The marker inside is always the accent so the button is
 * recognisable as "the ego."
 */
@Composable
fun recenterButton(
    followMode: FollowMode,
    theme: ConceptTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = followMode == FollowMode.FREE
    val borderColor = if (active) theme.accent else theme.dim
    Box(
        modifier =
            modifier
                .size(BUTTON_SIZE.dp)
                .background(Color(0xCC000000))
                .border(BORDER_WIDTH.dp, borderColor)
                .clickable(onClick = onClick),
    ) {
        Canvas(modifier = Modifier.size(BUTTON_SIZE.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val s = size.width.coerceAtMost(size.height) * TRIANGLE_SCALE
            val tip = Offset(cx, cy - s)
            val left = Offset(cx - s * 0.6f, cy + s * 0.7f)
            val right = Offset(cx + s * 0.6f, cy + s * 0.7f)
            val path =
                Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(right.x, right.y)
                    lineTo(left.x, left.y)
                    close()
                }
            drawPath(path = path, color = theme.accent)
            drawPath(path = path, color = Color.White, style = Stroke(width = 2f))
        }
    }
}

private const val BUTTON_SIZE: Int = 56
private const val BORDER_WIDTH: Int = 2
private const val TRIANGLE_SCALE: Float = 0.30f
