/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Atena Docs
 *
 * Atena Docs is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Atena Docs is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Atena Docs.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.docs.data.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrecencyTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `never opened scores zero`() {
        assertEquals(0.0, Frecency.score(0, 0L, now))
    }

    @Test
    fun `more visits raise the score`() {
        val once = Frecency.score(1, now, now)
        val often = Frecency.score(20, now, now)
        assertTrue(often > once)
        assertTrue(once > 0.0)
        assertTrue(often <= 1.0)
    }

    @Test
    fun `a recent open outranks an old one with the same count`() {
        val dayMs = 86_400_000L
        val recent = Frecency.score(5, now, now)
        val stale = Frecency.score(5, now - 60 * dayMs, now)
        assertTrue(recent > stale)
    }
}
