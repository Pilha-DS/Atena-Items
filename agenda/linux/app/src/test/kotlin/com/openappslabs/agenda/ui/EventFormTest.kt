/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Agenda
 *
 * Agenda is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.agenda.ui

import com.openappslabs.agenda.data.model.EventTimes
import com.openappslabs.agenda.ui.i18n.Strings
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EventFormTest {

    private val day = LocalDate.of(2026, 11, 13)

    private fun values(
        title: String = "Consulta",
        date: String = "13/11/2026",
        start: String = "09:00",
        end: String = "10:00",
        allDay: Boolean = false
    ) = EventFormValues(
        title = title,
        dateText = date,
        startText = start,
        endText = end,
        allDay = allDay
    )

    @Test
    fun title_is_required() {
        val result = EventForm.build(values(title = "   "))
        assertIs<EventForm.Result.Invalid>(result)
        assertEquals(Strings.errorTitle, result.message)
    }

    @Test
    fun invalid_date_is_rejected() {
        val result = EventForm.build(values(date = "2026-11-13"))
        assertIs<EventForm.Result.Invalid>(result)
        assertEquals(Strings.errorDate, result.message)
    }

    @Test
    fun invalid_time_is_rejected() {
        val result = EventForm.build(values(start = "9h"))
        assertIs<EventForm.Result.Invalid>(result)
        assertEquals(Strings.errorTime, result.message)
    }

    @Test
    fun end_must_be_after_start() {
        val result = EventForm.build(values(start = "12:00", end = "11:00"))
        assertIs<EventForm.Result.Invalid>(result)
        assertEquals(Strings.errorEndBeforeStart, result.message)
    }

    @Test
    fun single_digit_hour_is_accepted() {
        val result = EventForm.build(values(start = "9:30", end = "10:15"))
        assertIs<EventForm.Result.Ok>(result)
        assertEquals(EventTimes.toMillis(day, LocalTime.of(9, 30)), result.draft.startTime)
        assertEquals(EventTimes.toMillis(day, LocalTime.of(10, 15)), result.draft.endTime)
    }

    @Test
    fun all_day_spans_local_midnight() {
        val result = EventForm.build(values(allDay = true, start = "", end = ""))
        assertIs<EventForm.Result.Ok>(result)
        val (start, end) = EventTimes.allDayRange(day)
        assertEquals(start, result.draft.startTime)
        assertEquals(end, result.draft.endTime)
    }
}
