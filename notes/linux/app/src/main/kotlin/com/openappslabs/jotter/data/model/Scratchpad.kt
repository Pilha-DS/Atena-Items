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

package com.openappslabs.jotter.data.model

/**
 * The one "general note" (scratchpad) shared by every device.
 *
 * It is an ordinary note row, so the notes sync already knows how to carry it.
 * What makes it "the" scratchpad is this fixed uuid: the same constant on the
 * Android app and on the desktop app, so both resolve the row to the same note
 * on the hub instead of each device keeping a private copy.
 *
 * The value is a valid v4-shaped uuid that no random generator will produce.
 * Do not change it: it is the identity of the note on every device that is
 * already synced.
 */
object Scratchpad {
    const val UUID = "6b1f7a3c-4d2e-4f5a-9b8c-0d1e2f3a4b5c"
}
