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

package com.openappslabs.agenda.platform

import com.openappslabs.agenda.data.model.Event
import com.openappslabs.agenda.data.model.EventTimes
import com.openappslabs.agenda.data.repository.EventRepository
import com.openappslabs.agenda.data.sync.SyncPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class StatusEvent(
    val uuid: String,
    val title: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean
)

@Serializable
data class StatusSync(
    val configured: Boolean,
    val lastSyncAt: Long,
    val error: String? = null
)

/**
 * What the widget needs to know, refreshed whenever data or sync state changes.
 *
 * It carries the upcoming events themselves, so the plasmoid can show "today"
 * and "next" without parsing the database or guessing the time zone.
 */
@Serializable
data class StatusSnapshot(
    val generatedAt: Long,
    val todayCount: Int,
    val nextTitle: String? = null,
    val nextStart: Long? = null,
    val events: List<StatusEvent> = emptyList(),
    val sync: StatusSync
)

/**
 * Builds and publishes the status file. The plasmoid only reads JSON — all the
 * logic (which events are today, what is next) is decided here.
 */
class StatusPublisher(
    private val repository: EventRepository,
    private val preferences: SyncPreferences,
    private val syncError: () -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val json = Json { encodeDefaults = true }

    fun snapshot(): StatusSnapshot {
        val now = clock()
        val today = EventTimes.today()
        val from = EventTimes.startOfDay(today)
        val to = EventTimes.startOfDay(today.plusDays(WIDGET_HORIZON_DAYS))

        val events = repository.eventsBetween(from, to)
        val next = events.firstOrNull { it.endTime > now }
        val config = preferences.read()

        return StatusSnapshot(
            generatedAt = now,
            todayCount = events.count { EventTimes.isOn(it, today) },
            nextTitle = next?.title,
            nextStart = next?.startTime,
            events = events.map(::toStatusEvent),
            sync = StatusSync(
                configured = config.isConfigured,
                lastSyncAt = config.lastSyncAt,
                error = syncError()
            )
        )
    }

    fun publish() {
        IpcBridge.publishStatus(json.encodeToString(StatusSnapshot.serializer(), snapshot()))
    }

    private fun toStatusEvent(event: Event): StatusEvent = StatusEvent(
        uuid = event.uuid,
        title = event.title,
        start = event.startTime,
        end = event.endTime,
        allDay = event.allDay
    )

    private companion object {
        /** How far ahead the widget's "next days" list goes. */
        const val WIDGET_HORIZON_DAYS = 8L
    }
}
