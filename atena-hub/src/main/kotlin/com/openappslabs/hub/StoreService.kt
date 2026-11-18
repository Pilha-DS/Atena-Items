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
 * The universal key/value store: what a new app uses so it does not need a new
 * endpoint (or a new server release) to sync its own data.
 *
 * The contract is the same as the notes one, because it is the same problem:
 *
 *  - a record is written only when its `updatedTime` is newer (LWW);
 *  - deletions become tombstones so they survive a pull from an old client;
 *  - each `namespace/collection` has its own monotonic cursor, so two apps (or
 *    two datasets of one app) never see each other's traffic.
 *
 * The hub stores [StoreRecordDto.payload] as an opaque string. It never parses
 * it, so a todo list, a settings document, a game save or a ledger all work
 * without the server knowing what they are.
 */
class StoreService(private val database: Database, private val maxRecordBytes: Long) {

    private val log = LoggerFactory.getLogger(StoreService::class.java)

    fun sync(
        namespace: String,
        collection: String,
        request: StoreSyncRequest,
        remoteIp: String = "",
        caller: String = "",
        bytesIn: Long = 0L
    ): StoreSyncResponse = database.write { connection ->
        val sequence = Sequence(connection, storeSeqKey(namespace, collection))

        database.recordDevice(connection, request.deviceId, request.deviceName, remoteIp)

        val applied = applyRecords(connection, namespace, collection, request.records, sequence)
        val deletedApplied = applyDeletions(connection, namespace, collection, request.deletions, sequence)

        val maxSeq = sequence.current()
        sequence.persist()

        val response = StoreSyncResponse(
            cursor = maxSeq,
            serverTime = System.currentTimeMillis(),
            records = connection.queryList(
                "$SELECT_RECORD WHERE namespace = ? AND collection = ? " +
                    "AND sync_seq > ? AND sync_seq <= ? ORDER BY sync_seq",
                namespace, collection, request.cursor, maxSeq
            ) { it.readRecord() },
            deletions = connection.queryList(
                "SELECT record_key, deleted_at FROM record_tombstones " +
                    "WHERE namespace = ? AND collection = ? " +
                    "AND sync_seq > ? AND sync_seq <= ? ORDER BY sync_seq",
                namespace, collection, request.cursor, maxSeq
            ) { StoreDeletionDto(key = it.getString(1), deletedAt = it.getLong(2)) }
        )

        database.recordActivity(
            connection,
            SyncActivity(
                stream = "store:$namespace/$collection",
                tokenLabel = caller,
                deviceId = deviceKey(request.deviceId, remoteIp),
                deviceName = request.deviceName,
                ip = remoteIp,
                pushed = request.records.size + request.deletions.size,
                pulled = response.records.size + response.deletions.size,
                bytesIn = bytesIn,
                bytesOut = WIRE_JSON.encodeToString(StoreSyncResponse.serializer(), response)
                    .toByteArray().size.toLong()
            )
        )

        if (request.records.isNotEmpty() || request.deletions.isNotEmpty()) {
            log.info(
                "store {}/{} in: {} records ({} applied), {} deletions ({} applied) -> out: cursor {} +{} records",
                namespace, collection, request.records.size, applied,
                request.deletions.size, deletedApplied, maxSeq, response.records.size
            )
        }
        response
    }

    /** One record by key, or `null` when it does not exist (or is deleted). */
    fun read(namespace: String, collection: String, key: String): StoreRecordDto? = database.read { connection ->
        connection.query(
            "$SELECT_RECORD WHERE namespace = ? AND collection = ? AND record_key = ?",
            namespace, collection, key
        ) { it.readRecord() }
    }

