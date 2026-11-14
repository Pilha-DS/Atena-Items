package com.openappslabs.jotter.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The cadence is the whole "don't spend my data" promise, so it is pinned down here. */
class SyncCadenceTest {

    @Test
    fun free_data_keeps_the_five_minute_cadence() {
        assertEquals(5 * 60 * 1000L, SyncCadence.periodicDelay(NetworkCost.UNMETERED))
    }

    @Test
    fun mobile_link_is_six_times_slower_capped_at_ten_minutes() {
        val metered = SyncCadence.periodicDelay(NetworkCost.METERED)

        assertTrue(
            metered < SyncCadence.PERIODIC_SYNC_MS * SyncCadence.METERED_SLOWDOWN,
            "the cap wins over six intervals of five minutes"
        )
        assertEquals(SyncCadence.METERED_MAX_DELAY_MS, metered)
        assertEquals(10 * 60 * 1000L, metered)
    }

    @Test
    fun the_cap_never_shortens_the_wait_below_the_free_cadence() {
        assertTrue(SyncCadence.METERED_MAX_DELAY_MS > SyncCadence.PERIODIC_SYNC_MS)
        assertTrue(
            SyncCadence.periodicDelay(NetworkCost.METERED) >
                SyncCadence.periodicDelay(NetworkCost.UNMETERED)
        )
    }

    @Test
    fun offline_keeps_the_normal_cadence_because_nothing_is_sent() {
        assertEquals(
            SyncCadence.periodicDelay(NetworkCost.UNMETERED),
            SyncCadence.periodicDelay(NetworkCost.OFFLINE)
        )
    }
}
