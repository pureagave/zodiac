package org.pureagave.zodiac.control.ui.passenger

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.pureagave.zodiac.control.core.geo.GoldenSpike
import org.pureagave.zodiac.control.core.geo.PlayaProjection
import org.pureagave.zodiac.control.core.ops.PlayaPoi
import org.pureagave.zodiac.control.core.ops.campGuidance
import org.pureagave.zodiac.control.core.passenger.CardReason
import org.pureagave.zodiac.control.core.passenger.CardRotation
import org.pureagave.zodiac.control.core.passenger.PassengerCard
import org.pureagave.zodiac.control.core.passenger.PassengerInputs
import org.pureagave.zodiac.control.core.passenger.PassengerView
import org.pureagave.zodiac.control.core.passenger.approachingArt
import org.pureagave.zodiac.control.core.passenger.artNearby
import org.pureagave.zodiac.control.core.passenger.availablePassengerCards
import org.pureagave.zodiac.control.core.passenger.passengerLocationLine
import org.pureagave.zodiac.control.core.telemetry.AudioLevel
import org.pureagave.zodiac.control.core.vision.VisionFeed
import org.pureagave.zodiac.control.data.art.ArtImageStore
import org.pureagave.zodiac.control.ui.RetroFont
import org.pureagave.zodiac.control.ui.concepts.ConceptTheme
import org.pureagave.zodiac.control.ui.state.CockpitUiState
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * The passenger display: a self-running carousel of what the vehicle currently
 * knows, aimed at the people riding on it rather than the person driving.
 *
 * Two rules shape everything here. **Nobody will touch it**, so it rotates on
 * its own and events interrupt the rotation. And **it must never imply
 * authority over the driver's display** — no collision flags, no braking, no
 * alarm red. The windscreen is the primary instrument and the driver's HUD is
 * the second; this is the third, and it is for delight and orientation only.
 *
 * Every card is fed by data already on the fleet bus, so any number of these
 * can run off one beacon and one edge box.
 */
@Composable
fun passengerScreen(
    state: CockpitUiState,
    theme: ConceptTheme,
    audio: AudioLevel?,
    now: LocalTime,
    sunrise: LocalTime?,
    sunset: LocalTime?,
    artImages: ArtImageStore? = null,
) {
    val rotation = remember { CardRotation() }
    var view by remember { mutableStateOf<PassengerView?>(null) }
    var tickMs by remember { mutableLongStateOf(0L) }
    var lastShockCount by remember { mutableStateOf<Double?>(null) }
    var lastStreet by remember { mutableStateOf<String?>(null) }

    val projection = remember { PlayaProjection(GoldenSpike.ACTIVE) }
    val egoPoint = state.egoFix?.location?.let(projection::project)
    val nearbyArt = artNearby(state.pois, egoPoint)
    // What we are driving *towards*, which is not the same as what is nearest —
    // the nearest piece is frequently one we have already gone past.
    val approach = approachingArt(state.pois, egoPoint, state.headingDeg.toDouble())
    // The card describes the piece we are meeting when there is one, and falls
    // back to the nearest when parked among several.
    val shownArt = approach?.poi ?: nearbyArt.firstOrNull()

    // Decode off the main thread, keyed by the piece — so approaching a new
    // one swaps the picture and nothing decodes twice.
    val nearestUid = shownArt?.uid
    var artImage by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(nearestUid, artImages) {
        val uid = nearestUid
        val store = artImages
        artImage = if (uid != null && store != null) store.load(uid) else null
    }

    val available = availablePassengerCards(passengerInputs(state, audio, nearbyArt.size))

    // Event interrupts: the things a passenger physically just experienced.
    val shockG = state.shockAlertG
    LaunchedEffect(shockG) {
        if (shockG != null && shockG != lastShockCount) {
            lastShockCount = shockG
            rotation.interruptWith(PassengerCard.BUMP, tickMs)
        }
    }
    val approachUid = approach?.poi?.uid
    LaunchedEffect(approachUid, tickMs) {
        // Re-arming every tick is what keeps the card up for the length of the
        // pass: interruptWith() resets the timer rather than stacking, so the
        // card holds while the piece is ahead and releases a few seconds after
        // it drops behind. No separate "am I still passing it" state needed.
        if (approachUid != null) rotation.interruptWith(PassengerCard.ART, tickMs)
    }

    val street = state.streetPopup
    LaunchedEffect(street) {
        if (street != null && street != lastStreet) {
            lastStreet = street
            rotation.interruptWith(PassengerCard.WHERE, tickMs)
        }
    }

    // Parked next to a piece is exactly when there's time to read it, so hold
    // the art card while the vehicle is stopped. Only art — holding the trip
    // counter or the sun clock at a standstill would just be a stuck screen.
    val stopped = (state.egoFix?.speedKph ?: state.speedKph.toDouble()) < STOPPED_KPH

    // CardRotation.view() *mutates* — it advances the rotation. It must
    // therefore be called exactly once per tick, never from a composable body,
    // where recomposition would step it several times a second and skip cards.
    // The ticker owns the state machine; composition only renders its output.
    val latest = rememberUpdatedState(Triple(available, stopped, nearbyArt.isNotEmpty()))
    LaunchedEffect(Unit) {
        while (true) {
            val (cards, isStopped, hasArt) = latest.value
            val hold = isStopped && hasArt && view?.card == PassengerCard.ART
            view = rotation.view(tickMs, cards, hold = hold)
            tickMs += TICK_MS
            delay(TICK_MS)
        }
    }

    // Continuous phase for the scope trace and the souls sweep. Driven by a
    // Compose transition rather than the rotation ticker so the animation is
    // smooth without recomposing the card tree at frame rate.
    val transition = rememberInfiniteTransition(label = "passenger")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PHASE_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(SWEEP_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )

    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val current = view
        if (current == null) {
            // Never a blank screen, and never a fake reading either.
            Text(
                text = "ZODIAC\nSTANDING BY",
                color = theme.dim,
                fontFamily = RetroFont,
                fontSize = STANDBY_SP.sp,
                textAlign = TextAlign.Center,
            )
            return@Box
        }

        passengerCard(
            view = current,
            theme = theme,
            ctx =
                PassengerContext(
                    state = state,
                    audio = audio,
                    nearbyArt = nearbyArt,
                    shownArt = shownArt,
                    approaching = approach != null,
                    approachAbeam = approach?.abeam == true,
                    artImage = artImage,
                    parked = stopped,
                    motion = PassengerMotion(phase, sweep),
                    sun = SunClock(now, sunrise, sunset),
                ),
        )
    }
}

