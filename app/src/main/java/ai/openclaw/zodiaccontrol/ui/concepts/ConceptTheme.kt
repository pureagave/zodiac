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
)

val ThemeCrtVector =
    ConceptTheme(
        background = Color(0xFF000000),
        primary = Color(0xFF00FF66),
        secondary = Color(0xFF00BFFF),
        accent = Color(0xFFFFD166),
        dim = Color(0xFF2C8A4A),
    )

val ThemePerspective =
    ConceptTheme(
        background = Color(0xFF000000),
        primary = Color(0xFF00FF66),
        secondary = Color(0xFF9DFF7A),
        accent = Color(0xFFFFF700),
        dim = Color(0xFF2C8A4A),
    )

val ThemeTracker =
    ConceptTheme(
        background = Color(0xFF000000),
        primary = Color(0xFF7EFF62),
        secondary = Color(0xFF9DFF7A),
        accent = Color(0xFFFFF700),
        dim = Color(0xFF3AAA2C),
    )

val ThemeInstrumentBay =
    ConceptTheme(
        background = Color(0xFF0A0500),
        primary = Color(0xFFFF8A00),
        secondary = Color(0xFFFFAE3A),
        accent = Color(0xFFFFD166),
        dim = Color(0xFFA36000),
    )
