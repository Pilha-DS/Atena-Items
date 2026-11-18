/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Jotter is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Jotter.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.jotter.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.openappslabs.jotter.platform.Xdg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The colors the whole app paints with.
 *
 * Jotter is a desktop app, so "Material You" has no wallpaper to read: the
 * palette comes from the system (`kdeglobals` on Plasma). When there is no
 * system palette to read — Windows, a bare X11 session — the canonical palette
 * below is used instead, so the app looks the same on every platform and in
 * every one of its windows (notes and the general note).
 *
 * [background] and [surface] are the same color on purpose: screens used to
 * paint one and the card the other, which left the inside of the app darker
 * than its frame. Elevation is expressed by the `surfaceContainer*` levels of
 * the Material scheme, all derived from this single base.
 */
data class KdePalette(
    val isDark: Boolean,
    val accent: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val foreground: Color,
    val negative: Color,
    /** True when the values came from the desktop, false for the canonical set. */
    val isKde: Boolean
)

/**
 * Canonical dark palette, matching the AtenaDark scheme used on the desktops of
 * this monorepo. Same bytes on Windows and on a Linux box without KDE.
 */
private val canonicalDark = KdePalette(
    isDark = true,
    accent = Color(0xFF4FC3E0),
    background = Color(0xFF101218),
    surface = Color(0xFF101218),
    surfaceVariant = Color(0xFF191C24),
    foreground = Color(0xFFE8F0F1),
    negative = Color(0xFFEB7680),
    isKde = false
)

private val canonicalLight = KdePalette(
    isDark = false,
    accent = Color(0xFF1D7FC4),
    background = Color(0xFFFCFCFC),
    surface = Color(0xFFFCFCFC),
    surfaceVariant = Color(0xFFEFF0F1),
    foreground = Color(0xFF232629),
    negative = Color(0xFFB3261E),
    isKde = false
)

/** Canonical palette for a light or dark app theme. */
fun canonicalPalette(isDark: Boolean): KdePalette = if (isDark) canonicalDark else canonicalLight

/**
 * Reads the desktop palette.
 *
 * [followSystem] is the *Cores dinâmicas* switch: off means "use the canonical
 * palette, ignore the desktop". On (the default), the system palette is used
 * and the canonical one is only the fallback for platforms that have none.
 */
fun readKdePalette(followSystem: Boolean = true): KdePalette {
    if (!followSystem) return canonicalDark
    val file = Xdg.kdeGlobalsFile.toFile()
    if (!file.exists()) return canonicalDark

    val values = runCatching { parseKdeGlobals(file.readLines()) }.getOrNull()
        ?: return canonicalDark

    fun color(section: String, key: String): Color? =
        values["$section/$key"]?.let(::parseKdeColor)

    val background = color("Colors:Window", "BackgroundNormal")
        ?: color("Colors:View", "BackgroundNormal")
        ?: return canonicalDark
    val surfaceVariant = color("Colors:Button", "BackgroundNormal")
        ?: color("Colors:Header", "BackgroundNormal")
        ?: canonicalDark.surfaceVariant
    val foreground = color("Colors:Window", "ForegroundNormal")
        ?: color("Colors:View", "ForegroundNormal")
        ?: if (background.luminance() < 0.5f) Color.White else Color.Black
    val accent = color("General", "AccentColor")
        ?: color("Colors:Selection", "BackgroundNormal")
        ?: if (background.luminance() < 0.5f) canonicalDark.accent else canonicalLight.accent
    val negative = color("Colors:View", "ForegroundNegative")
        ?: color("Colors:Window", "ForegroundNegative")
        ?: if (background.luminance() < 0.5f) canonicalDark.negative else canonicalLight.negative

    return KdePalette(
        isDark = background.luminance() < 0.5f,
        accent = accent,
        background = background,
        // One base color for the card and for the screens inside it.
        surface = background,
        surfaceVariant = surfaceVariant,
        foreground = foreground,
        negative = negative,
        isKde = true
    )
}

@Composable
fun rememberKdePalette(followSystem: Boolean = true): KdePalette {
    var palette by remember(followSystem) {
        mutableStateOf(readKdePalette(followSystem))
    }
    LaunchedEffect(followSystem) {
        while (true) {
            delay(2_000)
            // Reading kdeglobals is disk I/O; polling it on the AWT thread
            // every couple of seconds only adds jank to the window.
            val next = withContext(Dispatchers.IO) { readKdePalette(followSystem) }
            if (next != palette) palette = next
        }
    }
    return palette
}

