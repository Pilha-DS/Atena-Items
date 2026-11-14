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

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Date/time conversions for events.
 *
 * Times are stored as epoch millis in the device's zone. An all-day event spans
 * local midnight to local midnight, exactly like the phone's calendar does, so
 * a day does not shift when the machine changes time zone.
 */
object EventTimes {

    val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    val shortDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

    private fun zone(): ZoneId = ZoneId.systemDefault()

    fun startOfDay(date: LocalDate): Long =
        date.atStartOfDay(zone()).toInstant().toEpochMilli()

    fun endOfDay(date: LocalDate): Long =
        date.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()

    fun toLocalDate(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone()).toLocalDate()

    fun toLocalDateTime(millis: Long): LocalDateTime =
        Instant.ofEpochMilli(millis).atZone(zone()).toLocalDateTime()

    fun toMillis(date: LocalDate, time: LocalTime): Long =
        LocalDateTime.of(date, time).atZone(zone()).toInstant().toEpochMilli()

    fun today(): LocalDate = LocalDate.now(zone())

    /** End of the day for an all-day event, used as its `endTime`. */
    fun allDayRange(date: LocalDate): Pair<Long, Long> =
        startOfDay(date) to endOfDay(date)

    fun allDayLastDate(event: Event): LocalDate {
        val end = if (event.endTime > event.startTime) event.endTime else event.startTime
        val lastDay = toLocalDate(end - 1)
        return lastDay
    }

    /** True when the event touches [date] at some point. */
    fun isOn(event: Event, date: LocalDate): Boolean {
        val from = startOfDay(date)
        val to = endOfDay(date)
        return overlaps(event, from, to)
    }

    /**
     * Half-open interval. A zero-length event still counts as occupied, so a
     * reminder created with `end == start` shows up instead of disappearing.
     */
    fun overlaps(event: Event, from: Long, to: Long): Boolean {
        val end = if (event.endTime > event.startTime) event.endTime else event.startTime + 1
        return event.startTime < to && end > from
    }

    /** "09:30 – 10:15", or "Dia inteiro" for an all-day event. */
    fun timeLabel(event: Event): String = when {
        event.allDay -> "Dia inteiro"
        else -> "${startLabel(event)} – ${endLabel(event)}"
    }

    fun startLabel(event: Event): String =
        if (event.allDay) "Dia inteiro" else toLocalDateTime(event.startTime).format(timeFormat)

    fun endLabel(event: Event): String =
        toLocalDateTime(event.endTime).format(timeFormat)

    /** Multiple-day events show the range instead of a single day. */
    fun dayRangeLabel(event: Event): String? {
        val first = toLocalDate(event.startTime)
        val last = allDayLastDate(event)
        if (!last.isAfter(first)) return null
        return "${first.format(shortDateFormat)} – ${last.format(shortDateFormat)}"
    }
}
