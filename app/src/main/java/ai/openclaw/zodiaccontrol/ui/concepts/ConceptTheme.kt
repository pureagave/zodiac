package ai.openclaw.zodiaccontrol.ui.concepts

import androidx.compose.ui.graphics.Color

/**
 * Per-concept palette. Each concept screen reads from one of these so chip
 * tints, borders, and text colors stay coherent inside that concept's
 * aesthetic without baking colors into every Composable.
 */
data class ConceptTheme(
    val background: Color,
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val dim: Color,
    /**
     * When true, shared chrome (currently the nav-cue bar) renders text
     * via [ai.openclaw.zodiaccontrol.ui.vectorText] — outlined glyphs
     * with a phosphor-bloom halo and a baseline beam trail — to match
     * the host concept's vector-monitor aesthetic. Off everywhere by
     * default; concept A opts in.
     */
    val useVectorText: Boolean = false,
)

val ThemeCrtVector =
    ConceptTheme(
        background = Color(0xFF000000),
        primary = Color(0xFF00FF66),
        secondary = Color(0xFF00BFFF),
        accent = Color(0xFFFFD166),
        dim = Color(0xFF2C8A4A),
        useVectorText = true,
    )

val ThemeTracker =
    ConceptTheme(
        background = Color(0xFF000000),
        primary = Color(0xFF7EFF62),
        secondary = Color(0xFF9DFF7A),
        accent = Color(0xFFFFF700),
        dim = Color(0xFF3AAA2C),
        useVectorText = true,
    )

// Concept D was originally amber/orange ("Nostromo"); recoloured to the green
// phosphor family 2026-07-04. Its distinct gauge-wall layout keeps it separate
// from Concept A. Amber accent retained for gauge needles/values to pop.
val ThemeInstrumentBay =
    ConceptTheme(
        background = Color(0xFF000000),
        primary = Color(0xFF00FF66),
        secondary = Color(0xFF00BFFF),
        accent = Color(0xFFFFD166),
        dim = Color(0xFF2C8A4A),
        useVectorText = true,
    )
