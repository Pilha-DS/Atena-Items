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
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Transport + jotter relay replica. Domain data still lives in each app's Room DB;
 * this file only holds a non-canonical copy used to converge peers and the hub.
 */
class Database(private val file: Path) {

    private val log = LoggerFactory.getLogger(Database::class.java)
    private val writeLock = ReentrantLock()

    @Volatile
    private var token: String = ""

    @Volatile
    private var deviceId: String = ""

    fun init(tokenOverride: String?, deviceIdOverride: String?) {
        ensureDir(file.toAbsolutePath().parent)
        open().use { connection ->
            connection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
            createSchema(connection)
            token = if (tokenOverride != null) {
                setMeta(connection, KEY_TOKEN, tokenOverride)
                log.info("using pairing token from the environment")
                tokenOverride
            } else {
                val existing = meta(connection, KEY_TOKEN)
                if (existing != null) {
                    existing
                } else {
                    val generated = newToken()
                    setMeta(connection, KEY_TOKEN, generated)
                    log.info("generated a new pairing token")
                    generated
                }
            }
            deviceId = if (!deviceIdOverride.isNullOrBlank()) {
                setMeta(connection, KEY_DEVICE_ID, deviceIdOverride)
                deviceIdOverride
            } else {
                val existing = meta(connection, KEY_DEVICE_ID)
                if (existing != null) {
                    existing
                } else {
                    val generated = newToken()
                    setMeta(connection, KEY_DEVICE_ID, generated)
                    generated
                }
            }
            if (meta(connection, KEY_SEQ) == null) {
                setMeta(connection, KEY_SEQ, "0")
            }
        }
        log.info("database ready at {}", file.toAbsolutePath())
    }

    fun authToken(): String = token

    fun deviceId(): String = deviceId

    fun tokenMatches(provided: String?): Boolean {
        val expected = token
        if (provided == null || expected.isEmpty() || provided.length != expected.length) return false
        var diff = 0
        for (i in provided.indices) diff = diff or (provided[i].code xor expected[i].code)
        return diff == 0
    }

    fun hubUrl(): String? = read { meta(it, KEY_HUB_URL) }?.takeIf { it.isNotBlank() }

    fun hubToken(): String? = read { meta(it, KEY_HUB_TOKEN) }?.takeIf { it.isNotBlank() }

    fun setHub(url: String?, token: String?) = write { connection ->
        if (url.isNullOrBlank() || token.isNullOrBlank()) {
            connection.execute("DELETE FROM meta WHERE key = ?", KEY_HUB_URL)
            connection.execute("DELETE FROM meta WHERE key = ?", KEY_HUB_TOKEN)
            connection.execute("DELETE FROM meta WHERE key = ?", KEY_HUB_CURSOR)
        } else {
            setMeta(connection, KEY_HUB_URL, normalizeHubUrl(url))
            setMeta(connection, KEY_HUB_TOKEN, token.trim())
        }
    }

    fun hubCursor(): Long = read {
        meta(it, KEY_HUB_CURSOR)?.toLongOrNull() ?: 0L
    } ?: 0L

    fun setHubCursor(cursor: Long) = write { connection ->
        setMeta(connection, KEY_HUB_CURSOR, cursor.toString())
    }

    fun hubPushCursor(): Long = read {
        meta(it, KEY_HUB_PUSH_CURSOR)?.toLongOrNull() ?: 0L
    } ?: 0L

    fun setHubPushCursor(cursor: Long) = write { connection ->
        setMeta(connection, KEY_HUB_PUSH_CURSOR, cursor.toString())
    }

    fun peerPullCursor(peerDeviceId: String): Long = read {
        it.query(
            "SELECT pull_cursor FROM peer_cursors WHERE peer_id = ?", peerDeviceId
        ) { rs -> rs.getLong(1) } ?: 0L
    } ?: 0L

    fun peerPushCursor(peerDeviceId: String): Long = read {
        it.query(
            "SELECT push_cursor FROM peer_cursors WHERE peer_id = ?", peerDeviceId
        ) { rs -> rs.getLong(1) } ?: 0L
    } ?: 0L

    fun setPeerCursors(peerDeviceId: String, pullCursor: Long, pushCursor: Long) = write { connection ->
        connection.execute(
            "INSERT INTO peer_cursors(peer_id, pull_cursor, push_cursor, updated_at) VALUES(?,?,?,?) " +
                "ON CONFLICT(peer_id) DO UPDATE SET pull_cursor = excluded.pull_cursor, " +
                "push_cursor = excluded.push_cursor, updated_at = excluded.updated_at",
            peerDeviceId, pullCursor, pushCursor, System.currentTimeMillis()
        )
    }

    fun <T> read(block: (Connection) -> T): T = open().use(block)

    fun <T> write(block: (Connection) -> T): T = writeLock.withLock {
        open().use { connection ->
            connection.autoCommit = false
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (error: Throwable) {
                runCatching { connection.rollback() }
                throw error
            }
        }
    }

    private fun open(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA busy_timeout = 5000")
            statement.execute("PRAGMA synchronous = FULL")
        }
        return connection
    }

    private fun createSchema(connection: Connection) {
        val statements = listOf(
            """
            CREATE TABLE IF NOT EXISTS meta (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS notes (
                uuid TEXT PRIMARY KEY,
                title TEXT NOT NULL DEFAULT '',
                content TEXT NOT NULL DEFAULT '',
                category TEXT NOT NULL DEFAULT '',
                created_time INTEGER NOT NULL,
                updated_time INTEGER NOT NULL,
                is_pinned INTEGER NOT NULL DEFAULT 0,
                is_locked INTEGER NOT NULL DEFAULT 0,
                is_trashed INTEGER NOT NULL DEFAULT 0,
                open_in_edit_mode INTEGER DEFAULT NULL,
                is_encrypted INTEGER NOT NULL DEFAULT 0,
                encryption_salt TEXT DEFAULT NULL,
                encrypted_content TEXT DEFAULT NULL,
                format TEXT NOT NULL DEFAULT 'text',
                sync_seq INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_notes_sync_seq ON notes (sync_seq)",
            """
            CREATE TABLE IF NOT EXISTS tombstones (
                uuid TEXT PRIMARY KEY,
                deleted_at INTEGER NOT NULL,
                sync_seq INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_tombstones_sync_seq ON tombstones (sync_seq)",
            """
            CREATE TABLE IF NOT EXISTS categories (
                name TEXT PRIMARY KEY,
                created_time INTEGER NOT NULL,
                sync_seq INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_categories_sync_seq ON categories (sync_seq)",
            """
            CREATE TABLE IF NOT EXISTS peer_cursors (
                peer_id TEXT PRIMARY KEY,
                pull_cursor INTEGER NOT NULL,
                push_cursor INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        connection.createStatement().use { statement ->
            statements.forEach { statement.execute(it) }
        }
    }

    private fun meta(connection: Connection, key: String): String? =
        connection.query("SELECT value FROM meta WHERE key = ?", key) { it.getString(1) }

    private fun setMeta(connection: Connection, key: String, value: String): String {
        connection.execute(
            "INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            key, value
        )
        return value
    }

    companion object {
        const val KEY_TOKEN = "auth_token"
        const val KEY_SEQ = "sync_seq"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_HUB_URL = "hub_url"
        const val KEY_HUB_TOKEN = "hub_token"
        const val KEY_HUB_CURSOR = "hub_cursor"
        const val KEY_HUB_PUSH_CURSOR = "hub_push_cursor"
    }
}