    /**
     * A single write, for an app that just wants to put something there. It is
     * the same LWW rule as the sync, with the server clock as `updatedTime`
     * because there is no client clock in this path.
     */
    fun write(namespace: String, collection: String, key: String, payload: String): StoreRecordDto =
        database.write { connection ->
            val sequence = Sequence(connection, storeSeqKey(namespace, collection))
            val now = System.currentTimeMillis()
            val existing = connection.query(
                "SELECT created_time, updated_time FROM records " +
                    "WHERE namespace = ? AND collection = ? AND record_key = ?",
                namespace, collection, key
            ) { it.getLong(1) to it.getLong(2) }
            val created = existing?.first ?: now
            connection.execute(
                "INSERT INTO records(namespace, collection, record_key, payload, created_time, " +
                    "updated_time, sync_seq) VALUES(?,?,?,?,?,?,?) " +
                    "ON CONFLICT(namespace, collection, record_key) DO UPDATE SET " +
                    "payload = excluded.payload, updated_time = excluded.updated_time, " +
                    "sync_seq = excluded.sync_seq",
                namespace, collection, key, payload, created, now, sequence.next()
            )
            // Writing a key here brings it back if it had been deleted.
            connection.execute(
                "DELETE FROM record_tombstones WHERE namespace = ? AND collection = ? AND record_key = ?",
                namespace, collection, key
            )
            sequence.persist()
            StoreRecordDto(key = key, payload = payload, createdTime = created, updatedTime = now)
        }

    /** Every collection that already holds data, for discovery. */
    fun streams(): List<StoreStreamDto> = database.read { connection ->
        val deleted = connection.queryList(
            "SELECT namespace, collection, COUNT(*) FROM record_tombstones " +
                "GROUP BY namespace, collection"
        ) { "${it.getString(1)}/${it.getString(2)}" to it.getInt(3) }.toMap()

        // One query for every cursor: no per-row round trip while a result set
        // is open.
        val cursors = connection.queryList(
            "SELECT key, value FROM meta WHERE key LIKE 'store_seq:%'"
        ) { it.getString(1) to (it.getString(2)?.toLongOrNull() ?: 0L) }.toMap()

        connection.queryList(
            """
            SELECT namespace, collection, COUNT(*) AS total,
                   COALESCE(SUM(LENGTH(CAST(payload AS BLOB))), 0) AS bytes,
                   MAX(updated_time) AS last_write,
                   MAX(sync_seq) AS last_seq
            FROM records
            GROUP BY namespace, collection
            ORDER BY last_write DESC
            LIMIT 200
            """.trimIndent()
        ) {
            val namespace = it.getString("namespace") ?: ""
            val collection = it.getString("collection") ?: ""
            // The cursor in `meta` is the source of truth (deletions advance it
            // without leaving a row here); the max seen is only a fallback.
            val cursor = cursors[storeSeqKey(namespace, collection)] ?: it.getLong("last_seq")
            StoreStreamDto(
                namespace = namespace,
                collection = collection,
                records = it.getInt("total"),
                deleted = deleted["$namespace/$collection"] ?: 0,
                bytes = it.getLong("bytes"),
                cursor = cursor,
                lastWrite = it.getLong("last_write")
            )
        }
    }

    // ---------------------------------------------------------------------
    // Push
    // ---------------------------------------------------------------------