/**
 * Continuous animation phases, bundled so the card dispatch doesn't take them
 * as loose floats. Both run on a Compose transition rather than the rotation
 * ticker, so the scope trace and the sweep stay smooth without recomposing the
 * card tree at frame rate.
 */
data class PassengerMotion(val phase: Float, val sweep: Float)

/** Wall clock plus today's sun events — the SUN card's whole input. */
data class SunClock(val now: LocalTime, val sunrise: LocalTime?, val sunset: LocalTime?)

/**
 * Everything the cards read, in one value. Bundled rather than passed as seven
 * parameters because the card set is expected to grow — the next card should
 * cost a field here, not another argument on every signature between the
 * screen and the drawing.
 */
data class PassengerContext(
    val state: CockpitUiState,
    val audio: AudioLevel?,
    val nearbyArt: List<PlayaPoi>,
    /** The piece the card is describing — what we're approaching, else nearest. */
    val shownArt: PlayaPoi?,
    /** True while a piece is ahead of us within approach range. */
    val approaching: Boolean,
    /** The approaching piece is off to one side — we're passing it, not nearing it. */
    val approachAbeam: Boolean,
    /** Pre-rendered, already-treated image for the shown piece; null if none. */
    val artImage: ImageBitmap?,
    /** Vehicle is stopped — the art card holds and shows its full description. */
    val parked: Boolean,
    val motion: PassengerMotion,
    val sun: SunClock,
)

/** Card dispatch, split out so [passengerScreen] stays about timing and state. */
@Composable
private fun passengerCard(
    view: PassengerView,
    theme: ConceptTheme,
    ctx: PassengerContext,
) {
    val urgent = view.reason == CardReason.INTERRUPT
    when (view.card) {
        PassengerCard.WHERE -> whereCard(ctx.state, theme, urgent)
        PassengerCard.AUDIO -> audioCard(theme, ctx.audio, ctx.motion.phase)
        PassengerCard.SOULS -> soulsCard(ctx.state, theme, ctx.motion.sweep)
        PassengerCard.BUMP -> bumpCard(ctx.state, theme, urgent)
        PassengerCard.TRIP -> tripCard(ctx.state, theme)
        PassengerCard.SUN -> sunCard(theme, ctx.sun)
        PassengerCard.ART -> artCard(ctx, theme)
    }
}

