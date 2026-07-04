package ai.openclaw.zodiaccontrol.ui.concepts

import androidx.compose.ui.graphics.Color

/**
 * Cockpit colour system (set 2026-07-04). Strong phosphor green carries all
 * chrome; blue and purple are the only accents that read well against it, amber
 * is banned, and red is reserved for genuine faults:
 *
 *  - [PhosphorGreen] — everything structural: controls, buttons, borders,
 *    labels, most text.
 *  - [StatusBlue] — limited, for status/state indicators only
 *    (link/connection/GPS state, "this is the selected control").
 *  - [DataPurple] — live data values (headings, speeds, ranges, clock,
 *    distances, gauge needles, the ego marker, map landmarks).
 *  - [AlertRed] — errors / extreme warnings ONLY.
 *  - [DimGreen] — de-emphasised green for secondary labels/notes.
 */
val PhosphorGreen = Color(0xFF00FF66)
val StatusBlue = Color(0xFF00BFFF)
val DataPurple = Color(0xFFC77DFF)
val DimGreen = Color(0xFF2C8A4A)
val AlertRed = Color(0xFFFF5555)

/**
 * Per-concept palette. Concepts currently share the same colour system (green
 * chrome / blue status / purple data / red error); the type stays per-concept
 * so a concept can diverge later without touching the others.
 *
 * @property primary green chrome (controls, borders, labels, text).
 * @property secondary blue status (limited).
 * @property accent purple data values.
 * @property dim de-emphasised green.
 * @property error red — faults/extreme warnings only.
 */
data class ConceptTheme(
    val background: Color,
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val dim: Color,
    val error: Color = AlertRed,
    /**
     * When true, shared chrome (currently the nav-cue bar) renders text
     * via [ai.openclaw.zodiaccontrol.ui.vectorText] — outlined glyphs
     * with a phosphor-bloom halo and a baseline beam trail.
     */
    val useVectorText: Boolean = false,
)

private val StandardCockpit =
    ConceptTheme(
        background = Color(0xFF000000),
        primary = PhosphorGreen,
        secondary = StatusBlue,
        accent = DataPurple,
        dim = DimGreen,
        error = AlertRed,
        useVectorText = true,
    )

val ThemeCrtVector = StandardCockpit
val ThemeTracker = StandardCockpit
val ThemeInstrumentBay = StandardCockpit
