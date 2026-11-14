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
 * Visibility of the clipboard window.
 *
 * A third desktop-shell window, glued to the left of the widget (the general
 * note is glued to the right). It holds no data of its own: the text is the
 * system clipboard, so there is nothing to flush when it is hidden.
 */
object ClipboardWindow {

    var isVisible by mutableStateOf(WindowStateStore.loadClipboardVisible())
        private set

    fun show() = update(true)

    fun hide() = update(false)

    fun toggle() = update(!isVisible)

    private fun update(value: Boolean) {
        if (isVisible == value) return
        isVisible = value
        WindowStateStore.saveClipboardVisible(value)
    }
}
