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
 * Where the window goes when the panel icon asks for it.
 *
 * Preferred spot is right below the icon; when there is no room it opens above
 * it, and the result is always clamped to the screen so the window never lands
 * off-screen on a multi-monitor setup.
 */
object WindowPlacement {

    data class Placed(val x: Int, val y: Int)

    fun below(
        anchor: IpcBridge.Anchor,
        width: Int,
        height: Int,
        screen: Rectangle,
        gap: Int = 6
    ): Placed {
        val belowY = anchor.y + anchor.height + gap
        val aboveY = anchor.y - height - gap
        val y = when {
            belowY + height <= screen.y + screen.height -> belowY
            aboveY >= screen.y -> aboveY
            else -> (screen.y + screen.height - height).coerceAtLeast(screen.y)
        }
        val centeredX = anchor.x + (anchor.width - width) / 2
        val maxX = screen.x + screen.width - width
        val x = centeredX.coerceIn(screen.x, maxX.coerceAtLeast(screen.x))
        return Placed(x = x, y = y.coerceIn(screen.y, (screen.y + screen.height - height).coerceAtLeast(screen.y)))
    }
}
