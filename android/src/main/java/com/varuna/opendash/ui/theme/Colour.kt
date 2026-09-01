package com.varuna.opendash.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * An instrument-cluster palette rather than the Material default.
 *
 * Amber on near-black is what a car reads like at night, and it is the reason
 * dark is the default here: this app gets used in a footwell at dusk far more
 * often than at a desk. The light scheme exists because some people want it,
 * and because a screenshot in a bug report is easier to read.
 *
 * The two schemes are defined in full, side by side, so a colour can never be
 * legible in one and invisible in the other without it being obvious here.
 */

// Dark
val Ink = Color(0xFF0D1117)
val InkRaised = Color(0xFF161B22)
val InkSunken = Color(0xFF21262D)
val InkLine = Color(0xFF30363D)
val Amber = Color(0xFFFFB454)
val Sky = Color(0xFF58B6FF)
val Alarm = Color(0xFFFF7B72)
val Good = Color(0xFF56D364)
val Bright = Color(0xFFE6EDF3)
val Muted = Color(0xFF8B949E)


// Light
val Paper = Color(0xFFF6F7F9)
val PaperRaised = Color(0xFFFFFFFF)
val PaperSunken = Color(0xFFE9ECF1)
val PaperLine = Color(0xFFD0D7DE)
val AmberInk = Color(0xFF9A5B00)
val SkyInk = Color(0xFF0B69A3)
val AlarmInk = Color(0xFFB3261E)
val GoodInk = Color(0xFF1A7F37)
val Dark = Color(0xFF10161C)
val MutedInk = Color(0xFF57606A)
val White = Color(0xFFFFFFFF)
