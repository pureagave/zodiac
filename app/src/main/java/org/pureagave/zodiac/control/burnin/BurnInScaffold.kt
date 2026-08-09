package org.pureagave.zodiac.control.burnin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pureagave.zodiac.control.ui.state.luxToBrightness
import timber.log.Timber
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Corner hot-zone size for the park / tuning long-press gestures. */
private val HOT_ZONE = 72.dp

private const val TWO_PI = 2.0 * Math.PI

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
 * The frame ticker (`elapsedNanos`) is read only inside the `offset`/
 * `graphicsLayer` lambdas, never in the composable body, so the 60–120 fps
 * animation invalidates the layout/draw phase only — matching the codebase's
 * recomposition-storm avoidance (see the Concept-C sweep).
 */
@Composable
fun burnInScaffold(
    manager: BurnInMitigationManager,
    /**
     * Label for the layout currently on screen (the active concept). Only used
     * to key the burn-in ledger — within one concept the chrome that risks
     * burning is the same pixels the whole time it's displayed.
     */
    zone: String = "cockpit",
    /**
     * Current ambient lux from the beacon's `$ZENV` (null when no beacon feed).
     * Burn-in is the single writer of `window.attributes.screenBrightness`; this
     * is folded into that write via [effectiveBacklight] rather than left to a
     * second, uncoordinated writer (see [effectiveBacklight] for why).
     */
    ambientLux: Double?,
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

    // The single write site for window.attributes.screenBrightness: burn-in's
    // per-phase ceiling folded with the beacon's ambient-lux curve via
    // effectiveBacklight (see its kdoc). A second writer — e.g. an auto-dim
    // effect keyed on ambientLux alone — would re-fire on every lux tick and
    // override DIM/DEEP_IDLE/SLEEP straight back to the lux curve, which is
    // exactly the bug this replaces.
    LaunchedEffect(phase, config, ambientLux) {
        val window = context.findActivity()?.window ?: return@LaunchedEffect
        window.attributes =
            window.attributes.apply { screenBrightness = effectiveBacklight(phase, config, ambientLux) }
    }

    var tuningOpen by remember { mutableStateOf(false) }

    // Burn-in stress ledger. Costs a map lookup per (zone, phase) transition
    // and nothing per frame; the running total goes to the rolling log so the
    // record survives reboots without another persistence layer.
    val ledger = remember { BurnInLedger { SystemClock.elapsedRealtime() } }
    LaunchedEffect(zone, phase) {
        ledger.mark("$zone/$phase")
        Timber.i(ledger.report())
    }

    // Long-nanos baseline, not a Float-seconds accumulator: a Float's ULP
    // exceeds a frame delta at t ~ 2^19 s (~6.1 days), so `+=` would round to
    // zero every frame and the animation would freeze partway through a 14-day
    // burn. elapsedNanos is derived (now - startNanos) rather than summed, and
    // each consumer takes its own modulo (phaseFraction) before narrowing to
    // Double/Float, so precision never depends on uptime.
    val startNanos = remember { mutableLongStateOf(0L) }
    val elapsedNanos = remember { mutableLongStateOf(0L) }
    val animating = phase != BurnInPhase.SLEEP
    LaunchedEffect(animating) {
        if (!animating) return@LaunchedEffect
        while (true) {
            withFrameNanos { now ->
                if (startNanos.longValue == 0L) startNanos.longValue = now
                elapsedNanos.longValue = now - startNanos.longValue
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
                .offset { if (shiftEnabled) pixelShift(elapsedNanos.longValue, config) else IntOffset.Zero }

        if (phase == BurnInPhase.DEEP_IDLE) {
            standbyScreen(modifier = shifted)
        } else {
            Box(
                modifier =
                    shifted.then(
                        if (visualEnabled) {
                            Modifier.graphicsLayer {
                                alpha = contentAlpha(phase, elapsedNanos.longValue, config)
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

        // Hidden corner gestures (non-interactive title/rail corners in every
        // concept): top-left long-press parks, bottom-left opens tuning.
        Box(Modifier.align(Alignment.TopStart).size(HOT_ZONE).cornerLongPress(manager::enterPark))
        Box(Modifier.align(Alignment.BottomStart).size(HOT_ZONE).cornerLongPress { tuningOpen = true })

        if (tuningOpen) {
            burnInTuningPanel(
                config = config,
                onConfig = manager::updateConfig,
                onPark = manager::enterPark,
                onWake = manager::wake,
                onClose = { tuningOpen = false },
            )
        }
    }
}

private fun Modifier.cornerLongPress(onLongPress: () -> Unit): Modifier =
    pointerInput(Unit) {
        detectTapGestures(onLongPress = { onLongPress() })
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

/**
 * Fraction of one [periodSec] cycle elapsed at [elapsedNanos], always in
 * `[0, 1)`. The modulo is taken in nanos (Long, exact for any uptime up to
 * 292 years) before narrowing to Double, so the value fed to trig downstream
 * stays small regardless of how long the process has been running — that's
 * what fixes the freeze in [pixelShift]/[breathe] (see kdoc on the caller in
 * [burnInScaffold]). Each consumer has its own period, so a single shared
 * modulo across pixel-shift and breathe would be wrong.
 */
internal fun phaseFraction(
    elapsedNanos: Long,
    periodSec: Int,
): Double {
    val periodNanos = periodSec.coerceAtLeast(1).toLong() * 1_000_000_000L
    return (elapsedNanos % periodNanos).toDouble() / periodNanos
}

internal fun pixelShift(
    elapsedNanos: Long,
    config: BurnInConfig,
): IntOffset {
    val amp = config.pixelShiftAmplitudePx
    if (amp <= 0) return IntOffset.Zero
    val angle = phaseFraction(elapsedNanos, config.pixelShiftPeriodSec) * TWO_PI
    return IntOffset(
        x = (amp * sin(angle)).roundToInt(),
        y = (amp * cos(angle * SHIFT_Y_RATE)).roundToInt(),
    )
}

/** Subtle downward brightness breathe in [BurnInPhase.ACTIVE]: oscillates (1−amp)..1. */
private fun breathe(
    elapsedNanos: Long,
    config: BurnInConfig,
): Float {
    val s = 0.5 - 0.5 * cos(phaseFraction(elapsedNanos, config.breathePeriodSec) * TWO_PI)
    return (1.0 - config.breatheAmplitude * s).toFloat()
}

internal fun contentAlpha(
    phase: BurnInPhase,
    elapsedNanos: Long,
    config: BurnInConfig,
): Float =
    when (phase) {
        BurnInPhase.ACTIVE -> breathe(elapsedNanos, config)
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

/**
 * The single arbiter for `window.attributes.screenBrightness`: burn-in's
 * per-phase ceiling (from [backlightFor]) folded with the beacon's ambient-lux
 * curve ([luxToBrightness]). Burn-in is a *ceiling* that can only reduce
 * brightness below what auto-dim would otherwise pick; auto-dim governs
 * outright in ACTIVE, where [backlightFor] already returns
 * `BRIGHTNESS_OVERRIDE_NONE`.
 *
 * - **Burn-in never brightens.** In DIM/DEEP_IDLE/SLEEP the result is the
 *   `min` of the two, so a dark room (lux floor 0.05) still wins over DIM's
 *   larger backlight, and SLEEP's 0.01 can never be raised by a lux tick.
 * - **ACTIVE defers to auto-dim** entirely.
 * - **No beacon feed → burn-in alone.** A tablet with no `$ZENV` still gets
 *   its phase backlight rather than falling back to system brightness.
 */
internal fun effectiveBacklight(
    phase: BurnInPhase,
    config: BurnInConfig,
    ambientLux: Double?,
): Float {
    val burnIn = backlightFor(phase, config) // NONE (-1f) in ACTIVE
    val lux = ambientLux?.let { luxToBrightness(it) } // null when no beacon feed
    return when {
        burnIn == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE ->
            lux ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        lux == null -> burnIn
        else -> minOf(lux, burnIn)
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
