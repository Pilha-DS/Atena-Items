/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Atena Sync
 *
 * Atena Sync is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Atena Sync is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Atena Sync.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.sync

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet

/** LWW merge for the local jotter relay replica (same rules as Atena Hub). */
class SyncService(private val database: Database) {

    private val log = LoggerFactory.getLogger(SyncService::class.java)

    fun sync(request: SyncRequest): SyncResponse = database.write { connection ->
        val sequence = Sequence(connection)
        val applied = applyNotes(connection, request.notes, sequence)
        val deletedApplied = applyDeletions(connection, request.deletions, sequence)
        val categoriesApplied = applyCategories(connection, request.categories, sequence)

        val maxSeq = sequence.current()
        sequence.persist()

        val response = SyncResponse(
            cursor = maxSeq,
            serverTime = System.currentTimeMillis(),
            notes = connection.queryList(
                "$SELECT_NOTE WHERE sync_seq > ? AND sync_seq <= ? ORDER BY sync_seq",
                request.cursor, maxSeq
            ) { it.readNote() },
            deletions = connection.queryList(
                "SELECT uuid, deleted_at FROM tombstones WHERE sync_seq > ? AND sync_seq <= ? ORDER BY sync_seq",
                request.cursor, maxSeq
            ) { DeletionDto(uuid = it.getString(1), deletedAt = it.getLong(2)) },
            categories = connection.queryList(
                "SELECT name FROM categories WHERE sync_seq > ? AND sync_seq <= ? ORDER BY sync_seq",
                request.cursor, maxSeq
            ) { it.getString(1) }
        )

        if (request.notes.isNotEmpty() || request.deletions.isNotEmpty() || request.categories.isNotEmpty()) {
            log.info(
                "sync in: {} notes ({} applied), {} deletions ({} applied), {} categories ({} new) -> cursor {}",
                request.notes.size, applied, request.deletions.size, deletedApplied,
                request.categories.size, categoriesApplied, response.cursor
            )
        }
        response
    }

    /** Snapshot of everything after [cursor], for pushing to a peer/hub. */
    fun changesSince(cursor: Long): SyncRequest = database.read { connection ->
        val maxSeq = connection.query(
            "SELECT value FROM meta WHERE key = ?", Database.KEY_SEQ
        ) { it.getString(1)?.toLongOrNull() ?: 0L } ?: 0L
        SyncRequest(
            cursor = cursor,
            deviceId = database.deviceId(),
            notes = connection.queryList(
                "$SELECT_NOTE WHERE sync_seq > ? AND sync_seq <= ? ORDER BY sync_seq",
                cursor, maxSeq
            ) { it.readNote() },
            deletions = connection.queryList(
                "SELECT uuid, deleted_at FROM tombstones WHERE sync_seq > ? AND sync_seq <= ? ORDER BY sync_seq",
                cursor, maxSeq
            ) { DeletionDto(uuid = it.getString(1), deletedAt = it.getLong(2)) },
            categories = connection.queryList(
                "SELECT name FROM categories WHERE sync_seq > ? AND sync_seq <= ? ORDER BY sync_seq",
                cursor, maxSeq
            ) { it.getString(1) }
        )
    }

    fun currentSeq(): Long = database.read { connection ->
        connection.query("SELECT value FROM meta WHERE key = ?", Database.KEY_SEQ) {
            it.getString(1)?.toLongOrNull() ?: 0L
        } ?: 0L
    }

    private fun applyNotes(connection: Connection, notes: List<NoteDto>, sequence: Sequence): Int {
        var applied = 0
        for (raw in notes) {
            val note = if (raw.isEncrypted) raw.copy(title = "", content = "") else raw
            val uuid = note.uuid.trim()
            if (uuid.isEmpty()) continue

            val updatedTime = note.updatedTime.coerceAtLeast(0L)
            val storedUpdatedTime = connection.query(
                "SELECT updated_time FROM notes WHERE uuid = ?", uuid
            ) { it.getLong(1) }
            val tombstoneAt = connection.query(
                "SELECT deleted_at FROM tombstones WHERE uuid = ?", uuid
            ) { it.getLong(1) }

            if (storedUpdatedTime == null) {
                if (tombstoneAt != null && tombstoneAt >= updatedTime) continue
                if (tombstoneAt != null) {
                    connection.execute("DELETE FROM tombstones WHERE uuid = ?", uuid)
                }
                connection.execute(
                    "INSERT INTO notes(uuid, title, content, category, created_time, updated_time, " +
                        "is_pinned, is_locked, is_trashed, open_in_edit_mode, is_encrypted, " +
                        "encryption_salt, encrypted_content, format, sync_seq) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    uuid, note.title, note.content, note.category,
                    note.createdTime.coerceAtLeast(0L), updatedTime,
                    note.isPinned.toInt(), note.isLocked.toInt(), note.isTrashed.toInt(),
                    note.openInEditMode?.toInt(), note.isEncrypted.toInt(),
                    note.encryptionSalt, note.encryptedContent, note.format, sequence.next()
                )
                applied++
            } else if (updatedTime > storedUpdatedTime) {
                connection.execute(
                    "UPDATE notes SET title = ?, content = ?, category = ?, created_time = ?, " +
                        "updated_time = ?, is_pinned = ?, is_locked = ?, is_trashed = ?, " +
                        "open_in_edit_mode = ?, is_encrypted = ?, encryption_salt = ?, " +
                        "encrypted_content = ?, format = ?, sync_seq = ? WHERE uuid = ?",
                    note.title, note.content, note.category,
                    minOf(note.createdTime, storedCreatedTime(connection, uuid) ?: note.createdTime),
                    updatedTime, note.isPinned.toInt(), note.isLocked.toInt(), note.isTrashed.toInt(),
                    note.openInEditMode?.toInt(), note.isEncrypted.toInt(),
                    note.encryptionSalt, note.encryptedContent, note.format, sequence.next(), uuid
                )
                applied++
            }
            ensureCategory(connection, note.category, sequence)
        }
        return applied
    }

