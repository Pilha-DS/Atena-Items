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

package com.openappslabs.jotter.server

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * SQLite storage.
 *
 * Writes are serialized by one lock (one process, one writer) and every reader
 * gets its own connection with WAL enabled, so a pull never waits for a push to
 * finish committing.
 */
class Database(private val file: Path) {

    private val log = LoggerFactory.getLogger(Database::class.java)
    private val writeLock = ReentrantLock()

    @Volatile
    private var token: String = ""

    /** Creates the schema when needed and resolves the sync token. */
    fun init(tokenOverride: String?) {
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        open().use { connection ->
            connection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
            createSchema(connection)
            token = if (tokenOverride != null) {
                setMeta(connection, KEY_TOKEN, tokenOverride)
                log.info("using the token from JOTTER_SERVER_TOKEN")
                tokenOverride
            } else {
                val existing = meta(connection, KEY_TOKEN)
                if (existing != null) {
                    existing
                } else {
                    val generated = UUID.randomUUID().toString().replace("-", "")
                    setMeta(connection, KEY_TOKEN, generated)
                    log.info("generated a new sync token (it is stored in the database)")
                    generated
                }
            }
            setMeta(connection, KEY_CREATED, System.currentTimeMillis().toString())
            val removed = purgeOldTombstones(connection)
            if (removed > 0) log.info("purged {} tombstones older than {} days", removed, TOMBSTONE_DAYS)
        }
        log.info("database ready at {}", file.toAbsolutePath())
    }

    fun authToken(): String = token

    /** Constant-time comparison, so a wrong token cannot be probed byte by byte. */
    fun tokenMatches(provided: String?): Boolean {
        val expected = token
        if (provided == null || expected.isEmpty() || provided.length != expected.length) return false
        var diff = 0
        for (i in provided.indices) diff = diff or (provided[i].code xor expected[i].code)
        return diff == 0
    }

    fun <T> read(block: (Connection) -> T): T = open().use(block)

    /**
     * Runs [block] inside a transaction. Everything that changes the dataset
     * (applying a push and reading the response) happens under the same lock,
     * which keeps the sequence numbers gap-free and monotonic.
     */
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
            // A pull and a push can overlap; wait instead of failing with BUSY.
            statement.execute("PRAGMA busy_timeout = 5000")
            // WAL only protects against a process crash; FULL also survives a
            // power loss, which matters because a client clears its "pending"
            // flag as soon as this server confirmed the write.
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
            CREATE TABLE IF NOT EXISTS devices (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL DEFAULT '',
                last_seen INTEGER NOT NULL,
                last_ip TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        connection.createStatement().use { statement ->
            statements.forEach(statement::execute)
        }
    }

    /**
     * Remembers which device talked to the server, so the start page can list
     * them. The name is whatever the user typed in the app on first connect;
     * an empty name keeps the previous one, so older clients do not blank it.
     */
    fun recordDevice(connection: Connection, id: String, name: String, ip: String) {
        val key = id.trim().ifEmpty { "ip:$ip" }.take(128)
        val cleanName = name.trim().take(64)
        connection.execute(
            "INSERT INTO devices(id, name, last_seen, last_ip) VALUES(?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET " +
                "name = CASE WHEN excluded.name = '' THEN devices.name ELSE excluded.name END, " +
                "last_seen = excluded.last_seen, last_ip = excluded.last_ip",
            key, cleanName, System.currentTimeMillis(), ip.take(64)
        )
    }

    fun devices(connection: Connection): List<DeviceDto> = connection.queryList(
        "SELECT id, name, last_seen, last_ip FROM devices ORDER BY last_seen DESC LIMIT 50"
    ) { DeviceDto(it.getString(1), it.getString(2), it.getLong(3), it.getString(4)) }

    private fun purgeOldTombstones(connection: Connection): Int {
        val cutoff = System.currentTimeMillis() - TOMBSTONE_DAYS * 24L * 60L * 60L * 1000L
        return connection.prepareStatement("DELETE FROM tombstones WHERE deleted_at < ?").use { statement ->
            statement.setLong(1, cutoff)
            statement.executeUpdate()
        }
    }

    private fun meta(connection: Connection, key: String): String? =
        connection.prepareStatement("SELECT value FROM meta WHERE key = ?").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }

    private fun setMeta(connection: Connection, key: String, value: String): String {
        connection.prepareStatement(
            "INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value"
        ).use { statement ->
            statement.setString(1, key)
            statement.setString(2, value)
            statement.executeUpdate()
        }
        return value
    }

    companion object {
        const val KEY_TOKEN = "auth_token"
        const val KEY_SEQ = "sync_seq"
        private const val KEY_CREATED = "created_at"
        private const val TOMBSTONE_DAYS = 180L
    }
}

/** Small JDBC helpers, kept local to the server. */
internal fun Connection.execute(sql: String, vararg args: Any?): Int =
    prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
    }

internal fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? =
    prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { result -> if (result.next()) map(result) else null }
    }

internal fun <T> Connection.queryList(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { result ->
            buildList {
                while (result.next()) add(map(result))
            }
        }
    }
