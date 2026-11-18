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

package com.openappslabs.agenda.platform

import java.awt.Rectangle

/**
 * Where the toast lands: pinned to the top-right corner of the usable screen,
 * which is where Plasma puts its own popups.
 *
 * The rectangle passed in is the usable area, so a panel at the top or on the
 * right is already excluded and the toast never hides under it. On a screen
 * smaller than the toast the position is clamped instead of going negative.
 */
object ToastPlacement {

    data class Placed(val x: Int, val y: Int)

    fun topRight(screen: Rectangle, width: Int, height: Int, margin: Int): Placed {
        val x = (screen.x + screen.width - width - margin).coerceAtLeast(screen.x)
        val y = (screen.y + margin).coerceAtMost(
            (screen.y + screen.height - height).coerceAtLeast(screen.y)
        )
        return Placed(x = x, y = y)
    }
}