    private fun storedCreatedTime(connection: Connection, uuid: String): Long? =
        connection.query("SELECT created_time FROM notes WHERE uuid = ?", uuid) { it.getLong(1) }

    private fun applyDeletions(connection: Connection, deletions: List<DeletionDto>, sequence: Sequence): Int {
        var applied = 0
        for (deletion in deletions) {
            val uuid = deletion.uuid.trim()
            if (uuid.isEmpty()) continue
            val deletedAt = deletion.deletedAt.coerceAtLeast(0L)

            val noteUpdatedTime = connection.query(
                "SELECT updated_time FROM notes WHERE uuid = ?", uuid
            ) { it.getLong(1) }
            if (noteUpdatedTime != null && deletedAt < noteUpdatedTime) continue

            if (noteUpdatedTime != null) connection.execute("DELETE FROM notes WHERE uuid = ?", uuid)

            val storedDeletedAt = connection.query(
                "SELECT deleted_at FROM tombstones WHERE uuid = ?", uuid
            ) { it.getLong(1) }
            if (storedDeletedAt == null || deletedAt > storedDeletedAt) {
                connection.execute(
                    "INSERT INTO tombstones(uuid, deleted_at, sync_seq) VALUES(?,?,?) " +
                        "ON CONFLICT(uuid) DO UPDATE SET deleted_at = excluded.deleted_at, sync_seq = excluded.sync_seq",
                    uuid, deletedAt, sequence.next()
                )
                applied++
            }
        }
        return applied
    }

    private fun applyCategories(connection: Connection, categories: List<String>, sequence: Sequence): Int {
        var applied = 0
        for (raw in categories) {
            val name = raw.trim()
            if (name.isEmpty()) continue
            val exists = connection.query("SELECT 1 FROM categories WHERE name = ?", name) { true } ?: false
            if (!exists) {
                connection.execute(
                    "INSERT INTO categories(name, created_time, sync_seq) VALUES(?,?,?)",
                    name, System.currentTimeMillis(), sequence.next()
                )
                applied++
            }
        }
        return applied
    }

    private fun ensureCategory(connection: Connection, raw: String, sequence: Sequence) {
        val name = raw.trim()
        if (name.isEmpty()) return
        val exists = connection.query("SELECT 1 FROM categories WHERE name = ?", name) { true } ?: false
        if (!exists) {
            connection.execute(
                "INSERT INTO categories(name, created_time, sync_seq) VALUES(?,?,?)",
                name, System.currentTimeMillis(), sequence.next()
            )
        }
    }

    private class Sequence(private val connection: Connection) {
        private val initial = connection.query(
            "SELECT value FROM meta WHERE key = ?", Database.KEY_SEQ
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
                Database.KEY_SEQ, last.toString()
            )
        }
    }

    private fun ResultSet.readNote(): NoteDto = NoteDto(
        uuid = getString("uuid"),
        title = getString("title"),
        content = getString("content"),
        category = getString("category"),
        createdTime = getLong("created_time"),
        updatedTime = getLong("updated_time"),
        isPinned = getInt("is_pinned") != 0,
        isLocked = getInt("is_locked") != 0,
        isTrashed = getInt("is_trashed") != 0,
        openInEditMode = getObject("open_in_edit_mode")?.let { (it as Number).toInt() != 0 },
        isEncrypted = getInt("is_encrypted") != 0,
        encryptionSalt = getString("encryption_salt"),
        encryptedContent = getString("encrypted_content"),
        format = getString("format") ?: "text"
    )

    private companion object {
        const val SELECT_NOTE =
            "SELECT uuid, title, content, category, created_time, updated_time, is_pinned, " +
                "is_locked, is_trashed, open_in_edit_mode, is_encrypted, encryption_salt, " +
                "encrypted_content, format FROM notes"
    }
}
