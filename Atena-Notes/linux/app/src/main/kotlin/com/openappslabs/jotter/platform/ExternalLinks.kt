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

/**
 * Tracks links handed over to the system (browser, mail client).
 *
 * The desktop port behaves like a popup: it hides itself shortly after losing
 * focus. That is right for a click on the desktop, but wrong when the window
 * lost focus because the user asked to open something in another application —
 * the card would disappear the instant the browser opened.
 *
 * The root composable watches [lastOpenedAt] and disarms the self-closing until
 * the window gets focus again.
 */
object ExternalLinks {

    @Volatile
    var lastOpenedAt: Long = 0L
        private set

    fun markOpened() {
        lastOpenedAt = System.currentTimeMillis()
    }
}
