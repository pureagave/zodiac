package ai.openclaw.zodiaccontrol.burnin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val TWO_PI = 2f * Math.PI.toFloat()

/** Distinct y-rate so the shift traces a slow Lissajous path, not a line. */
private const val SHIFT_Y_RATE = 0.5f

/**
 * Wraps the cockpit dispatch with OLED burn-in mitigation, driven by
 * [manager]. Applies (in this order):
 *  - a non-consuming touch observer that reports activity (pointer Initial
 *    pass — children still receive every event, so map gestures are unaffected);
 *  - a whole-UI pixel-shift offset (placement phase only — no recomposition);
 *  - an OLED-only content-brightness layer that breathes in [BurnInPhase.ACTIVE]
 *    and dims in idle phases (skipped on LCD via [BurnInDeviceProfile]);
 *  - per-phase window backlight + a held `FLAG_KEEP_SCREEN_ON`.
 *
 * The frame ticker [tSec] is read only inside the `offset`/`graphicsLayer`
 * lambdas, never in the composable body, so the 60–120 fps animation
 * invalidates the layout/draw phase only — matching the codebase's
 * recomposition-storm avoidance (see the Concept-C sweep).
 */
@Composable
fun burnInScaffold(
    manager: BurnInMitigationManager,
    content: @Composable () -> Unit,
) {
    val phase by manager.phase.collectAsStateWithLifecycle()
    val config by manager.config.collectAsStateWithLifecycle()
    val visualEnabled = config.visualModulationEnabled && BurnInDeviceProfile.visualModulationSupported()

    val context = LocalContext.current

    // Hold the screen on for the whole cockpit lifetime; restore system
    // brightness and release the flag on teardown.
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.let {
                it.attributes =
                    it.attributes.apply {
                        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                it.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // Per-phase backlight. Cheap window attribute, applied on every device
    // (the LCD Fire still benefits from idle backlight stepping for power).
    LaunchedEffect(phase, config) {
        val window = context.findActivity()?.window ?: return@LaunchedEffect
        window.attributes =
            window.attributes.apply { screenBrightness = backlightFor(phase, config) }
    }

    val tSec = remember { mutableFloatStateOf(0f) }
    val animating = phase != BurnInPhase.SLEEP
    LaunchedEffect(animating) {
        if (!animating) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) tSec.floatValue += (now - last) / 1e9f
                last = now
            }
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .burnInActivityObserver(manager::onUserInteraction),
    ) {
        if (phase == BurnInPhase.SLEEP) return@Box // app-drawn black — OLED pixels off

        val shiftEnabled = config.pixelShiftEnabled
        val shifted =
            Modifier
                .fillMaxSize()
                .offset { if (shiftEnabled) pixelShift(tSec.floatValue, config) else IntOffset.Zero }

        if (phase == BurnInPhase.DEEP_IDLE) {
            standbyScreen(modifier = shifted)
        } else {
            Box(
                modifier =
                    shifted.then(
                        if (visualEnabled) {
                            Modifier.graphicsLayer {
                                alpha = contentAlpha(phase, tSec.floatValue, config)
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                content()
            }
        }
    }
}

private fun Modifier.burnInActivityObserver(onInteraction: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial)
                onInteraction()
            }
        }
    }

private fun pixelShift(
    tSec: Float,
    config: BurnInConfig,
): IntOffset {
    val amp = config.pixelShiftAmplitudePx
    if (amp <= 0) return IntOffset.Zero
    val angle = tSec / config.pixelShiftPeriodSec.coerceAtLeast(1) * TWO_PI
    return IntOffset(
        x = (amp * sin(angle)).roundToInt(),
        y = (amp * cos(angle * SHIFT_Y_RATE)).roundToInt(),
    )
}

/** Subtle downward brightness breathe in [BurnInPhase.ACTIVE]: oscillates (1−amp)..1. */
private fun breathe(
    tSec: Float,
    config: BurnInConfig,
): Float {
    val s = 0.5f - 0.5f * cos(tSec / config.breathePeriodSec.coerceAtLeast(1) * TWO_PI)
    return 1f - config.breatheAmplitude * s
}

private fun contentAlpha(
    phase: BurnInPhase,
    tSec: Float,
    config: BurnInConfig,
): Float =
    when (phase) {
        BurnInPhase.ACTIVE -> breathe(tSec, config)
        BurnInPhase.DIM -> config.dimContentAlpha
        // DEEP_IDLE / SLEEP never reach this layer — the scaffold routes them to
        // the standby screen / pure black before applying content alpha.
        BurnInPhase.DEEP_IDLE -> config.deepIdleBacklight
        BurnInPhase.SLEEP -> 0f
    }

private fun backlightFor(
    phase: BurnInPhase,
    config: BurnInConfig,
): Float =
    when (phase) {
        BurnInPhase.ACTIVE -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        BurnInPhase.DIM -> config.dimBacklight
        BurnInPhase.DEEP_IDLE -> config.deepIdleBacklight
        BurnInPhase.SLEEP -> config.sleepBacklight
    }

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
