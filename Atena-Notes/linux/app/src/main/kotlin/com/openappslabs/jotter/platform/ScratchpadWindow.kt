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

package com.openappslabs.jotter.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Visibility of the companion "general note" window.
 *
 * It lives outside the navigation graph on purpose: the window is part of the
 * desktop shell (glued to the right of the widget), not a screen inside it.
 * Kept in memory as Compose state so the tray, the search bar button and the
 * window itself all stay in sync.
 */
object ScratchpadWindow {

    var isVisible by mutableStateOf(WindowStateStore.loadScratchpadVisible())
        private set

    fun show() = update(true)

    fun hide() = update(false)

    fun toggle() = update(!isVisible)

    private fun update(value: Boolean) {
        if (isVisible == value) return
        // The editor is disposed as soon as the window is removed, so the write
        // has to be finished before that: wait for it and checkpoint the WAL.
        if (!value) {
            AutoSave.flushAllBlocking()
            ScratchpadSaver.checkpointBlocking()
        }
        isVisible = value
        WindowStateStore.saveScratchpadVisible(value)
    }
}
