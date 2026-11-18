/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Atena Docs
 *
 * Atena Docs is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Atena Docs is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Atena Docs.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.docs.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.openappslabs.docs.platform.Xdg
import java.nio.file.Files

/**
 * The handful of KDE colors the UI needs.
 *
 * Only `kdeglobals` is read — a plain ini file, no KConfig dependency — so the
 * app follows the Plasma palette and accent color without linking Qt.
 */
data class KdePalette(
    val window: Color,
    val windowText: Color,
    val view: Color,
    val viewText: Color,
    val accent: Color,
    val accentText: Color
) {
    val isDark: Boolean get() = window.luminance() < 0.5f
}

/** Used when there is no `kdeglobals` (non-KDE session, or a fresh account). */
val FallbackDarkPalette = KdePalette(
    window = Color(0xFF232629),
    windowText = Color(0xFFEFF0F1),
    view = Color(0xFF1B1E20),
    viewText = Color(0xFFEFF0F1),
    accent = Color(0xFF3DAEE9),
    accentText = Color(0xFFFCFCFC)
)

/**
 * Parses the groups we care about. Returns null when there is no window
 * background, which is the signal that this is not a usable KDE palette and
 * the caller should fall back instead of guessing colors from partial data.
 */
fun parseKdeGlobals(text: String): KdePalette? {
    val groups = mutableMapOf<String, MutableMap<String, String>>()
    var current = ""
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
        if (line.startsWith("[") && line.endsWith("]")) {
            current = line.substring(1, line.length - 1).trim()
            continue
        }
        val separator = line.indexOf('=')
        if (separator <= 0) continue
        val key = line.substring(0, separator).trim()
        val value = line.substring(separator + 1).trim()
        if (key.isEmpty()) continue
        groups.getOrPut(current) { mutableMapOf() }[key] = value
    }

    val window = groups["Colors:Window"] ?: return null
    val windowBackground = parseRgb(window["BackgroundNormal"]) ?: return null
    val windowText = parseRgb(window["ForegroundNormal"]) ?: Color(0xFFEFF0F1)

    val view = groups["Colors:View"]
    val viewBackground = parseRgb(view?.get("BackgroundNormal")) ?: windowBackground
    val viewText = parseRgb(view?.get("ForegroundNormal")) ?: windowText

    val accent = parseRgb(groups["General"]?.get("AccentColor"))
        ?: parseRgb(groups["Colors:Selection"]?.get("BackgroundNormal"))
        ?: FallbackDarkPalette.accent
    val accentText = parseRgb(groups["Colors:Selection"]?.get("ForegroundNormal"))
        ?: FallbackDarkPalette.accentText

    return KdePalette(
        window = windowBackground,
        windowText = windowText,
        view = viewBackground,
        viewText = viewText,
        accent = accent,
        accentText = accentText
    )
}

/** `r,g,b` as written by KDE, with each channel range-checked. */
fun parseRgb(value: String?): Color? {
    if (value == null) return null
    val parts = value.split(',').map { it.trim() }
    if (parts.size < 3) return null
    val channels = parts.take(3).map { it.toIntOrNull() ?: return null }
    if (channels.any { it !in 0..255 }) return null
    return Color(channels[0], channels[1], channels[2])
}

@Composable
fun rememberKdePalette(): KdePalette = remember {
    runCatching {
        val file = Xdg.kdeGlobalsFile
        if (!Files.isRegularFile(file)) null else parseKdeGlobals(Files.readString(file))
    }.getOrNull() ?: FallbackDarkPalette
}
