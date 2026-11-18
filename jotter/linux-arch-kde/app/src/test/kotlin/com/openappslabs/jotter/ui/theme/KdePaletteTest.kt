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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KdePaletteTest {

    private val systemDark = KdePalette(
        isDark = true,
        accent = Color(0xFF0D7A8C),
        background = Color(0xFF101218),
        surface = Color(0xFF101218),
        surfaceVariant = Color(0xFF191C24),
        foreground = Color(0xFFE8F0F1),
        negative = Color(0xFFEB7680),
        isKde = true
    )

    @Test
    fun canonical_palettes_use_one_base_color_for_card_and_screens() {
        listOf(true, false).forEach { dark ->
            val palette = canonicalPalette(dark)
            assertEquals(palette.background, palette.surface)
            assertEquals(dark, palette.isDark)
            assertTrue(!palette.isKde)
        }
    }

    @Test
    fun dark_desktop_palette_is_used_for_the_dark_theme() {
        val scheme = kdeColorScheme(systemDark, isDarkTheme = true, trueBlack = false)

        assertEquals(systemDark.background, scheme.background)
        assertEquals(systemDark.background, scheme.surface)
        assertEquals(systemDark.accent, scheme.primary)
        assertEquals(systemDark.foreground, scheme.onSurface)
    }

    @Test
    fun light_theme_ignores_a_dark_desktop_palette() {
        // Forcing the light theme on a dark desktop must produce a light
        // background, otherwise the app paints dark text over a dark card.
        val scheme = kdeColorScheme(systemDark, isDarkTheme = false, trueBlack = false)

        assertTrue(scheme.background.luminance() > 0.5f)
        assertTrue(scheme.onBackground.luminance() < 0.5f)
        assertEquals(scheme.background, scheme.surface)
    }

    @Test
    fun error_colors_follow_the_scheme() {
        val scheme = kdeColorScheme(systemDark, isDarkTheme = true, trueBlack = false)

        assertEquals(systemDark.negative, scheme.error)
    }

    @Test
    fun true_black_only_changes_the_background() {
        val scheme = kdeColorScheme(systemDark, isDarkTheme = true, trueBlack = true)

        assertEquals(Color.Black, scheme.background)
        assertEquals(Color.Black, scheme.surface)
    }
}
