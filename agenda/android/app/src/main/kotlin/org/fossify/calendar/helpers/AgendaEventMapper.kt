package org.fossify.calendar.helpers

import android.graphics.Color
import org.fossify.calendar.extensions.seconds
import org.fossify.calendar.models.Event
import org.joda.time.DateTimeZone
import java.util.Locale

/**
 * Translates between the app's event row and the hub's wire format.
 *
 * The interesting part is the all-day convention: the app stores an all-day
 * event from local midnight to **noon of the last day**, while the hub (and the
 * Linux app) uses a half-open range of whole days. Both sides are converted
 * here so neither has to know about the other's quirk.
 */
object AgendaEventMapper {

    const val SECONDS_TO_MILLIS = 1000L
    private const val NOON_HOUR = 12
    private const val COLOR_MASK = 0xFFFFFF

    fun toDto(event: Event, uuid: String): AgendaSyncEventDto {
        val allDay = event.getIsAllDay()
        val endTime = if (allDay) {
            // Stored end is noon of the last day: the hub wants the midnight after it.
            Formatter.getDateTimeFromTS(event.endTS).withTimeAtStartOfDay().plusDays(1).seconds() *
                SECONDS_TO_MILLIS
        } else {
            event.endTS * SECONDS_TO_MILLIS
        }

        return AgendaSyncEventDto(
            uuid = uuid,
            title = event.title,
            description = event.description,
            location = event.location,
            startTime = event.startTS * SECONDS_TO_MILLIS,
            endTime = endTime,
            allDay = allDay,
            color = colorToHex(event.color),
            createdTime = event.lastUpdated,
            updatedTime = event.lastUpdated
        )
    }

    /** Copies the hub's fields onto [event], keeping reminders, calendar and type. */
    fun applyDto(event: Event, dto: AgendaSyncEventDto): Event {
        event.title = dto.title
        event.description = dto.description
        event.location = dto.location
        event.lastUpdated = dto.updatedTime

        if (dto.allDay) {
            event.flags = event.flags or FLAG_ALL_DAY
            event.startTS = Formatter.getDateTimeFromTS(dto.startTime).withTimeAtStartOfDay().seconds()
            event.endTS = Formatter.getDateTimeFromTS(dto.endTime - 1)
                .withTimeAtStartOfDay()
                .withHourOfDay(NOON_HOUR)
                .seconds()
            event.timeZone = DateTimeZone.getDefault().id
        } else {
            event.flags = event.flags and FLAG_ALL_DAY.inv()
            event.startTS = dto.startTime / SECONDS_TO_MILLIS
            event.endTS = dto.endTime / SECONDS_TO_MILLIS
            if (event.timeZone.isEmpty()) {
                event.timeZone = DateTimeZone.getDefault().id
            }
        }

        val parsed = parseColor(dto.color)
        if (parsed != 0) {
            event.color = parsed
        }
        return event
    }

    /** An event exactly as the hub described it, for a row that does not exist yet. */
    fun toNewEvent(dto: AgendaSyncEventDto): Event {
        val event = Event(
            id = null,
            title = dto.title,
            description = dto.description,
            location = dto.location,
            calendarId = LOCAL_CALENDAR_ID,
            source = SOURCE_SIMPLE_CALENDAR,
            lastUpdated = dto.updatedTime
        )
        return applyDto(event, dto)
    }

    fun colorToHex(color: Int): String =
        if (color == 0) "" else String.format(Locale.US, "#%06X", color and COLOR_MASK)

    fun parseColor(value: String): Int {
        if (value.isEmpty()) return 0
        return try {
            Color.parseColor(value)
        } catch (_: IllegalArgumentException) {
            0
        }
    }
}