    private fun applyRecords(
        connection: Connection,
        namespace: String,
        collection: String,
        records: List<StoreRecordDto>,
        sequence: Sequence
    ): Int {
        var applied = 0
        for (record in records) {
            val key = record.key.trim()
            if (key.isEmpty() || key.length > MAX_KEY) continue
            // Defense in depth: the route already refuses an oversized body,
            // but a single huge record inside a small body is still wrong.
            val payloadBytes = record.payload.toByteArray().size.toLong()
            if (payloadBytes > maxRecordBytes) continue

            val updatedTime = record.updatedTime.coerceAtLeast(0L)
            val stored = connection.query(
                "SELECT updated_time, created_time FROM records " +
                    "WHERE namespace = ? AND collection = ? AND record_key = ?",
                namespace, collection, key
            ) { it.getLong(1) to it.getLong(2) }
            val tombstoneAt = connection.query(
                "SELECT deleted_at FROM record_tombstones " +
                    "WHERE namespace = ? AND collection = ? AND record_key = ?",
                namespace, collection, key
            ) { it.getLong(1) }

            if (stored == null) {
                // Deleted elsewhere after this version was written: stays gone.
                if (tombstoneAt != null && tombstoneAt >= updatedTime) continue
                if (tombstoneAt != null) {
                    connection.execute(
                        "DELETE FROM record_tombstones WHERE namespace = ? AND collection = ? AND record_key = ?",
                        namespace, collection, key
                    )
                }
                connection.execute(
                    "INSERT INTO records(namespace, collection, record_key, payload, created_time, " +
                        "updated_time, sync_seq) VALUES(?,?,?,?,?,?,?)",
                    namespace, collection, key, record.payload,
                    record.createdTime.takeIf { it > 0 } ?: updatedTime,
                    updatedTime, sequence.next()
                )
                applied++
            } else if (updatedTime > stored.first) {
                // Creation time only ever goes back, never forward: a client
                // that forgot to send it (0) must not reset the original date.
                val created = if (record.createdTime > 0) {
                    minOf(record.createdTime, stored.second)
                } else {
                    stored.second
                }
                connection.execute(
                    "UPDATE records SET payload = ?, created_time = ?, updated_time = ?, sync_seq = ? " +
                        "WHERE namespace = ? AND collection = ? AND record_key = ?",
                    record.payload, created, updatedTime, sequence.next(),
                    namespace, collection, key
                )
                applied++
            }
        }
        return applied
    }

    private fun applyDeletions(
        connection: Connection,
        namespace: String,
        collection: String,
        deletions: List<StoreDeletionDto>,
        sequence: Sequence
    ): Int {
        var applied = 0
        for (deletion in deletions) {
            val key = deletion.key.trim()
            if (key.isEmpty() || key.length > MAX_KEY) continue
            val deletedAt = deletion.deletedAt.coerceAtLeast(0L)

            val storedUpdatedTime = connection.query(
                "SELECT updated_time FROM records " +
                    "WHERE namespace = ? AND collection = ? AND record_key = ?",
                namespace, collection, key
            ) { it.getLong(1) }
            // An edit made after the deletion wins and the record comes back.
            if (storedUpdatedTime != null && deletedAt < storedUpdatedTime) continue

            if (storedUpdatedTime != null) {
                connection.execute(
                    "DELETE FROM records WHERE namespace = ? AND collection = ? AND record_key = ?",
                    namespace, collection, key
                )
            }

            val storedDeletedAt = connection.query(
                "SELECT deleted_at FROM record_tombstones " +
                    "WHERE namespace = ? AND collection = ? AND record_key = ?",
                namespace, collection, key
            ) { it.getLong(1) }
            if (storedDeletedAt == null || deletedAt > storedDeletedAt) {
                connection.execute(
                    "INSERT INTO record_tombstones(namespace, collection, record_key, deleted_at, sync_seq) " +
                        "VALUES(?,?,?,?,?) ON CONFLICT(namespace, collection, record_key) DO UPDATE SET " +
                        "deleted_at = excluded.deleted_at, sync_seq = excluded.sync_seq",
                    namespace, collection, key, deletedAt, sequence.next()
                )
                applied++
            }
        }
        return applied
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** The cursor of one collection, under its own meta key. */
    private class Sequence(private val connection: Connection, private val metaKey: String) {
        private val initial = connection.query(
            "SELECT value FROM meta WHERE key = ?", metaKey
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
                metaKey, last.toString()
            )
        }
    }

    private fun ResultSet.readRecord(): StoreRecordDto = StoreRecordDto(
        key = getString("record_key") ?: "",
        payload = getString("payload") ?: "",
        createdTime = getLong("created_time"),
        updatedTime = getLong("updated_time")
    )

    companion object {
        /** A key is an id, not a document: long enough for a path-like key. */
        const val MAX_KEY = 512

        private const val SELECT_RECORD =
            "SELECT record_key, payload, created_time, updated_time FROM records"
    }
}
