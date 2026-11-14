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

package com.openappslabs.agenda.data.notify

import com.openappslabs.agenda.data.model.Event
import com.openappslabs.agenda.data.model.EventTimes
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * When a notification is due. Pure functions on purpose: the rules (digest
 * once a day, reminder once per event, nothing for what already happened) are
 * the part that is worth testing, and they do not need a clock or a desktop.
 */
object NotifyPlan {

    /** "7:00", "07:00", "7h", "7h30" and "7:00" all mean the same thing to a person. */
    fun parseTime(text: String): LocalTime? {
        val clean = text.trim().lowercase().replace('h', ':').replace(" ", "")
        val parts = clean.split(':')
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = when (parts.size) {
            1 -> 0
            2 -> parts[1].ifEmpty { "0" }.toIntOrNull() ?: return null
            else -> return null
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    fun formatTime(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    fun digestTitle(count: Int): String =
        if (count == 1) "1 evento hoje" else "$count eventos hoje"

    /** One line per event, in the order the day happens. */
    fun digestBody(events: List<Event>): String =
        events.sortedWith(compareBy({ it.startTime }, { it.title }))
            .joinToString("\n") { "${EventTimes.startLabel(it)}  ${it.title}" }

    /** Once a day, from [NotifyConfig.digestAt] onwards. */
    fun shouldSendDigest(config: NotifyConfig, now: LocalDateTime): Boolean {
        if (!config.enabled || !config.digestEnabled) return false
        if (config.lastDigestDay == now.toLocalDate().toString()) return false
        val at = parseTime(config.digestAt) ?: return false
        return !now.toLocalTime().isBefore(at)
    }

    /** Events starting inside the lead window, oldest first, without repeats. */
    fun dueReminders(events: List<Event>, nowMillis: Long, config: NotifyConfig): List<Event> {
        if (!config.enabled || !config.remindEnabled) return emptyList()
        val lead = config.leadMinutes.coerceIn(1, MAX_LEAD_MINUTES) * 60_000L
        return events
            .filter { !it.allDay && it.startTime > nowMillis && it.startTime - nowMillis <= lead }
            .filter { key(it) !in config.sent }
            .sortedBy { it.startTime }
    }

    /** Identity of "this reminder was already shown": the event and its start. */
    fun key(event: Event): String = "${event.uuid}@${event.startTime}"

    /** Drops keys from other days, so the file does not grow forever. */
    fun pruneSent(sent: List<String>, day: LocalDate): List<String> = sent.filter { key ->
        key.substringAfter('@').toLongOrNull()?.let { EventTimes.toLocalDate(it) == day } == true
    }

    const val MAX_LEAD_MINUTES = 24 * 60
}
