/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Agenda
 *
 * Agenda is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.agenda.ui

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MonthGridDaysTest {

    @Test
    fun always_six_full_weeks_starting_on_sunday() {
        for (month in listOf(YearMonth.of(2026, 11), YearMonth.of(2026, 8), YearMonth.of(2024, 2))) {
            val days = monthGridDays(month)
            assertEquals(42, days.size, "42 cells for $month")
            assertEquals(DayOfWeek.SUNDAY, days.first().dayOfWeek, "grid starts on Sunday")
            assertEquals(days.first().plusDays(41), days.last())
        }
    }

    @Test
    fun short_months_borrow_the_previous_week() {
        // November 2026 starts on a Sunday and fits in five weeks, but Plasma
        // still draws six rows: the extra week goes above, not below.
        val days = monthGridDays(YearMonth.of(2026, 11))
        assertEquals(LocalDate.of(2026, 10, 25), days.first())
        assertEquals(LocalDate.of(2026, 12, 5), days.last())
        assertEquals(LocalDate.of(2026, 11, 13), days[19])
    }

    @Test
    fun months_that_need_six_weeks_are_not_shifted() {
        // August 2026 starts on a Saturday, so the first week already has six
        // days of July and the grid begins on the Sunday right before it.
        val days = monthGridDays(YearMonth.of(2026, 8))
        assertEquals(LocalDate.of(2026, 7, 26), days.first())
        assertTrue(days.contains(LocalDate.of(2026, 8, 1)))
        assertTrue(days.contains(LocalDate.of(2026, 8, 31)))
    }
}
