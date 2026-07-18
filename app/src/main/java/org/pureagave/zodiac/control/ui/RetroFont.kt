package org.pureagave.zodiac.control.ui

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import org.pureagave.zodiac.control.R

/**
 * Orbitron — a geometric retro-futurism typeface (SIL OFL, see
 * `licenses/Orbitron-OFL.txt`). Bundled as a single variable font; the weights
 * below are pulled from its `wght` axis. Used for the address keypad / street
 * picker / heading flash to give those glance-and-tap surfaces a sci-fi feel
 * distinct from the monospace terminal chrome.
 */
@OptIn(ExperimentalTextApi::class)
val RetroFont: FontFamily =
    FontFamily(
        Font(R.font.orbitron, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(WEIGHT_MEDIUM))),
        Font(R.font.orbitron, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(WEIGHT_BOLD))),
        Font(R.font.orbitron, weight = FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(WEIGHT_BLACK))),
    )

private const val WEIGHT_MEDIUM = 500
private const val WEIGHT_BOLD = 700
private const val WEIGHT_BLACK = 900
