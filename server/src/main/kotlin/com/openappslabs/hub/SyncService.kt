/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Jotter is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Jotter.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.hub

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet

/**
 * The merge itself.
 *
 * A sync is one transaction that does both directions:
 *
 *  1. every pushed note is applied only when its `updatedTime` is newer than
 *     what the server already has (last-write-wins on the client clock);
 *  2. the same transaction reads back everything the client has not seen yet,
 *     using a monotonically increasing sequence number as the cursor.
 *
 * Doing both in one call means a conflict is resolved exactly once: the older
 * side loses, and the winner is already in the response the loser receives.
 */
class SyncService(private val database: Database) {

    private val log = LoggerFactory.getLogger(SyncService::class.java)

    fun sync(request: SyncRequest, remoteIp: String = ""): SyncResponse = database.write { connection ->
        val sequence = Sequence(connection)

        // Bookkeeping only: never part of the sequence, never pulled by clients.
        database.recordDevice(connection, request.deviceId, request.deviceName, remoteIp)

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
                "sync in: {} notes ({} applied), {} deletions ({} applied), {} categories ({} new) -> out: cursor {} +{} notes +{} deletions",
                request.notes.size, applied, request.deletions.size, deletedApplied,
                request.categories.size, categoriesApplied,
                response.cursor, response.notes.size, response.deletions.size
            )
        } else {
            log.info("sync in: pull only -> cursor {} (+{} notes)", response.cursor, response.notes.size)
        }
        response
    }

    // ---------------------------------------------------------------------
    // Push
    // ---------------------------------------------------------------------

    private fun applyNotes(connection: Connection, notes: List<NoteDto>, sequence: Sequence): Int {
        var applied = 0
        for (raw in notes) {
            // Defense in depth for encrypted notes: even if a client bug leaves
            // the plain text in `title`/`content`, only the ciphertext is kept.
            // The salt and the payload travel exactly as the client produced
            // them; the server never has the password to open them.
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
                // The note was deleted on another device after this version was
                // written here: the deletion is newer, so the note stays gone.
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
            } else {
                // Equal or older: the server copy wins and nothing changes.
                continue
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
            // The note was edited after it was deleted elsewhere: the edit wins.
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

    /** A note always carries a category name that the clients can show. */
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

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * The global sequence. Every write gets the next value, so a client can
     * ask for "everything after N" and never miss or repeat a change.
     */
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

internal fun Boolean.toInt(): Int = if (this) 1 else 0
