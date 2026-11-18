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
import com.openappslabs.agenda.data.model.QuickTimer
import com.openappslabs.agenda.data.source.AgendaDatabase
import com.openappslabs.agenda.data.source.execute
import com.openappslabs.agenda.data.source.query
import com.openappslabs.agenda.data.source.queryList
import com.openappslabs.agenda.data.sync.AgendaTimerDeletionDto
import com.openappslabs.agenda.data.sync.AgendaTimerDto
import com.openappslabs.agenda.data.sync.SyncMerge
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/**
 * Reads and writes quick timers. Same rules as [EventRepository]: a local save
 * bumps `updatedTime`, a deletion leaves a tombstone, and the pull applies the
 * server version only when it is newer.
 */
class QuickTimerRepository(
    private val database: AgendaDatabase,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** Newest deadline first would hide the urgent ones at the bottom: ascending. */
    fun all(): List<QuickTimer> = database.read { connection ->
        connection.queryList("$SELECT_TIMER ORDER BY end_time, title", map = ::readTimer)
    }

    fun find(uuid: String): QuickTimer? = database.read { connection ->
        findByUuid(connection, uuid)
    }

    fun expired(now: Long): List<QuickTimer> = database.read { connection ->
        connection.queryList("$SELECT_TIMER WHERE end_time <= ? ORDER BY end_time", now, map = ::readTimer)
    }

    fun save(title: String, endTime: Long): QuickTimer {
        val now = clock()
        val timer = QuickTimer(
            uuid = UUID.randomUUID().toString(),
            title = title.trim(),
            endTime = endTime,
            createdTime = now,
            updatedTime = now,
            needsSync = true,
            notified = false
        )
        database.write { connection ->
            connection.execute(
                "INSERT INTO quick_timers(uuid, title, end_time, created_time, updated_time, " +
                    "needs_sync, notified) VALUES(?,?,?,?,?,1,0)",
                timer.uuid, timer.title, timer.endTime, timer.createdTime, timer.updatedTime
            )
        }
        return timer
    }

    /** Hard delete locally, with a tombstone so the deletion reaches the hub. */
    fun delete(timer: QuickTimer) {
        val deletedAt = maxOf(clock(), timer.updatedTime + 1L)
        database.write { connection ->
            connection.execute("DELETE FROM quick_timers WHERE uuid = ?", timer.uuid)
            connection.execute(
                "INSERT INTO quick_timer_tombstones(uuid, deleted_at) VALUES(?,?) " +
                    "ON CONFLICT(uuid) DO UPDATE SET deleted_at = excluded.deleted_at",
                timer.uuid, deletedAt
            )
        }
    }

    /** Marks a timer as already rung, so a restart does not ring it again. */
    fun markNotified(uuid: String) {
        database.write { connection ->
            connection.execute("UPDATE quick_timers SET notified = 1 WHERE uuid = ?", uuid)
        }
    }

    fun pendingTimers(): List<QuickTimer> = database.read { connection ->
        connection.queryList(
            "$SELECT_TIMER WHERE needs_sync = 1 ORDER BY updated_time", map = ::readTimer
        )
    }

    fun pendingDeletions(): List<Deletion> = database.read { connection ->
        connection.queryList("SELECT uuid, deleted_at FROM quick_timer_tombstones ORDER BY deleted_at") {
            Deletion(uuid = it.getString(1), deletedAt = it.getLong(2))
        }
    }

    fun markSynced(uuids: List<String>) {
        if (uuids.isEmpty()) return
        database.write { connection ->
            val placeholders = uuids.joinToString(",") { "?" }
            connection.execute(
                "UPDATE quick_timers SET needs_sync = 0 WHERE uuid IN ($placeholders)",
                *uuids.toTypedArray()
            )
        }
    }

    fun clearDeletions(uuids: List<String>) {
        if (uuids.isEmpty()) return
        database.write { connection ->
            val placeholders = uuids.joinToString(",") { "?" }
            connection.execute(
                "DELETE FROM quick_timer_tombstones WHERE uuid IN ($placeholders)",
                *uuids.toTypedArray()
            )
        }
    }

    /** Applies the pull, using the same LWW rules as the server. */
    fun applyPulled(timers: List<AgendaTimerDto>, deletions: List<AgendaTimerDeletionDto>) {
        database.write { connection ->
            for (dto in timers) {
                if (dto.uuid.isBlank() || dto.endTime <= 0L) continue
                val local = findByUuid(connection, dto.uuid)
                val deletion = findTombstone(connection, dto.uuid)
                when (SyncMerge.pullAction(local?.updatedTime, deletion, dto.updatedTime)) {
                    SyncMerge.PullAction.INSERT -> {
                        connection.execute(
                            "DELETE FROM quick_timer_tombstones WHERE uuid = ?", dto.uuid
                        )
                        connection.execute(
                            "INSERT INTO quick_timers(uuid, title, end_time, created_time, " +
                                "updated_time, needs_sync, notified) VALUES(?,?,?,?,?,0,0)",
                            dto.uuid, dto.title, dto.endTime,
                            dto.createdTime, dto.updatedTime
                        )
                    }
                    SyncMerge.PullAction.UPDATE -> {
                        // A new deadline is a new countdown: it has to ring again.
                        val notified = if (dto.endTime != local?.endTime) 0 else (local?.notified ?: false).toInt()
                        connection.execute(
                            "UPDATE quick_timers SET title = ?, end_time = ?, created_time = ?, " +
                                "updated_time = ?, notified = ?, needs_sync = 0 WHERE uuid = ?",
                            dto.title, dto.endTime,
                            minOf(dto.createdTime, local?.createdTime ?: dto.createdTime),
                            dto.updatedTime, notified, dto.uuid
                        )
                    }
                    SyncMerge.PullAction.IGNORE -> Unit
                }
            }

            for (deletion in deletions) {
                val local = findByUuid(connection, deletion.uuid) ?: continue
                if (SyncMerge.deletionRemoves(local.updatedTime, deletion.deletedAt)) {
                    connection.execute("DELETE FROM quick_timers WHERE uuid = ?", deletion.uuid)
                }
            }
        }
    }

    private fun findByUuid(connection: Connection, uuid: String): QuickTimer? =
        connection.query("$SELECT_TIMER WHERE uuid = ?", uuid, map = ::readTimer)

    private fun findTombstone(connection: Connection, uuid: String): Deletion? =
        connection.query("SELECT uuid, deleted_at FROM quick_timer_tombstones WHERE uuid = ?", uuid) {
            Deletion(uuid = it.getString(1), deletedAt = it.getLong(2))
        }

    private fun readTimer(result: ResultSet): QuickTimer = QuickTimer(
        id = result.getInt("id"),
        uuid = result.getString("uuid"),
        title = result.getString("title") ?: "",
        endTime = result.getLong("end_time"),
        createdTime = result.getLong("created_time"),
        updatedTime = result.getLong("updated_time"),
        needsSync = result.getInt("needs_sync") != 0,
        notified = result.getInt("notified") != 0
    )

    private companion object {
        const val SELECT_TIMER =
            "SELECT id, uuid, title, end_time, created_time, updated_time, needs_sync, " +
                "notified FROM quick_timers"
    }
}

private fun Boolean.toInt(): Int = if (this) 1 else 0
