/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Atena Hub
 *
 * Atena Hub is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Atena Hub is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Atena Hub.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.hub

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet

/**
 * The agenda half of the hub.
 *
 * Same contract as the notes sync, on its own cursor:
 *
 *  1. a pushed event is applied only when its `updatedTime` is newer than the
 *     stored copy (last-write-wins on the client clock);
 *  2. the response carries everything written after the client's cursor,
 *     ordered by a monotonic sequence, so nothing is missed or repeated.
 *
 * The last write to reach the server with the newest timestamp is the one that
 * survives: "the most recent data wins on the server".
 */
class AgendaSyncService(private val database: Database) {

    private val log = LoggerFactory.getLogger(AgendaSyncService::class.java)

    fun sync(request: AgendaSyncRequest, remoteIp: String = ""): AgendaSyncResponse =
        database.write { connection ->
            val sequence = Sequence(connection)

            database.recordDevice(connection, request.deviceId, request.deviceName, remoteIp)

            val applied = applyEvents(connection, request.events, sequence)
            val deletedApplied = applyDeletions(connection, request.deletions, sequence)

            val maxSeq = sequence.current()
            sequence.persist()

            val response = AgendaSyncResponse(
                cursor = maxSeq,
                serverTime = System.currentTimeMillis(),
                events = connection.queryList(
                    "$SELECT_EVENT WHERE sync_seq > ? AND sync_seq <= ? ORDER BY sync_seq",
                    request.cursor, maxSeq
                ) { it.readEvent() },
                deletions = connection.queryList(
                    "SELECT uuid, deleted_at FROM agenda_tombstones " +
                        "WHERE sync_seq > ? AND sync_seq <= ? ORDER BY sync_seq",
                    request.cursor, maxSeq
                ) { AgendaDeletionDto(uuid = it.getString(1), deletedAt = it.getLong(2)) }
            )

            if (request.events.isNotEmpty() || request.deletions.isNotEmpty()) {
                log.info(
                    "agenda sync in: {} events ({} applied), {} deletions ({} applied) " +
                        "-> out: cursor {} +{} events +{} deletions",
                    request.events.size, applied, request.deletions.size, deletedApplied,
                    response.cursor, response.events.size, response.deletions.size
                )
            } else {
                log.info(
                    "agenda sync in: pull only -> cursor {} (+{} events)",
                    response.cursor, response.events.size
                )
            }
            response
        }

    // ---------------------------------------------------------------------
    // Push
    // ---------------------------------------------------------------------

    private fun applyEvents(connection: Connection, events: List<AgendaEventDto>, sequence: Sequence): Int {
        var applied = 0
        for (event in events) {
            val uuid = event.uuid.trim()
            if (uuid.isEmpty()) continue

            val updatedTime = event.updatedTime.coerceAtLeast(0L)
            val storedUpdatedTime = connection.query(
                "SELECT updated_time FROM agenda_events WHERE uuid = ?", uuid
            ) { it.getLong(1) }
            val tombstoneAt = connection.query(
                "SELECT deleted_at FROM agenda_tombstones WHERE uuid = ?", uuid
            ) { it.getLong(1) }

            if (storedUpdatedTime == null) {
                // Deleted elsewhere after this version was written: the
                // deletion is newer, so the event stays gone.
                if (tombstoneAt != null && tombstoneAt >= updatedTime) continue
                if (tombstoneAt != null) {
                    connection.execute("DELETE FROM agenda_tombstones WHERE uuid = ?", uuid)
                }
                connection.execute(
                    "INSERT INTO agenda_events(uuid, title, description, location, start_time, " +
                        "end_time, is_all_day, color, created_time, updated_time, sync_seq) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    uuid, event.title, event.description, event.location,
                    event.startTime.coerceAtLeast(0L), event.endTime.coerceAtLeast(0L),
                    event.allDay.toInt(), event.color,
                    event.createdTime.coerceAtLeast(0L), updatedTime, sequence.next()
                )
                applied++
            } else if (updatedTime > storedUpdatedTime) {
                connection.execute(
                    "UPDATE agenda_events SET title = ?, description = ?, location = ?, " +
                        "start_time = ?, end_time = ?, is_all_day = ?, color = ?, " +
                        "created_time = ?, updated_time = ?, sync_seq = ? WHERE uuid = ?",
                    event.title, event.description, event.location,
                    event.startTime.coerceAtLeast(0L), event.endTime.coerceAtLeast(0L),
                    event.allDay.toInt(), event.color,
                    minOf(event.createdTime.coerceAtLeast(0L), storedCreatedTime(connection, uuid) ?: Long.MAX_VALUE),
                    updatedTime, sequence.next(), uuid
                )
                applied++
            }
            // Equal or older: the server copy wins and nothing changes.
        }
        return applied
    }

