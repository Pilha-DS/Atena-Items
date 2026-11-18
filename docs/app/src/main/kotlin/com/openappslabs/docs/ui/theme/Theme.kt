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

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.lerp
import com.openappslabs.docs.ui.i18n.LocalDocsStrings
import com.openappslabs.docs.ui.i18n.PtBrDocsStrings

/**
 * Material 3 scheme derived from the Plasma palette.
 *
 * The base scheme is Material's own (so every role we do not touch has a sane
 * value), then the roles that must match the desktop — background, surfaces,
 * accent — are replaced. Surfaces in between are mixed from the two colors the
 * user actually chose, so no Material purple leaks into a blue Plasma theme.
 */
fun docsColorScheme(palette: KdePalette): ColorScheme {
    val base = if (palette.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = palette.accent,
        onPrimary = palette.accentText,
        primaryContainer = lerp(palette.view, palette.accent, 0.28f),
        onPrimaryContainer = palette.viewText,
        secondaryContainer = lerp(palette.view, palette.accent, 0.16f),
        onSecondaryContainer = palette.viewText,
        background = palette.window,
        onBackground = palette.windowText,
        surface = palette.view,
        onSurface = palette.viewText,
        surfaceVariant = lerp(palette.view, palette.viewText, 0.08f),
        onSurfaceVariant = lerp(palette.viewText, palette.view, 0.25f),
        outline = lerp(palette.view, palette.viewText, 0.30f),
        outlineVariant = lerp(palette.view, palette.viewText, 0.16f)
    )
}

@Composable
fun DocsTheme(content: @Composable () -> Unit) {
    val palette = rememberKdePalette()
    val scheme = remember(palette) { docsColorScheme(palette) }
    CompositionLocalProvider(LocalDocsStrings provides PtBrDocsStrings) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
