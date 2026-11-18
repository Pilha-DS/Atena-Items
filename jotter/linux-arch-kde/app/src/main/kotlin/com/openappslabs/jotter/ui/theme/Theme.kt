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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.openappslabs.jotter.ui.i18n.AppLanguage
import com.openappslabs.jotter.ui.i18n.LocalStrings
import com.openappslabs.jotter.ui.i18n.resolved
import com.openappslabs.jotter.ui.i18n.stringsFor

/**
 * Wraps the content in the app theme.
 *
 * The [colorScheme] is computed once by the shell and shared by every window,
 * so the notes card and the general note can
 * never disagree on a color — not even for the couple of seconds between two
 * independent reads of the desktop palette.
 */
@Composable
fun JotterTheme(
    colorScheme: ColorScheme,
    isHapticEnabled: Boolean = true,
    language: String = AppLanguage.SYSTEM.key,
    content: @Composable () -> Unit
) {
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
