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

package com.openappslabs.agenda.data.repository

import com.openappslabs.agenda.data.model.Deletion
import com.openappslabs.agenda.data.model.Event
import com.openappslabs.agenda.data.model.EventDraft
import com.openappslabs.agenda.data.model.EventTimes
import com.openappslabs.agenda.data.source.AgendaDatabase
import com.openappslabs.agenda.data.source.execute
import com.openappslabs.agenda.data.source.query
import com.openappslabs.agenda.data.source.queryList
import com.openappslabs.agenda.data.sync.AgendaDeletionDto
import com.openappslabs.agenda.data.sync.AgendaEventDto
import com.openappslabs.agenda.data.sync.SyncMerge
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

/**
 * Reads and writes events. All persistence lives here; the UI only sees
 * [Event] and [EventDraft].
 */
class EventRepository(
    private val database: AgendaDatabase,
    private val clock: () -> Long = System::currentTimeMillis
) {

    fun eventsBetween(from: Long, to: Long): List<Event> = database.read { connection ->
        connection.queryList(
            "$SELECT_EVENT WHERE start_time < ? AND " +
                "(CASE WHEN end_time > start_time THEN end_time ELSE start_time + 1 END) > ? " +
                "ORDER BY start_time, is_all_day DESC",
            to, from, map = ::readEvent
        )
    }

    fun eventsForDate(date: LocalDate): List<Event> =
        eventsBetween(EventTimes.startOfDay(date), EventTimes.endOfDay(date))

    fun find(uuid: String): Event? = database.read { connection -> findByUuid(connection, uuid) }

    /** Creates or updates, bumps `updatedTime` and marks the row for the next push. */
    fun save(draft: EventDraft): Event {
        val existing = draft.uuid?.let(::find)
        val now = clock()
        val event = Event(
            id = existing?.id ?: 0,
            uuid = existing?.uuid ?: UUID.randomUUID().toString(),
            title = draft.title.trim(),
            description = draft.description.trim(),
            location = draft.location.trim(),
            startTime = draft.startTime,
            endTime = draft.endTime,
            allDay = draft.allDay,
            color = draft.color,
            createdTime = existing?.createdTime ?: now,
            // A save in the same millisecond as the previous one must still win
            // in last-write-wins, so the timestamp only moves forward.
            updatedTime = maxOf(now, (existing?.updatedTime ?: 0L) + 1L),
            needsSync = true
        )

        database.write { connection ->
            if (existing == null) {
                connection.execute(
                    "INSERT INTO events(uuid, title, description, location, start_time, end_time, " +
                        "is_all_day, color, created_time, updated_time, needs_sync) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,1)",
                    event.uuid, event.title, event.description, event.location,
                    event.startTime, event.endTime, event.allDay.toInt(), event.color,
                    event.createdTime, event.updatedTime
                )
            } else {
                connection.execute(
                    "UPDATE events SET title = ?, description = ?, location = ?, start_time = ?, " +
                        "end_time = ?, is_all_day = ?, color = ?, updated_time = ?, needs_sync = 1 " +
                        "WHERE uuid = ?",
                    event.title, event.description, event.location, event.startTime,
                    event.endTime, event.allDay.toInt(), event.color, event.updatedTime, event.uuid
                )
            }
            // Editing an event that had been deleted here brings it back.
            connection.execute("DELETE FROM tombstones WHERE uuid = ?", event.uuid)
        }
        return event
    }

    /** Hard delete locally, with a tombstone so the deletion reaches the server. */
    fun delete(event: Event) {
        val deletedAt = maxOf(clock(), event.updatedTime + 1L)
        database.write { connection ->
            connection.execute("DELETE FROM events WHERE uuid = ?", event.uuid)
            connection.execute(
                "INSERT INTO tombstones(uuid, deleted_at) VALUES(?,?) " +
                    "ON CONFLICT(uuid) DO UPDATE SET deleted_at = excluded.deleted_at",
                event.uuid, deletedAt
            )
        }
    }

    fun pendingEvents(): List<Event> = database.read { connection ->
        connection.queryList("$SELECT_EVENT WHERE needs_sync = 1 ORDER BY updated_time", map = ::readEvent)
    }

    fun pendingDeletions(): List<Deletion> = database.read { connection ->
        connection.queryList("SELECT uuid, deleted_at FROM tombstones ORDER BY deleted_at") {
            Deletion(uuid = it.getString(1), deletedAt = it.getLong(2))
        }
    }

    fun markSynced(uuids: List<String>) {
        if (uuids.isEmpty()) return
        database.write { connection ->
            val placeholders = uuids.joinToString(",") { "?" }
            connection.execute(
                "UPDATE events SET needs_sync = 0 WHERE uuid IN ($placeholders)",
                *uuids.toTypedArray()
            )
        }
    }

    fun clearDeletions(uuids: List<String>) {
        if (uuids.isEmpty()) return
        database.write { connection ->
            val placeholders = uuids.joinToString(",") { "?" }
            connection.execute(
                "DELETE FROM tombstones WHERE uuid IN ($placeholders)",
                *uuids.toTypedArray()
            )
        }
    }

    /** Applies the pull, using the same LWW rules as the server. */
    fun applyPulled(events: List<AgendaEventDto>, deletions: List<AgendaDeletionDto>) {
        database.write { connection ->
            for (dto in events) {
                if (dto.uuid.isBlank()) continue
                val local = findByUuid(connection, dto.uuid)
                val deletion = findTombstone(connection, dto.uuid)
                when (SyncMerge.pullAction(local, deletion, dto.updatedTime)) {
                    SyncMerge.PullAction.INSERT -> {
                        connection.execute("DELETE FROM tombstones WHERE uuid = ?", dto.uuid)
                        connection.execute(
                            "INSERT INTO events(uuid, title, description, location, start_time, " +
                                "end_time, is_all_day, color, created_time, updated_time, needs_sync) " +
                                "VALUES(?,?,?,?,?,?,?,?,?,?,0)",
                            dto.uuid, dto.title, dto.description, dto.location,
                            dto.startTime, dto.endTime, dto.allDay.toInt(), dto.color,
                            dto.createdTime, dto.updatedTime
                        )
                    }
                    SyncMerge.PullAction.UPDATE -> connection.execute(
                        "UPDATE events SET title = ?, description = ?, location = ?, start_time = ?, " +
                            "end_time = ?, is_all_day = ?, color = ?, created_time = ?, " +
                            "updated_time = ?, needs_sync = 0 WHERE uuid = ?",
                        dto.title, dto.description, dto.location, dto.startTime, dto.endTime,
                        dto.allDay.toInt(), dto.color,
                        minOf(dto.createdTime, local?.createdTime ?: dto.createdTime),
                        dto.updatedTime, dto.uuid
                    )
                    SyncMerge.PullAction.IGNORE -> Unit
                }
            }

            for (deletion in deletions) {
                val local = findByUuid(connection, deletion.uuid) ?: continue
                if (SyncMerge.deletionRemoves(local, deletion.deletedAt)) {
                    connection.execute("DELETE FROM events WHERE uuid = ?", deletion.uuid)
                }
            }
        }
    }

    private fun findByUuid(connection: Connection, uuid: String): Event? =
        connection.query("$SELECT_EVENT WHERE uuid = ?", uuid, map = ::readEvent)

    private fun findTombstone(connection: Connection, uuid: String): Deletion? =
        connection.query("SELECT uuid, deleted_at FROM tombstones WHERE uuid = ?", uuid) {
            Deletion(uuid = it.getString(1), deletedAt = it.getLong(2))
        }

    private fun readEvent(result: ResultSet): Event = Event(
        id = result.getInt("id"),
        uuid = result.getString("uuid"),
        title = result.getString("title") ?: "",
        description = result.getString("description") ?: "",
        location = result.getString("location") ?: "",
        startTime = result.getLong("start_time"),
        endTime = result.getLong("end_time"),
        allDay = result.getInt("is_all_day") != 0,
        color = result.getString("color") ?: "",
        createdTime = result.getLong("created_time"),
        updatedTime = result.getLong("updated_time"),
        needsSync = result.getInt("needs_sync") != 0
    )

    private companion object {
        const val SELECT_EVENT =
            "SELECT id, uuid, title, description, location, start_time, end_time, is_all_day, " +
                "color, created_time, updated_time, needs_sync FROM events"
    }
}

private fun Boolean.toInt(): Int = if (this) 1 else 0
