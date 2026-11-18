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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A quick timer ("assunto rápido"): a short, named countdown, like the phone's
 * clock timer. "Ir ao mercado em 20 minutos" is one of these, not an event.
 *
 * Only [endTime] matters after creation, so the countdown survives a restart
 * and can be shared with the other devices. `needsSync` keeps the row in the
 * next push until the hub confirms it; `notified` is local (each device rings
 * once, on its own).
 */
data class QuickTimer(
    val id: Int = 0,
    val uuid: String,
    val title: String,
    val endTime: Long,
    val createdTime: Long = 0L,
    val updatedTime: Long = 0L,
    val needsSync: Boolean = true,
    val notified: Boolean = false
)

/**
 * Parsing, formatting and presets for the quick timers. Kept out of the UI so
 * the same rules can be tested without a composition.
 */
object QuickTimerTimes {

    /** One minute up to one day: enough for "chego em 3 horas", short of an alarm clock app. */
    const val MIN_MINUTES = 1L
    const val MAX_MINUTES = 24L * 60L

    /** The buttons under the duration field, in minutes. */
    val presets: List<Int> = listOf(5, 10, 15, 25, 30, 60, 120)

    private val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private val colon = Regex("""^(\d{1,2}):([0-5]\d)$""")
    private val hoursAndMinutes =
        Regex("""^(\d+)\s*h(?:oras?)?(?:\s*(\d{1,2})\s*(?:m|min|minutos?)?)?$""")
    private val minutesOnly = Regex("""^(\d+)\s*(?:m|min|minutos?)?$""")

    /**
     * Reads what people actually type: `25`, `25min`, `1h`, `1h30`, `1:30`,
     * `2 horas`. Returns `null` for anything outside 1 minute – 24 hours, so
     * the form can show a single clear message.
     */
    fun parseDuration(raw: String): Duration? {
        val text = raw.trim().lowercase(Locale.ROOT).replace(",", ".")
        if (text.isEmpty()) return null

        colon.matchEntire(text)?.let { match ->
            val hours = match.groupValues[1].toLong()
            val minutes = match.groupValues[2].toLong()
            return durationOrNull(hours * 60 + minutes)
        }
        hoursAndMinutes.matchEntire(text)?.let { match ->
            val hours = match.groupValues[1].toLong()
            val minutes = match.groupValues[2].toLongOrNull() ?: 0L
            return durationOrNull(hours * 60 + minutes)
        }
        minutesOnly.matchEntire(text)?.let { match ->
            return durationOrNull(match.groupValues[1].toLong())
        }
        return null
    }

    private fun durationOrNull(totalMinutes: Long): Duration? =
        if (totalMinutes in MIN_MINUTES..MAX_MINUTES) Duration.ofMinutes(totalMinutes) else null

    /** "12:34", or "1:02:03" once the countdown passes an hour. Zero when it is over. */
    fun remainingLabel(endTime: Long, now: Long): String {
        val remaining = (endTime - now).coerceAtLeast(0L)
        val totalSeconds = remaining / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    /** "termina às 15:42", in the local zone. */
    fun endLabel(endTime: Long): String {
        val time = Instant.ofEpochMilli(endTime).atZone(ZoneId.systemDefault()).toLocalTime()
        return time.format(clockFormat)
    }

    fun isExpired(timer: QuickTimer, now: Long): Boolean = timer.endTime <= now
}