@Composable
private fun whereCard(
    state: CockpitUiState,
    theme: ConceptTheme,
    urgent: Boolean,
) {
    // Distance to whatever the driver is steering for, so a passenger gets
    // the same sense of progress the driver has.
    val target = state.activeDriveTarget
    val ego = state.egoFix?.location
    val away =
        if (target != null && ego != null) {
            campGuidance(ego, target.location, PlayaProjection(GoldenSpike.ACTIVE)).distanceM
        } else {
            null
        }
    passengerCardFrame(
        theme = theme,
        label = if (urgent) "NOW ENTERING" else "YOU ARE HERE",
        value = passengerLocationLine(state.navCue),
        footnote = target?.let { "${it.label.uppercase()}  ${formatKm(away)}" },
        valueColor = if (urgent) theme.primary else theme.accent,
    )
}

@Composable
private fun audioCard(
    theme: ConceptTheme,
    audio: AudioLevel?,
    phase: Float,
) {
    val level = audio ?: AudioLevel(rms = 0.0, peak = 0.0, beat = false)
    passengerCardFrame(
        theme = theme,
        label = "SOUND SYSTEM",
        value = "${(level.rms * PERCENT).roundToInt()}%",
        footnote = "MIC LEVEL ONLY — NO AUDIO IS RECORDED",
        valueColor = if (level.beat) theme.primary else theme.accent,
    ) {
        audioScope(theme = theme, rms = level.rms, peak = level.peak, beat = level.beat, phase = phase)
    }
}

@Composable
private fun soulsCard(
    state: CockpitUiState,
    theme: ConceptTheme,
    sweep: Float,
) {
    val bearings = state.threats.map { it.relAzDeg.toDouble() }
    passengerCardFrame(
        theme = theme,
        label = "SOULS DETECTED",
        value = "${bearings.size}",
        footnote = "THERMAL, ALL ROUND THE VEHICLE",
    ) {
        soulsRing(theme = theme, bearingsDeg = bearings, sweepDeg = sweep)
    }
}

@Composable
private fun bumpCard(
    state: CockpitUiState,
    theme: ConceptTheme,
    urgent: Boolean,
) {
    val g = state.shockAlertG ?: state.beaconReadout.shockG ?: 0.0
    passengerCardFrame(
        theme = theme,
        label = if (urgent) "THAT ONE" else "BUMP-O-METER",
        value = "%.1fg".format(g),
        footnote = bumpVerdict(g),
        valueColor = if (urgent) theme.primary else theme.accent,
    )
}

@Composable
private fun tripCard(
    state: CockpitUiState,
    theme: ConceptTheme,
) {
    val odo = state.odometer
    passengerCardFrame(
        theme = theme,
        label = "THIS BURN",
        value = formatKm(odo?.tripMeters),
        footnote = "LIFETIME ${formatKm(odo?.totalMeters)}",
    )
}

@Composable
private fun sunCard(
    theme: ConceptTheme,
    sun: SunClock,
) {
    val next = nextSunEvent(sun.now, sun.sunrise, sun.sunset)
    passengerCardFrame(
        theme = theme,
        label = next.first,
        value = next.second,
        footnote = "BLACK ROCK CITY",
    )
}

