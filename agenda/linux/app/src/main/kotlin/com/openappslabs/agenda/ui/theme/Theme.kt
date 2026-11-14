/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Agenda
 *
 * Agenda is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Agenda is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Agenda.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.agenda.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.nio.file.Files
import java.nio.file.Path

@Immutable
data class KdeColors(
    val isDark: Boolean,
    val accent: Color,
    val background: Color,
    val surfaceVariant: Color,
    val foreground: Color
)

/**
 * Colors read from KDE's `kdeglobals`, so the app follows the Plasma color
 * scheme instead of Android's Material You. The fallback is the same dark card
 * used by the panel widget.
 */
fun readKdeColors(file: Path, darkFallback: Boolean = true): KdeColors {
    val values = runCatching {
        if (!Files.exists(file)) return@runCatching null
        parseKdeGlobals(Files.readAllLines(file))
    }.getOrNull() ?: return fallback(darkFallback)

    fun color(section: String, key: String): Color? =
        values["$section/$key"]?.let(::parseKdeColor)

    val background = color("Colors:Window", "BackgroundNormal")
        ?: color("Colors:View", "BackgroundNormal")
        ?: return fallback(darkFallback)
    val foreground = color("Colors:Window", "ForegroundNormal")
        ?: color("Colors:View", "ForegroundNormal")
        ?: return fallback(darkFallback)
    val surfaceVariant = color("Colors:Button", "BackgroundNormal")
        ?: background
    val accent = color("General", "AccentColor")
        ?: color("Colors:Selection", "BackgroundNormal")
        ?: fallback(background.luminance() < 0.5f).accent

    return KdeColors(
        isDark = background.luminance() < 0.5f,
        accent = accent,
        background = background,
        surfaceVariant = surfaceVariant,
        foreground = foreground
    )
}

private fun fallback(isDark: Boolean): KdeColors = if (isDark) {
    KdeColors(
        isDark = true,
        accent = Color(0xFF3DBAE9),
        background = Color(0xFF1D2022),
        surfaceVariant = Color(0xFF292C30),
        foreground = Color(0xFFEFF0F1)
    )
} else {
    KdeColors(
        isDark = false,
        accent = Color(0xFF1D7FC4),
        background = Color(0xFFFCFCFC),
        surfaceVariant = Color(0xFFEFF0F1),
        foreground = Color(0xFF232629)
    )
}

/** `section/key -> "r,g,b"`, with comments and blank lines ignored. */
internal fun parseKdeGlobals(lines: List<String>): Map<String, String> {
    val values = mutableMapOf<String, String>()
    var section = ""
    for (raw in lines) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        if (line.startsWith("[") && line.endsWith("]")) {
            section = line.substring(1, line.length - 1)
            continue
        }
        val separator = line.indexOf('=')
        if (separator <= 0) continue
        values["$section/${line.substring(0, separator).trim()}"] = line.substring(separator + 1).trim()
    }
    return values
}

internal fun parseKdeColor(value: String): Color? {
    val parts = value.split(',').map { it.trim().toIntOrNull() }
    if (parts.size < 3) return null
    val red = parts[0] ?: return null
    val green = parts[1] ?: return null
    val blue = parts[2] ?: return null
    return Color(red.coerceIn(0, 255), green.coerceIn(0, 255), blue.coerceIn(0, 255))
}

@Composable
fun AgendaTheme(file: Path, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val colors = remember(file, systemDark) { readKdeColors(file, darkFallback = systemDark) }

    val onAccent = if (colors.accent.luminance() > 0.6f) Color(0xFF101314) else Color.White
    val muted = colors.foreground.copy(alpha = 0.72f)
    val outline = colors.foreground.copy(alpha = 0.24f)

    val scheme = if (colors.isDark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = onAccent,
            background = colors.background,
            onBackground = colors.foreground,
            surface = colors.background,
            onSurface = colors.foreground,
            surfaceVariant = colors.surfaceVariant,
            onSurfaceVariant = muted,
            outline = outline,
            error = Color(0xFFE35656)
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = onAccent,
            background = colors.background,
            onBackground = colors.foreground,
            surface = colors.background,
            onSurface = colors.foreground,
            surfaceVariant = colors.surfaceVariant,
            onSurfaceVariant = muted,
            outline = outline,
            error = Color(0xFFB3261E)
        )
    }

    MaterialTheme(colorScheme = scheme, content = content)
}
