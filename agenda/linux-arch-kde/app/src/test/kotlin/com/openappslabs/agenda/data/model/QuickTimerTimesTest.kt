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

package com.openappslabs.agenda.data.model

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuickTimerTimesTest {

    private val now = 1_000_000L

    @Test
    fun parses_minutes_the_way_people_type_them() {
        assertEquals(Duration.ofMinutes(25), QuickTimerTimes.parseDuration("25"))
        assertEquals(Duration.ofMinutes(25), QuickTimerTimes.parseDuration(" 25 "))
        assertEquals(Duration.ofMinutes(25), QuickTimerTimes.parseDuration("25min"))
        assertEquals(Duration.ofMinutes(25), QuickTimerTimes.parseDuration("25 minutos"))
    }

    @Test
    fun parses_hours_and_mixed_input() {
        assertEquals(Duration.ofMinutes(60), QuickTimerTimes.parseDuration("1h"))
        assertEquals(Duration.ofMinutes(60), QuickTimerTimes.parseDuration("1 hora"))
        assertEquals(Duration.ofMinutes(90), QuickTimerTimes.parseDuration("1h30"))
        assertEquals(Duration.ofMinutes(90), QuickTimerTimes.parseDuration("1h30m"))
        assertEquals(Duration.ofMinutes(90), QuickTimerTimes.parseDuration("1:30"))
        assertEquals(Duration.ofMinutes(120), QuickTimerTimes.parseDuration("2 horas"))
    }

    @Test
    fun rejects_empty_zero_out_of_range_and_garbage() {
        for (raw in listOf("", "   ", "0", "0min", "25 horas", "1:75", "abc", "1441")) {
            assertNull(QuickTimerTimes.parseDuration(raw), "should reject \"$raw\"")
        }
    }

    @Test
    fun accepts_the_limits() {
        assertEquals(Duration.ofMinutes(1), QuickTimerTimes.parseDuration("1"))
        assertEquals(Duration.ofMinutes(1440), QuickTimerTimes.parseDuration("24h"))
    }

    @Test
    fun counts_down_with_seconds_and_hours() {
        assertEquals("01:30", QuickTimerTimes.remainingLabel(now + 90_000, now))
        assertEquals("00:05", QuickTimerTimes.remainingLabel(now + 5_000, now))
        assertEquals("1:00:00", QuickTimerTimes.remainingLabel(now + 3_600_000, now))
        assertEquals("00:00", QuickTimerTimes.remainingLabel(now - 5_000, now))
    }

    @Test
    fun knows_when_it_expired() {
        val timer = QuickTimer(uuid = "a", title = "Ir ao mercado", endTime = now)
        assertEquals(true, QuickTimerTimes.isExpired(timer, now))
        assertEquals(false, QuickTimerTimes.isExpired(timer, now - 1))
    }
}
