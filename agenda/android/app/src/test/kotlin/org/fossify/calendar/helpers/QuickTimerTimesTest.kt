package org.fossify.calendar.helpers

import org.fossify.calendar.models.QuickTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickTimerTimesTest {

    private val minute = 60_000L

    @Test
    fun reads_plain_minutes() {
        assertEquals(25 * minute, QuickTimerTimes.parseDurationMillis("25"))
        assertEquals(25 * minute, QuickTimerTimes.parseDurationMillis(" 25min "))
        assertEquals(25 * minute, QuickTimerTimes.parseDurationMillis("25 minutos"))
        assertEquals(25 * minute, QuickTimerTimes.parseDurationMillis("25m"))
    }

    @Test
    fun reads_hours_and_a_clock_like_value() {
        assertEquals(60 * minute, QuickTimerTimes.parseDurationMillis("1h"))
        assertEquals(90 * minute, QuickTimerTimes.parseDurationMillis("1h30"))
        assertEquals(90 * minute, QuickTimerTimes.parseDurationMillis("1:30"))
        assertEquals(120 * minute, QuickTimerTimes.parseDurationMillis("2 horas"))
        assertEquals(90 * minute, QuickTimerTimes.parseDurationMillis("1h 30min"))
    }

    @Test
    fun refuses_what_is_out_of_range_or_unreadable() {
        assertNull(QuickTimerTimes.parseDurationMillis(""))
        assertNull(QuickTimerTimes.parseDurationMillis("0"))
        assertNull(QuickTimerTimes.parseDurationMillis("abc"))
        assertNull(QuickTimerTimes.parseDurationMillis("1:75"))
        assertNull(QuickTimerTimes.parseDurationMillis("24h1"))
        assertNull(QuickTimerTimes.parseDurationMillis("25h"))
    }

    @Test
    fun accepts_the_edges_of_the_range() {
        assertEquals(QuickTimerTimes.MIN_MINUTES * minute, QuickTimerTimes.parseDurationMillis("1"))
        assertEquals(
            QuickTimerTimes.MAX_MINUTES * minute,
            QuickTimerTimes.parseDurationMillis("24h")
        )
    }

    @Test
    fun counts_down_in_minutes_and_seconds() {
        val now = 1_000_000L
        assertEquals("12:34", QuickTimerTimes.remainingLabel(now + 754_000L, now))
        assertEquals("00:05", QuickTimerTimes.remainingLabel(now + 5_000L, now))
        assertEquals("1:02:03", QuickTimerTimes.remainingLabel(now + 3_723_000L, now))
    }

    @Test
    fun a_passed_deadline_counts_as_zero() {
        assertEquals("00:00", QuickTimerTimes.remainingLabel(1_000L, 5_000L))
    }

    @Test
    fun a_timer_rings_only_inside_the_grace_window() {
        val timer = timer(endTime = 10_000L)
        val exactlyNow = 10_000L

        assertTrue(QuickTimerTimes.shouldRing(timer, exactlyNow))
        assertTrue(
            QuickTimerTimes.shouldRing(timer, exactlyNow + QuickTimerTimes.RING_GRACE_MS)
        )
        assertFalse(
            QuickTimerTimes.shouldRing(timer, exactlyNow + QuickTimerTimes.RING_GRACE_MS + 1)
        )
        assertFalse(QuickTimerTimes.shouldRing(timer, exactlyNow - 1))
    }

    @Test
    fun an_old_deadline_is_stale_and_a_rung_one_never_rings_again() {
        val timer = timer(endTime = 10_000L)
        val muchLater = 10_000L + QuickTimerTimes.RING_GRACE_MS + 1

        assertTrue(QuickTimerTimes.isStale(timer, muchLater))
        assertFalse(QuickTimerTimes.isStale(timer.copy(notified = true), 10_000L))
        assertFalse(QuickTimerTimes.shouldRing(timer.copy(notified = true), 10_000L))
    }

    @Test
    fun a_preset_is_never_out_of_range() {
        assertTrue(
            QuickTimerTimes.presets.all {
                it.toLong() in QuickTimerTimes.MIN_MINUTES..QuickTimerTimes.MAX_MINUTES
            }
        )
    }

    private fun timer(endTime: Long) = QuickTimer(
        uuid = "uuid",
        title = "Ir ao mercado",
        endTime = endTime
    )
}