    private fun storedCreatedTime(connection: Connection, uuid: String): Long? =
        connection.query("SELECT created_time FROM agenda_events WHERE uuid = ?", uuid) { it.getLong(1) }

    private fun applyDeletions(connection: Connection, deletions: List<AgendaDeletionDto>, sequence: Sequence): Int {
        var applied = 0
        for (deletion in deletions) {
            val uuid = deletion.uuid.trim()
            if (uuid.isEmpty()) continue
            val deletedAt = deletion.deletedAt.coerceAtLeast(0L)

            val eventUpdatedTime = connection.query(
                "SELECT updated_time FROM agenda_events WHERE uuid = ?", uuid
            ) { it.getLong(1) }
            // The event was edited after it was deleted elsewhere: the edit wins.
            if (eventUpdatedTime != null && deletedAt < eventUpdatedTime) continue

            if (eventUpdatedTime != null) connection.execute("DELETE FROM agenda_events WHERE uuid = ?", uuid)

            val storedDeletedAt = connection.query(
                "SELECT deleted_at FROM agenda_tombstones WHERE uuid = ?", uuid
            ) { it.getLong(1) }
            if (storedDeletedAt == null || deletedAt > storedDeletedAt) {
                connection.execute(
                    "INSERT INTO agenda_tombstones(uuid, deleted_at, sync_seq) VALUES(?,?,?) " +
                        "ON CONFLICT(uuid) DO UPDATE SET deleted_at = excluded.deleted_at, " +
                        "sync_seq = excluded.sync_seq",
                    uuid, deletedAt, sequence.next()
                )
                applied++
            }
        }
        return applied
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * The agenda sequence, stored under its own meta key so a notes client
     * cursor can never be confused with an agenda one.
     */
    private class Sequence(private val connection: Connection) {
        private val initial = connection.query(
            "SELECT value FROM meta WHERE key = ?", Database.KEY_AGENDA_SEQ
        ) { it.getString(1)?.toLongOrNull() ?: 0L } ?: 0L

        private var last = initial

        fun next(): Long {
            last += 1
            return last
        }

        fun current(): Long = last

        fun persist() {
            if (last == initial) return
            connection.execute(
                "INSERT INTO meta(key, value) VALUES(?, ?) " +
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                Database.KEY_AGENDA_SEQ, last.toString()
            )
        }
    }

    private fun ResultSet.readEvent(): AgendaEventDto = AgendaEventDto(
        uuid = getString("uuid"),
        title = getString("title"),
        description = getString("description"),
        location = getString("location"),
        startTime = getLong("start_time"),
        endTime = getLong("end_time"),
        allDay = getInt("is_all_day") != 0,
        color = getString("color") ?: "",
        createdTime = getLong("created_time"),
        updatedTime = getLong("updated_time")
    )

    private companion object {
        const val SELECT_EVENT =
            "SELECT uuid, title, description, location, start_time, end_time, is_all_day, " +
                "color, created_time, updated_time FROM agenda_events"
    }
}
