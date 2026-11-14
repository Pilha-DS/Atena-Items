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
 * The link inbox half of the hub.
 *
 * Same contract as the notes and the agenda syncs, on its own cursor:
 *
 *  1. a pushed link is applied only when its `updatedTime` is newer than the
 *     stored copy (last-write-wins on the client clock);
 *  2. the response carries everything written after the client's cursor,
 *     ordered by a monotonic sequence, so nothing is missed or repeated.
 *
 * Links are small and written in bursts (a few in a row while browsing), so the
 * whole push is one transaction: a client that saw "ok" knows every link it
 * sent is stored.
 */
class LinksSyncService(private val database: Database) {

    private val log = LoggerFactory.getLogger(LinksSyncService::class.java)

    fun sync(
        request: LinksSyncRequest,
        remoteIp: String = "",
        caller: String = "",
        bytesIn: Long = 0L
    ): LinksSyncResponse =
        database.write { connection ->
            val sequence = Sequence(connection)

            database.recordDevice(connection, request.deviceId, request.deviceName, remoteIp)

            val applied = applyLinks(connection, request.links, sequence)
            val deletedApplied = applyDeletions(connection, request.deletions, sequence)

            val maxSeq = sequence.current()
            sequence.persist()

            val response = LinksSyncResponse(
                cursor = maxSeq,
                serverTime = System.currentTimeMillis(),
                links = connection.queryList(
                    "$SELECT_LINK WHERE sync_seq > ? AND sync_seq <= ? ORDER BY sync_seq",
                    request.cursor, maxSeq
                ) { it.readLink() },
                deletions = connection.queryList(
                    "SELECT uuid, deleted_at FROM link_tombstones " +
                        "WHERE sync_seq > ? AND sync_seq <= ? ORDER BY sync_seq",
                    request.cursor, maxSeq
                ) { LinkDeletionDto(uuid = it.getString(1), deletedAt = it.getLong(2)) }
            )

            if (request.links.isNotEmpty() || request.deletions.isNotEmpty()) {
                log.info(
                    "links sync in: {} links ({} applied), {} deletions ({} applied) " +
                        "-> out: cursor {} +{} links +{} deletions",
                    request.links.size, applied, request.deletions.size, deletedApplied,
                    response.cursor, response.links.size, response.deletions.size
                )
            } else {
                log.info(
                    "links sync in: pull only -> cursor {} (+{} links)",
                    response.cursor, response.links.size
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
                    pushed = request.links.size + request.deletions.size,
                    pulled = response.links.size + response.deletions.size,
                    bytesIn = bytesIn,
                    bytesOut = WIRE_JSON.encodeToString(LinksSyncResponse.serializer(), response)
                        .toByteArray().size.toLong()
                )
            )
            response
        }

    // ---------------------------------------------------------------------
    // Push
    // ---------------------------------------------------------------------

    private fun applyLinks(connection: Connection, links: List<LinkDto>, sequence: Sequence): Int {
        var applied = 0
        for (link in links) {
            val uuid = link.uuid.trim()
            val url = link.url.trim()
            if (uuid.isEmpty() || url.isEmpty()) continue

            val updatedTime = link.updatedTime.coerceAtLeast(0L)
            val storedUpdatedTime = connection.query(
                "SELECT updated_time FROM links WHERE uuid = ?", uuid
            ) { it.getLong(1) }
            val tombstoneAt = connection.query(
                "SELECT deleted_at FROM link_tombstones WHERE uuid = ?", uuid
            ) { it.getLong(1) }

            if (storedUpdatedTime == null) {
                // Removed on another device after this version was written: the
                // deletion is newer, so the link stays gone.
                if (tombstoneAt != null && tombstoneAt >= updatedTime) continue
                if (tombstoneAt != null) {
                    connection.execute("DELETE FROM link_tombstones WHERE uuid = ?", uuid)
                }
                connection.execute(
                    "INSERT INTO links(uuid, url, title, created_time, updated_time, " +
                        "last_opened_time, open_count, sync_seq) VALUES(?,?,?,?,?,?,?,?)",
                    uuid, url.take(MAX_URL), link.title.take(MAX_TITLE),
                    link.createdTime.coerceAtLeast(0L), updatedTime,
                    link.lastOpenedTime.coerceAtLeast(0L), link.openCount.coerceAtLeast(0),
                    sequence.next()
                )
                applied++
            } else if (updatedTime > storedUpdatedTime) {
                connection.execute(
                    "UPDATE links SET url = ?, title = ?, created_time = ?, " +
                        "updated_time = ?, last_opened_time = ?, open_count = ?, sync_seq = ? " +
                        "WHERE uuid = ?",
                    url.take(MAX_URL), link.title.take(MAX_TITLE),
                    minOf(link.createdTime.coerceAtLeast(0L), storedCreatedTime(connection, uuid) ?: Long.MAX_VALUE),
                    updatedTime, link.lastOpenedTime.coerceAtLeast(0L),
                    link.openCount.coerceAtLeast(0), sequence.next(), uuid
                )
                applied++
            }
            // Equal or older: the server copy wins and nothing changes.
        }
        return applied
    }

    private fun storedCreatedTime(connection: Connection, uuid: String): Long? =
        connection.query("SELECT created_time FROM links WHERE uuid = ?", uuid) { it.getLong(1) }

    private fun applyDeletions(connection: Connection, deletions: List<LinkDeletionDto>, sequence: Sequence): Int {
        var applied = 0
        for (deletion in deletions) {
            val uuid = deletion.uuid.trim()
            if (uuid.isEmpty()) continue
            val deletedAt = deletion.deletedAt.coerceAtLeast(0L)

            val linkUpdatedTime = connection.query(
                "SELECT updated_time FROM links WHERE uuid = ?", uuid
            ) { it.getLong(1) }
            // Opening the link bumped it after it was deleted elsewhere: the
            // newer version wins and the link comes back on the next round.
            if (linkUpdatedTime != null && deletedAt < linkUpdatedTime) continue

            if (linkUpdatedTime != null) connection.execute("DELETE FROM links WHERE uuid = ?", uuid)

            val storedDeletedAt = connection.query(
                "SELECT deleted_at FROM link_tombstones WHERE uuid = ?", uuid
            ) { it.getLong(1) }
            if (storedDeletedAt == null || deletedAt > storedDeletedAt) {
                connection.execute(
                    "INSERT INTO link_tombstones(uuid, deleted_at, sync_seq) VALUES(?,?,?) " +
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

    /** The links sequence, under its own meta key, like the agenda's. */
    private class Sequence(private val connection: Connection) {
        private val initial = connection.query(
            "SELECT value FROM meta WHERE key = ?", Database.KEY_LINKS_SEQ
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
                Database.KEY_LINKS_SEQ, last.toString()
            )
        }
    }

    private fun ResultSet.readLink(): LinkDto = LinkDto(
        uuid = getString("uuid"),
        url = getString("url") ?: "",
        title = getString("title") ?: "",
        createdTime = getLong("created_time"),
        updatedTime = getLong("updated_time"),
        lastOpenedTime = getLong("last_opened_time"),
        openCount = getInt("open_count")
    )

    private companion object {
        const val STREAM = "links"

        const val SELECT_LINK =
            "SELECT uuid, url, title, created_time, updated_time, last_opened_time, " +
                "open_count FROM links"

        /** A link is a URL, not a document: these keep a bad client bounded. */
        const val MAX_URL = 4_096
        const val MAX_TITLE = 512
    }
}
