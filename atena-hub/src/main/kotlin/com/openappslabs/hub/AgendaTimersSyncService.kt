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
 * Quick timers ("assuntos rápidos") on the hub.
 *
 * They are agenda data but live on their own stream: a timer cursor is never an
 * event cursor, so the two clients can move at different speeds. The merge is
 * the same last-write-wins on `updatedTime` used everywhere else, and a
 * deletion is a tombstone that outlives the row.
 *
 * Only the end of the countdown travels; the duration is input the client
 * already turned into [AgendaTimerDto.endTime].
 */
class AgendaTimersSyncService(private val database: Database) {

    private val log = LoggerFactory.getLogger(AgendaTimersSyncService::class.java)

    fun sync(
        request: AgendaTimerSyncRequest,
        remoteIp: String = "",
        caller: String = "",
        bytesIn: Long = 0L
    ): AgendaTimerSyncResponse =
        database.write { connection ->
            val sequence = Sequence(connection)

            database.recordDevice(connection, request.deviceId, request.deviceName, remoteIp)

            val applied = applyTimers(connection, request.timers, sequence)
            val deletedApplied = applyDeletions(connection, request.deletions, sequence)

            val maxSeq = sequence.current()
            sequence.persist()

            val response = AgendaTimerSyncResponse(
                cursor = maxSeq,
                serverTime = System.currentTimeMillis(),
                timers = connection.queryList(
                    "$SELECT_TIMER WHERE sync_seq > ? AND sync_seq <= ? ORDER BY sync_seq",
                    request.cursor, maxSeq
                ) { it.readTimer() },
                deletions = connection.queryList(
                    "SELECT uuid, deleted_at FROM agenda_timer_tombstones " +
                        "WHERE sync_seq > ? AND sync_seq <= ? ORDER BY sync_seq",
                    request.cursor, maxSeq
                ) { AgendaTimerDeletionDto(uuid = it.getString(1), deletedAt = it.getLong(2)) }
            )

            if (request.timers.isNotEmpty() || request.deletions.isNotEmpty()) {
                log.info(
                    "agenda timers sync in: {} timers ({} applied), {} deletions ({} applied) " +
                        "-> out: cursor {} +{} timers +{} deletions",
                    request.timers.size, applied, request.deletions.size, deletedApplied,
                    response.cursor, response.timers.size, response.deletions.size
                )
            } else {
                log.info(
                    "agenda timers sync in: pull only -> cursor {} (+{} timers)",
                    response.cursor, response.timers.size
                )
            }

            database.recordActivity(
                connection,
                SyncActivity(
                    stream = STREAM,
                    tokenLabel = caller,
                    deviceId = deviceKey(request.deviceId, remoteIp),
                    deviceName = request.deviceName,
                    ip = remoteIp,
                    pushed = request.timers.size + request.deletions.size,
                    pulled = response.timers.size + response.deletions.size,
                    bytesIn = bytesIn,
                    bytesOut = WIRE_JSON.encodeToString(AgendaTimerSyncResponse.serializer(), response)
                        .toByteArray().size.toLong()
                )
            )
            response
        }

    // ---------------------------------------------------------------------
    // Push
    // ---------------------------------------------------------------------

    private fun applyTimers(
        connection: Connection,
        timers: List<AgendaTimerDto>,
        sequence: Sequence
    ): Int {
        var applied = 0
        for (timer in timers) {
            val uuid = timer.uuid.trim()
            if (uuid.isEmpty() || timer.endTime <= 0L) continue

            val updatedTime = timer.updatedTime.coerceAtLeast(0L)
            val storedUpdatedTime = connection.query(
                "SELECT updated_time FROM agenda_timers WHERE uuid = ?", uuid
            ) { it.getLong(1) }
            val tombstoneAt = connection.query(
                "SELECT deleted_at FROM agenda_timer_tombstones WHERE uuid = ?", uuid
            ) { it.getLong(1) }

            if (storedUpdatedTime == null) {
                // Removed on another device after this version was written: the
                // deletion is newer, so the timer stays gone.
                if (tombstoneAt != null && tombstoneAt >= updatedTime) continue
                if (tombstoneAt != null) {
                    connection.execute("DELETE FROM agenda_timer_tombstones WHERE uuid = ?", uuid)
                }
                connection.execute(
                    "INSERT INTO agenda_timers(uuid, title, end_time, created_time, " +
                        "updated_time, sync_seq) VALUES(?,?,?,?,?,?)",
                    uuid, timer.title.take(MAX_TITLE), timer.endTime,
                    timer.createdTime.coerceAtLeast(0L), updatedTime, sequence.next()
                )
                applied++
            } else if (updatedTime > storedUpdatedTime) {
                connection.execute(
                    "UPDATE agenda_timers SET title = ?, end_time = ?, created_time = ?, " +
                        "updated_time = ?, sync_seq = ? WHERE uuid = ?",
                    timer.title.take(MAX_TITLE), timer.endTime,
                    minOf(
                        timer.createdTime.coerceAtLeast(0L),
                        storedCreatedTime(connection, uuid) ?: Long.MAX_VALUE
                    ),
                    updatedTime, sequence.next(), uuid
                )
                applied++
            }
            // Equal or older: the server copy wins and nothing changes.
        }
        return applied
    }

    private fun storedCreatedTime(connection: Connection, uuid: String): Long? =
        connection.query("SELECT created_time FROM agenda_timers WHERE uuid = ?", uuid) {
            it.getLong(1)
        }

    private fun applyDeletions(
        connection: Connection,
        deletions: List<AgendaTimerDeletionDto>,
        sequence: Sequence
    ): Int {
        var applied = 0
        for (deletion in deletions) {
            val uuid = deletion.uuid.trim()
            if (uuid.isEmpty()) continue
            val deletedAt = deletion.deletedAt.coerceAtLeast(0L)

            val timerUpdatedTime = connection.query(
                "SELECT updated_time FROM agenda_timers WHERE uuid = ?", uuid
            ) { it.getLong(1) }
            // The timer was edited after it was deleted elsewhere: the edit wins.
            if (timerUpdatedTime != null && deletedAt < timerUpdatedTime) continue

            if (timerUpdatedTime != null) {
                connection.execute("DELETE FROM agenda_timers WHERE uuid = ?", uuid)
            }

            val storedDeletedAt = connection.query(
                "SELECT deleted_at FROM agenda_timer_tombstones WHERE uuid = ?", uuid
            ) { it.getLong(1) }
            if (storedDeletedAt == null || deletedAt > storedDeletedAt) {
                connection.execute(
                    "INSERT INTO agenda_timer_tombstones(uuid, deleted_at, sync_seq) VALUES(?,?,?) " +
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

    /** The timer sequence, under its own meta key, like the events one. */
    private class Sequence(private val connection: Connection) {
        private val initial = connection.query(
            "SELECT value FROM meta WHERE key = ?", Database.KEY_AGENDA_TIMERS_SEQ
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
                Database.KEY_AGENDA_TIMERS_SEQ, last.toString()
            )
        }
    }

    private fun ResultSet.readTimer(): AgendaTimerDto = AgendaTimerDto(
        uuid = getString("uuid"),
        title = getString("title") ?: "",
        endTime = getLong("end_time"),
        createdTime = getLong("created_time"),
        updatedTime = getLong("updated_time")
    )

    private companion object {
        const val STREAM = "agenda-timers"

        const val SELECT_TIMER =
            "SELECT uuid, title, end_time, created_time, updated_time FROM agenda_timers"

        /** A timer title is a line of text, not a document. */
        const val MAX_TITLE = 512
    }
}
