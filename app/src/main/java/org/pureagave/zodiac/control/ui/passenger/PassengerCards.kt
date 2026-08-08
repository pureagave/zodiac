package org.pureagave.zodiac.control.ui.passenger

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.pureagave.zodiac.control.ui.RetroFont
import org.pureagave.zodiac.control.ui.concepts.ConceptTheme
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// The passenger cards. Every one follows the same shape — a small label, one
// enormous value, and an optional footnote — because these are read at two
// metres, at night, from an odd angle, by someone who is not looking for
// information so much as being handed it.
//
// The target hardware is LCD (Fire HD 8/10), so unlike the OLED driver HUD
// these can use large bright fills without any burn-in cost.

/** Label / huge value / footnote — the shared skeleton of every card. */
@Composable
fun passengerCardFrame(
    theme: ConceptTheme,
    label: String,
    value: String,
    footnote: String? = null,
    valueColor: Color = theme.accent,
    content: (@Composable () -> Unit)? = null,
) {
    // Type scales off the panel rather than being fixed. The fleet is a mix of
    // 8" and 10" Fires in either orientation, and a passenger display that
    // leaves half the glass empty on the big one — or overflows the small one —
    // is the difference between "installed" and "someone bothered".
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val shortest = if (maxWidth < maxHeight) maxWidth else maxHeight
        val base = (shortest.value * VALUE_FRACTION).coerceIn(MIN_VALUE_SP, MAX_VALUE_SP)
        // Long titles (art names run to 30+ characters) step down rather than
        // truncate — the name is the entire point of that card. Cheap
        // approximation instead of TextAutoSize, which postdates our Compose BOM.
        val valueSp = (base * lengthScale(value)).coerceAtLeast(MIN_VALUE_SP)
        val labelSp = (valueSp * LABEL_RATIO).coerceAtLeast(MIN_LABEL_SP)
        val footSp = (valueSp * FOOT_RATIO).coerceAtLeast(MIN_LABEL_SP)
        val gap = (shortest.value * GAP_FRACTION).dp

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label,
                color = theme.primary,
                fontFamily = RetroFont,
                fontSize = labelSp.sp,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(gap))
            Text(
                text = value,
                color = valueColor,
                fontFamily = RetroFont,
                fontSize = valueSp.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            if (content != null) {
                Spacer(Modifier.height(gap))
                content()
            }
            if (footnote != null) {
                Spacer(Modifier.height(gap))
                Text(
                    text = footnote,
                    color = theme.secondary,
                    fontFamily = RetroFont,
                    fontSize = footSp.sp,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Oscilloscope for the beacon mic. [rms] and [peak] are 0..1; [beat] flags an
 * onset in this frame.
 *
 * The trace is synthesised from the level rather than drawn from samples —
 * only a level number ever leaves the beacon phone, no audio is recorded or
 * transmitted, and that's a property worth keeping even when a real waveform
 * would look marginally better.
 */
@Composable
fun audioScope(
    theme: ConceptTheme,
    rms: Double,
    peak: Double,
    beat: Boolean,
    phase: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(SCOPE_HEIGHT)) {
        val midY = size.height / 2f
        val amp = (size.height / 2f) * rms.toFloat().coerceIn(0f, 1f)
        val path = Path()
        var x = 0f
        while (x <= size.width) {
            val t = x / size.width
            // Two detuned harmonics so the trace reads as a living waveform
            // instead of a pure sine, without pretending to be real audio.
            val y =
                midY +
                    amp * sin((t * WAVE_CYCLES + phase) * TWO_PI) *
                    (1f + HARMONIC * sin((t * WAVE_CYCLES * HARMONIC_RATIO + phase) * TWO_PI))
            if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
            x += SCOPE_STEP_PX
        }
        drawPath(path, color = if (beat) theme.accent else theme.primary, style = Stroke(width = SCOPE_STROKE))

        // Peak rails — the loudest the last frame reached, mirrored.
        val peakY = (size.height / 2f) * peak.toFloat().coerceIn(0f, 1f)
        listOf(midY - peakY, midY + peakY).forEach { y ->
            drawLine(
                color = theme.dim,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = RAIL_STROKE,
            )
        }
    }
}

/**
 * Nose-up ring of the contacts the edge box can see, as a passenger-facing
 * "what's around us" rather than a hazard display.
 *
 * Deliberately unlike the driver's ring: no collision flags, no braking, no
 * alarm colours. The driver's HUD owns safety, and a second screen implying
 * authority over it would split attention at exactly the wrong moment.
 */
@Composable
fun soulsRing(
    theme: ConceptTheme,
    bearingsDeg: List<Double>,
    sweepDeg: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(RING_HEIGHT)) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) / 2f - RING_PAD
        drawCircle(color = theme.dim, radius = radius, center = centre, style = Stroke(width = RING_STROKE))

        // Sweep arm — the only moving part, so the card reads as alive even
        // when nobody is near the vehicle.
        val sweepRad = Math.toRadians(sweepDeg.toDouble())
        drawLine(
            color = theme.primary,
            start = centre,
            end =
                Offset(
                    centre.x + (radius * sin(sweepRad)).toFloat(),
                    centre.y - (radius * cos(sweepRad)).toFloat(),
                ),
            strokeWidth = RING_STROKE,
        )

        bearingsDeg.forEach { bearing ->
            val rad = Math.toRadians(bearing)
            val blip =
                Offset(
                    centre.x + (radius * BLIP_RADIUS_FRAC * sin(rad)).toFloat(),
                    centre.y - (radius * BLIP_RADIUS_FRAC * cos(rad)).toFloat(),
                )
            // Brighten as the arm passes, so contacts pulse rather than sit.
            val delta = angularDistanceDeg(bearing, sweepDeg.toDouble())
            val lit = (1f - (delta / PULSE_ARC_DEG).toFloat()).coerceIn(BLIP_FLOOR, 1f)
            drawCircle(color = theme.accent.copy(alpha = lit), radius = BLIP_RADIUS, center = blip)
        }
    }
}