@Composable
private fun artCard(
    ctx: PassengerContext,
    theme: ConceptTheme,
) {
    val nearest = ctx.shownArt
    val artImage = ctx.artImage
    val parked = ctx.parked
    passengerCardFrame(
        theme = theme,
        label = artLabel(parked, ctx.approachAbeam, ctx.approaching),
        value = nearest?.name?.uppercase() ?: "—",
        // The artist and where they're from, then the piece in their own
        // words. "What IS that?" is the question people actually ask from a
        // moving art car, and the feed answers it for every piece — so the
        // card answers it rather than making anyone look it up later.
        footnote = artByline(nearest),
    ) {
        if (artImage != null) {
            Image(
                bitmap = artImage,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(IMAGE_WIDTH_FRACTION).heightIn(max = IMAGE_MAX_HEIGHT),
            )
            Spacer(Modifier.height(10.dp))
        }
        val blurb = nearest?.description
        if (!blurb.isNullOrBlank()) {
            Text(
                text = blurb.trim(),
                color = theme.primary,
                fontFamily = RetroFont,
                fontSize = BLURB_SP.sp,
                lineHeight = BLURB_LINE_SP.sp,
                textAlign = TextAlign.Center,
                // Parked, there's time to read the whole thing; moving, a
                // glance is all anyone gets, so cap it and don't tease.
                maxLines = if (parked) BLURB_LINES_PARKED else BLURB_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(BLURB_WIDTH_FRACTION),
            )
        }
        val tags = artTags(nearest, ctx.nearbyArt.size)
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = tags.joinToString("   "),
                color = theme.secondary,
                fontFamily = RetroFont,
                fontSize = TAG_SP.sp,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The short chips under the description. Deliberately sparse: a passenger
 * glancing up wants to know *where it is* and whether they can go in, not how
 * it was funded.
 *
 * Category ("Open Playa"), programme ("Self-Funded") and the volunteer call
 * were all dropped — they read as filler next to the artist's own words, and
 * the slot is reserved for the piece's **address**, which is the one fact a
 * passenger can act on. That is null across the whole 2026 feed today; it takes
 * this slot automatically the moment BM publishes placements.
 */
internal fun artTags(
    poi: PlayaPoi?,
    nearbyCount: Int,
): List<String> {
    if (poi == null) return emptyList()
    return buildList {
        poi.address?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
        if (poi.guidedTours) add("GUIDED TOURS")
        if (poi.selfGuidedTour) add("ON THE TOUR MAP")
        if (nearbyCount > 1) add("+${nearbyCount - 1} MORE NEARBY")
    }
}

/** The availability inputs, split out to keep [passengerScreen] under its complexity budget. */
private fun passengerInputs(
    state: CockpitUiState,
    audio: AudioLevel?,
    artCount: Int,
): PassengerInputs =
    PassengerInputs(
        hasFix = state.egoFix != null,
        hasAudio = audio != null,
        visionLive = state.visionFeed == VisionFeed.LIVE,
        hasShock = state.shockAlertG != null || state.beaconReadout.shockG != null,
        odometer = state.odometer,
        artAhead = artCount,
    )

/**
 * What the card is telling you, which depends entirely on where the piece is
 * relative to the nose. Parked wins over everything — a stopped vehicle next to
 * a piece is the clearest case there is.
 */
internal fun artLabel(
    parked: Boolean,
    abeam: Boolean,
    approaching: Boolean,
): String =
    when {
        parked -> "YOU ARE PARKED AT"
        abeam -> "PASSING"
        approaching -> "COMING UP"
        else -> "ART NEARBY"
    }

/** `ABRAM SANTA CRUZ · LONG BEACH, CA` — artist first, since that's who to thank. */
internal fun artByline(poi: PlayaPoi?): String {
    if (poi == null) return "NOTHING PLACED NEARBY YET"
    val parts = listOfNotNull(poi.subtitle.takeIf { it.isNotBlank() }, poi.hometown?.takeIf { it.isNotBlank() })
    return if (parts.isEmpty()) "ARTIST UNKNOWN" else parts.joinToString("  ·  ").uppercase()
}

/** Playful reading of an impact magnitude. Deliberately not a safety judgement. */
internal fun bumpVerdict(g: Double): String =
    when {
        g >= BUMP_BIG -> "HOLD ON TO SOMETHING"
        g >= BUMP_MED -> "THAT'S THE PLAYA"
        g > 0.0 -> "SMOOTH SAILING"
        else -> "NO BUMPS YET"
    }

/**
 * The next sun event and how long until it, as (label, countdown). Sunrise and
 * sunset are the two moments the whole city organises itself around.
 */
internal fun nextSunEvent(
    now: LocalTime,
    sunrise: LocalTime?,
    sunset: LocalTime?,
): Pair<String, String> {
    val candidates =
        listOfNotNull(
            sunrise?.let { "SUNRISE IN" to it },
            sunset?.let { "SUNSET IN" to it },
        ).filter { it.second.isAfter(now) }
    val next = candidates.minByOrNull { it.second } ?: return "SUN" to "—"
    val minutes = (next.second.toSecondOfDay() - now.toSecondOfDay()) / SECONDS_PER_MINUTE
    return next.first to "${minutes / MINUTES_PER_HOUR}H ${minutes % MINUTES_PER_HOUR}M"
}

/** Metres as a passenger-facing distance: `47.3 KM`, or an em dash when unknown. */
internal fun formatKm(meters: Double?): String = meters?.let { "%.1f KM".format(it / METERS_PER_KM) } ?: "—"

private const val TICK_MS = 1_000L
private const val PHASE_MS = 2_200
private const val SWEEP_MS = 6_000
private const val PERCENT = 100
private const val STANDBY_SP = 40
private const val BUMP_BIG = 1.5
private const val BUMP_MED = 0.6
private const val SECONDS_PER_MINUTE = 60
private const val MINUTES_PER_HOUR = 60
private const val METERS_PER_KM = 1_000.0
private const val BLURB_SP = 17
private const val BLURB_LINE_SP = 24
private const val BLURB_LINES = 4
private const val BLURB_LINES_PARKED = 10
private const val TAG_SP = 15
private const val IMAGE_WIDTH_FRACTION = 0.55f
private val IMAGE_MAX_HEIGHT = 240.dp
private const val STOPPED_KPH = 1.5
private const val BLURB_WIDTH_FRACTION = 0.82f
