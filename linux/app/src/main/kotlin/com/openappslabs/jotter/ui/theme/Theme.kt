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

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.openappslabs.jotter.ui.i18n.AppLanguage
import com.openappslabs.jotter.ui.i18n.LocalStrings
import com.openappslabs.jotter.ui.i18n.resolved
import com.openappslabs.jotter.ui.i18n.stringsFor

@Composable
fun JotterTheme(
    isDarkTheme: Boolean = false,
    isTrueBlackEnabled: Boolean = false,
    isDynamicColor: Boolean = true,
    isHapticEnabled: Boolean = true,
    language: String = AppLanguage.SYSTEM.key,
    content: @Composable () -> Unit
) {
    val palette = rememberKdePalette()
    val colorScheme = remember(isDarkTheme, isTrueBlackEnabled, palette) {
        kdeColorScheme(palette, isDarkTheme, isTrueBlackEnabled)
    }
    // Resolved once per language change: every screen reads the same instance.
    val strings = remember(language) { stringsFor(AppLanguage.fromKey(language).resolved()) }

    CompositionLocalProvider(
        LocalHapticEnabled provides isHapticEnabled,
        LocalStrings provides strings
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
