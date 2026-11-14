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

package com.openappslabs.agenda.data.source

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * The local SQLite database.
 *
 * One file, opened per operation: the desktop app writes rarely (a save, a
 * sync) and WAL keeps a read from waiting for a write. No ORM on purpose —
 * the schema is small and the sync code needs precise SQL anyway.
 */
class AgendaDatabase(private val file: Path) {

    @Volatile
    private var ready = false

    fun init() {
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        open().use { connection ->
            createSchema(connection)
        }
        ready = true
    }

    fun <T> read(block: (Connection) -> T): T {
        ensureReady()
        return open().use(block)
    }

    /** Everything that changes the dataset happens in one transaction. */
    fun <T> write(block: (Connection) -> T): T {
        ensureReady()
        return open().use { connection ->
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

    /**
     * The schema is created by a background thread at startup, so a query can
     * arrive first. Whoever gets here first creates it once; the rest wait.
     */
    private fun ensureReady() {
        if (ready) return
        synchronized(this) {
            if (!ready) init()
        }
    }

    private fun open(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA busy_timeout = 5000")
            statement.execute("PRAGMA foreign_keys = ON")
        }
        return connection
    }

    private fun createSchema(connection: Connection) {
        val statements = listOf(
            """
            CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL UNIQUE,
                title TEXT NOT NULL DEFAULT '',
                description TEXT NOT NULL DEFAULT '',
                location TEXT NOT NULL DEFAULT '',
                start_time INTEGER NOT NULL,
                end_time INTEGER NOT NULL,
                is_all_day INTEGER NOT NULL DEFAULT 0,
                color TEXT NOT NULL DEFAULT '',
                created_time INTEGER NOT NULL,
                updated_time INTEGER NOT NULL,
                needs_sync INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_events_range ON events (start_time, end_time)",
            "CREATE INDEX IF NOT EXISTS index_events_pending ON events (needs_sync, updated_time)",
            """
            CREATE TABLE IF NOT EXISTS tombstones (
                uuid TEXT PRIMARY KEY,
                deleted_at INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS meta (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent()
        )
        connection.createStatement().use { statement ->
            statements.forEach(statement::execute)
        }
    }
}

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
