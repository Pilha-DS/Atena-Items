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

package com.openappslabs.agenda.ui

import com.openappslabs.agenda.data.model.Event
import com.openappslabs.agenda.data.model.EventDraft
import com.openappslabs.agenda.data.model.EventTimes
import com.openappslabs.agenda.ui.i18n.Strings
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** What the editor form collects, before validation. */
data class EventFormValues(
    val uuid: String? = null,
    val title: String = "",
    val dateText: String = "",
    val startText: String = "",
    val endText: String = "",
    val allDay: Boolean = false,
    val location: String = "",
    val description: String = ""
)

/**
 * Turns the text fields into a draft. Kept pure (no Compose, no database) so a
 * bad date or an inverted time range can be unit tested.
 */
object EventForm {

    private val lenientTime: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")
    private val strictTime: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    sealed interface Result {
        data class Ok(val draft: EventDraft) : Result
        data class Invalid(val message: String) : Result
    }

    fun valuesFrom(event: Event): EventFormValues {
        val start = EventTimes.toLocalDateTime(event.startTime)
        val end = EventTimes.toLocalDateTime(event.endTime)
        return EventFormValues(
            uuid = event.uuid,
            title = event.title,
            dateText = start.toLocalDate().format(EventTimes.dateFormat),
            startText = start.toLocalTime().format(strictTime),
            endText = end.toLocalTime().format(strictTime),
            allDay = event.allDay,
            location = event.location,
            description = event.description
        )
    }

    fun build(values: EventFormValues): Result {
        val title = values.title.trim()
        if (title.isEmpty()) return Result.Invalid(Strings.errorTitle)

        val date = parseDate(values.dateText)
            ?: return Result.Invalid(Strings.errorDate)

        if (values.allDay) {
            val (start, end) = EventTimes.allDayRange(date)
            return Result.Ok(
                EventDraft(
                    uuid = values.uuid,
                    title = title,
                    description = values.description.trim(),
                    location = values.location.trim(),
                    startTime = start,
                    endTime = end,
                    allDay = true
                )
            )
        }

        val startTime = parseTime(values.startText)
            ?: return Result.Invalid(Strings.errorTime)
        val endTime = parseTime(values.endText)
            ?: return Result.Invalid(Strings.errorTime)
        if (!endTime.isAfter(startTime)) return Result.Invalid(Strings.errorEndBeforeStart)

        return Result.Ok(
            EventDraft(
                uuid = values.uuid,
                title = title,
                description = values.description.trim(),
                location = values.location.trim(),
                startTime = EventTimes.toMillis(date, startTime),
                endTime = EventTimes.toMillis(date, endTime),
                allDay = false
            )
        )
    }

    private fun parseDate(text: String): LocalDate? = try {
        LocalDate.parse(text.trim(), EventTimes.dateFormat)
    } catch (error: DateTimeParseException) {
        null
    }

    /** Accepts "9:30" and "09:30"; the stored value is always zero-padded. */
    private fun parseTime(text: String): LocalTime? = try {
        LocalTime.parse(text.trim(), lenientTime)
    } catch (error: DateTimeParseException) {
        null
    }
}
