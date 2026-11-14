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

package com.openappslabs.jotter.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cadence is the whole "don't spend my data" promise, so it is pinned down here. */
class SyncCadenceTest {

    @Test
    fun `free data keeps the five minute cadence`() {
        assertEquals(5 * 60 * 1000L, SyncCadence.periodicDelay(NetworkCost.UNMETERED))
    }

    @Test
    fun `mobile data is six times slower, capped at ten minutes`() {
        val metered = SyncCadence.periodicDelay(NetworkCost.METERED)

        assertTrue(
            "the cap wins over six intervals of five minutes",
            metered < SyncCadence.PERIODIC_SYNC_MS * SyncCadence.METERED_SLOWDOWN
        )
        assertEquals(SyncCadence.METERED_MAX_DELAY_MS, metered)
        assertEquals(10 * 60 * 1000L, metered)
    }

    @Test
    fun `the cap never shortens the wait below the free cadence`() {
        assertTrue(SyncCadence.METERED_MAX_DELAY_MS > SyncCadence.PERIODIC_SYNC_MS)
        assertTrue(
            SyncCadence.periodicDelay(NetworkCost.METERED) >
                SyncCadence.periodicDelay(NetworkCost.UNMETERED)
        )
    }

    @Test
    fun `offline keeps the normal cadence because nothing is sent`() {
        assertEquals(
            SyncCadence.periodicDelay(NetworkCost.UNMETERED),
            SyncCadence.periodicDelay(NetworkCost.OFFLINE)
        )
    }
}
