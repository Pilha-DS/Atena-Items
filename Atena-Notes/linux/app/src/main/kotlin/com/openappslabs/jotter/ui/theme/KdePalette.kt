/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
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
 * Colors read from KDE's kdeglobals, so Jotter follows the Plasma color scheme
 * and accent color instead of Android's Material You.
 */
data class KdePalette(
    val isDark: Boolean,
    val accent: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val foreground: Color,
    val isKde: Boolean
)

private val fallbackDark = KdePalette(
    isDark = true,
    accent = Color(0xFF3DBAE9),
    background = Color(0xFF202326),
    surface = Color(0xFF202326),
    surfaceVariant = Color(0xFF292C30),
    foreground = Color(0xFFEFF0F1),
    isKde = false
)

private val fallbackLight = KdePalette(
    isDark = false,
    accent = Color(0xFF1D7FC4),
    background = Color(0xFFFCFCFC),
    surface = Color(0xFFFCFCFC),
    surfaceVariant = Color(0xFFEFF0F1),
    foreground = Color(0xFF232629),
    isKde = false
)

fun readKdePalette(): KdePalette {
    val file = Xdg.kdeGlobalsFile.toFile()
    if (!file.exists()) return fallbackDark.copy(isDark = isSystemDark())

    val values = runCatching { parseKdeGlobals(file.readLines()) }.getOrNull()
        ?: return fallbackDark.copy(isDark = isSystemDark())

    fun color(section: String, key: String): Color? =
        values["$section/$key"]?.let(::parseKdeColor)

    val background = color("Colors:Window", "BackgroundNormal")
        ?: color("Colors:View", "BackgroundNormal")
    val surface = color("Colors:View", "BackgroundNormal")
        ?: background
    val surfaceVariant = color("Colors:Button", "BackgroundNormal")
        ?: color("Colors:Header", "BackgroundNormal")
    val foreground = color("Colors:Window", "ForegroundNormal")
        ?: color("Colors:View", "ForegroundNormal")
        ?: if ((background?.luminance() ?: 0f) < 0.5f) Color.White else Color.Black
    val accent = color("General", "AccentColor")
        ?: color("Colors:Selection", "BackgroundNormal")
        ?: if ((background?.luminance() ?: 0f) < 0.5f) fallbackDark.accent else fallbackLight.accent

    val isDark = (background?.luminance() ?: if (isSystemDark()) 0f else 1f) < 0.5f
    val fallback = if (isDark) fallbackDark else fallbackLight

    return KdePalette(
        isDark = isDark,
        accent = accent,
        background = background ?: fallback.background,
        surface = surface ?: fallback.surface,
        surfaceVariant = surfaceVariant ?: fallback.surfaceVariant,
        foreground = foreground,
        isKde = true
    )
}

@Composable
fun rememberKdePalette(): KdePalette {
    var palette by remember { mutableStateOf(readKdePalette()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            // Reading kdeglobals is disk I/O; polling it on the AWT thread
            // every couple of seconds only adds jank to the window.
            val next = withContext(Dispatchers.IO) { readKdePalette() }
            if (next != palette) palette = next
        }
    }
    return palette
}

fun kdeColorScheme(palette: KdePalette, isDarkTheme: Boolean, trueBlack: Boolean): ColorScheme {
    val dark = isDarkTheme
    val fallback = if (dark) fallbackDark else fallbackLight
    val base = if (dark) darkColorScheme() else lightColorScheme()

    val background = if (dark && trueBlack) Color.Black else palette.background
    val surface = if (dark && trueBlack) Color.Black else palette.surface
    val onBackground = if (dark) {
        if (palette.foreground.luminance() < 0.6f) fallback.foreground else palette.foreground
    } else {
        if (palette.foreground.luminance() > 0.6f) fallback.foreground else palette.foreground
    }
    val onSurfaceVariant = lerp(onBackground, background, 0.28f)
    val accent = palette.accent
    val onAccent = if (accent.luminance() > 0.55f) Color.Black else Color.White
    val surfaceVariant = if (dark) {
        lerp(surface, onBackground, 0.10f)
    } else {
        lerp(surface, onBackground, 0.08f)
    }

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
        error = if (dark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A),
        onError = if (dark) Color(0xFF690005) else Color.White,
        errorContainer = if (dark) Color(0xFF93000A) else Color(0xFFFFDAD6),
        onErrorContainer = if (dark) Color(0xFFFFDAD6) else Color(0xFF410002),
        outline = lerp(onBackground, background, 0.50f),
        outlineVariant = lerp(onBackground, background, 0.76f),
        scrim = Color.Black,
        surfaceBright = lerp(surface, onBackground, 0.08f),
        surfaceDim = lerp(surface, background, 0.12f),
        surfaceContainerLowest = if (dark && trueBlack) Color.Black else lerp(surface, background, 0.65f),
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

private fun isSystemDark(): Boolean {
    // The portal setting is only used as a fallback when kdeglobals is unreadable.
    val gtk = System.getenv("GTK_THEME")?.lowercase()
    if (gtk != null) return "dark" in gtk
    return true
}