/**
 * The Material scheme used by every window of the app.
 *
 * The desktop palette is only used when it agrees with the chosen app theme:
 * a light theme on a dark desktop would otherwise paint dark text over a dark
 * background. When they disagree — or when there is no desktop palette — the
 * canonical palette of that mode is used, which is the same on every platform.
 */
fun kdeColorScheme(palette: KdePalette, isDarkTheme: Boolean, trueBlack: Boolean): ColorScheme {
    val canonical = canonicalPalette(isDarkTheme)
    val source = if (palette.isKde && palette.isDark == isDarkTheme) palette else canonical
    val base = if (isDarkTheme) darkColorScheme() else lightColorScheme()

    val background = if (isDarkTheme && trueBlack) Color.Black else source.background
    val surface = if (isDarkTheme && trueBlack) Color.Black else source.surface
    val onBackground = source.foreground
    val onSurfaceVariant = lerp(onBackground, background, 0.28f)
    val accent = source.accent
    val onAccent = if (accent.luminance() > 0.55f) Color.Black else Color.White
    val surfaceVariant = if (isDarkTheme) {
        lerp(surface, onBackground, 0.10f)
    } else {
        lerp(surface, onBackground, 0.08f)
    }
    // Error colors follow the scheme (`ForegroundNegative`) instead of the
    // pink Material ships with, so they match the desktop's own alerts.
    val negative = source.negative
    val onNegative = if (negative.luminance() > 0.55f) Color.Black else Color.White

    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = lerp(accent, background, 0.70f),
        onPrimaryContainer = lerp(accent, onBackground, 0.20f),
        inversePrimary = lerp(accent, background, 0.45f),
        secondary = lerp(accent, onBackground, 0.30f),
        onSecondary = onAccent,
        secondaryContainer = lerp(accent, background, 0.82f),
        onSecondaryContainer = onBackground,
        tertiary = lerp(accent, Color(0xFF7BD0C8), 0.35f),
        onTertiary = onAccent,
        tertiaryContainer = lerp(accent, background, 0.84f),
        onTertiaryContainer = onBackground,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onBackground,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = accent,
        inverseSurface = onBackground,
        inverseOnSurface = background,
        error = negative,
        onError = onNegative,
        errorContainer = lerp(negative, background, 0.76f),
        onErrorContainer = lerp(negative, onBackground, 0.15f),
        outline = lerp(onBackground, background, 0.50f),
        outlineVariant = lerp(onBackground, background, 0.76f),
        scrim = Color.Black,
        surfaceBright = lerp(surface, onBackground, 0.08f),
        surfaceDim = lerp(surface, background, 0.12f),
        surfaceContainerLowest = if (isDarkTheme && trueBlack) Color.Black else lerp(surface, background, 0.65f),
        surfaceContainerLow = lerp(surface, onBackground, 0.04f),
        surfaceContainer = lerp(surface, onBackground, 0.08f),
        surfaceContainerHigh = lerp(surface, onBackground, 0.12f),
        surfaceContainerHighest = lerp(surface, onBackground, 0.16f)
    )
}

private fun parseKdeGlobals(lines: List<String>): Map<String, String> {
    val result = mutableMapOf<String, String>()
    var section = ""
    lines.forEach { raw ->
        val line = raw.trim()
        when {
            line.isEmpty() || line.startsWith("#") -> Unit
            line.startsWith("[") && line.endsWith("]") ->
                section = line.substring(1, line.length - 1)
            else -> {
                val index = line.indexOf('=')
                if (index > 0) {
                    val key = line.substring(0, index).trim()
                    val value = line.substring(index + 1).trim()
                    result["$section/$key"] = value
                }
            }
        }
    }
    return result
}

private fun parseKdeColor(value: String): Color? {
    val parts = value.split(',').map { it.trim().toIntOrNull() }
    if (parts.size < 3) return null
    val r = parts[0] ?: return null
    val g = parts[1] ?: return null
    val b = parts[2] ?: return null
    if (listOf(r, g, b).any { it !in 0..255 }) return null
    return Color(r, g, b)
}
