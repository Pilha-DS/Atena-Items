/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.jotter.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

val LocalHapticEnabled = staticCompositionLocalOf { false }

/**
 * Desktop has no vibration motor. The API is kept so the shared screens do not
 * need to know which platform they are running on.
 */
@Composable
fun rememberJotterHaptics(): JotterHaptics {
    val isEnabled = LocalHapticEnabled.current
    return remember(isEnabled) { JotterHaptics(isEnabled) }
}

class JotterHaptics(private val isEnabled: Boolean) {
    fun tick() = Unit
    fun click() = Unit
    fun heavy() = Unit
    fun success() = Unit
    fun error() = Unit
}