/**
 * How much to shrink the big value so a long string still fits two lines.
 * Tuned against the longest real inputs: art titles and street names.
 */
internal fun lengthScale(value: String): Float =
    when {
        value.length <= SHORT_VALUE -> 1f
        value.length >= LONG_VALUE -> LONG_VALUE_SCALE
        else -> 1f - (value.length - SHORT_VALUE).toFloat() / (LONG_VALUE - SHORT_VALUE) * (1f - LONG_VALUE_SCALE)
    }

/** Smallest angle between two bearings, in degrees. */
internal fun angularDistanceDeg(
    a: Double,
    b: Double,
): Double {
    val raw = abs(((a - b) % FULL_TURN + FULL_TURN) % FULL_TURN)
    return if (raw > HALF_TURN) FULL_TURN - raw else raw
}

private const val TWO_PI = (2 * Math.PI).toFloat()
private const val WAVE_CYCLES = 3.5f
private const val HARMONIC = 0.25f
private const val HARMONIC_RATIO = 2.7f
private const val SCOPE_STEP_PX = 6f
private const val SCOPE_STROKE = 4f
private const val RAIL_STROKE = 2f
private const val RING_STROKE = 3f
private const val RING_PAD = 8f
private const val BLIP_RADIUS = 9f
private const val BLIP_RADIUS_FRAC = 0.72f
private const val BLIP_FLOOR = 0.25f
private const val PULSE_ARC_DEG = 70.0
private const val FULL_TURN = 360.0
private const val HALF_TURN = 180.0
private const val VALUE_FRACTION = 0.15f
private const val MIN_VALUE_SP = 34f
private const val MAX_VALUE_SP = 120f
private const val LABEL_RATIO = 0.26f
private const val FOOT_RATIO = 0.22f
private const val MIN_LABEL_SP = 14f
private const val GAP_FRACTION = 0.035f
private const val SHORT_VALUE = 10
private const val LONG_VALUE = 30
private const val LONG_VALUE_SCALE = 0.45f
private val SCOPE_HEIGHT = 220.dp
private val RING_HEIGHT = 300.dp
