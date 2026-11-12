/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.jotter.data.source

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.openappslabs.jotter.platform.Diagnostics
import com.openappslabs.jotter.platform.Xdg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds the same Room database the Android app uses, but backed by the
 * bundled SQLite driver so it runs on a plain JVM.
 */
object DatabaseFactory {

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_isArchived_isTrashed` ON `notes` (`isArchived`, `isTrashed`)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_category` ON `notes` (`category`)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_isPinned_updatedTime` ON `notes` (`isPinned`, `updatedTime`)")
        }
    }

    /** Per-note "open in edit mode" override; NULL keeps the global default. */
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `notes` ADD COLUMN `openInEditMode` INTEGER DEFAULT NULL")
        }
    }

    /** Per-note encryption: ciphertext + salt alongside a `isEncrypted` flag. */
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `notes` ADD COLUMN `isEncrypted` INTEGER NOT NULL DEFAULT 0")
            connection.execSQL("ALTER TABLE `notes` ADD COLUMN `encryptionSalt` TEXT DEFAULT NULL")
            connection.execSQL("ALTER TABLE `notes` ADD COLUMN `encryptedContent` TEXT DEFAULT NULL")
        }
    }

    /** The single persistent "general note" (scratchpad) lives outside the list. */
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `notes` ADD COLUMN `isScratchpad` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** Per-note renderer for the plain text (text, markdown, html, json, xml). */
    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `notes` ADD COLUMN `format` TEXT NOT NULL DEFAULT 'text'")
        }
    }

    /**
     * Sync identity.
     *
     * Existing notes get a fresh uuid and are flagged for upload, otherwise the
     * first sync would only pull and the notes already on this device would
     * never reach the server. The unique index is created after the backfill,
     * because every row starts as an empty string.
     */
    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `notes` ADD COLUMN `uuid` TEXT NOT NULL DEFAULT ''")
            connection.execSQL("ALTER TABLE `notes` ADD COLUMN `needsSync` INTEGER NOT NULL DEFAULT 0")

            val ids = mutableListOf<Long>()
            connection.prepare("SELECT `id` FROM `notes` WHERE `uuid` = ''").use { statement ->
                while (statement.step()) ids.add(statement.getLong(0))
            }
            ids.forEach { id ->
                connection.execSQL("UPDATE `notes` SET `uuid` = '${java.util.UUID.randomUUID()}' WHERE `id` = $id")
            }
            connection.execSQL("UPDATE `notes` SET `needsSync` = 1 WHERE `isScratchpad` = 0")
            connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_notes_uuid` ON `notes` (`uuid`)")

            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `deleted_notes` " +
                    "(`uuid` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, PRIMARY KEY(`uuid`))"
            )
        }
    }

    fun create(): JotterDatabase {
        Xdg.ensureDirectories()
        return Room.databaseBuilder<JotterDatabase>(name = Xdg.databaseFile.toString())
            .setDriver(BundledSQLiteDriver())
            .addMigrations(
                MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10
            )
            // No destructive fallback on purpose: a missing migration must fail
            // loudly instead of silently dropping every note.
            .addCallback(object : RoomDatabase.Callback() {
                private val logged = java.util.concurrent.atomic.AtomicBoolean(false)

                override fun onOpen(connection: SQLiteConnection) {
                    // WAL commits are only durable across a hard reset/reboot
                    // when each commit is fsynced.
                    connection.execSQL("PRAGMA synchronous = FULL")
                    // The list is read again after every write; keeping more
                    // pages cached avoids going back to disk for them.
                    connection.execSQL("PRAGMA cache_size = -8000")
                    connection.execSQL("PRAGMA temp_store = MEMORY")
                    // The checkpoint opens a second connection: wait for the
                    // writer instead of failing immediately with SQLITE_BUSY.
                    connection.execSQL("PRAGMA busy_timeout = 3000")
                    if (logged.compareAndSet(false, true)) {
                        runCatching {
                            val mode = readPragma(connection, "PRAGMA journal_mode")
                            val sync = readPragma(connection, "PRAGMA synchronous")
                            Diagnostics.log("sqlite opened: journal=$mode synchronous=$sync")
                        }
                    }
                }
            })
            .build()
    }

    private fun readPragma(connection: SQLiteConnection, sql: String): String =
        runCatching {
            connection.prepare(sql).use { statement ->
                if (statement.step()) statement.getText(0) else "?"
            }
        }.getOrDefault("?")

    /**
     * Copies the write-ahead log back into the main database file.
     *
     * SQLite keeps recent commits in `-wal`; until a checkpoint happens they
     * exist nowhere else. Forcing one before the window hides or the process
     * ends is what makes the text survive a reboot.
     */
    suspend fun checkpoint() = withContext(Dispatchers.IO) {
        runCatching {
            BundledSQLiteDriver().open(Xdg.databaseFile.toString()).use { connection ->
                // FULL waits for in-flight readers/writers and copies every
                // committed frame; TRUNCATE additionally empties the WAL, which
                // can fail with SQLITE_BUSY while the app is writing.
                connection.execSQL("PRAGMA wal_checkpoint(FULL)")
            }
        }.onFailure { Diagnostics.log("wal checkpoint failed: ${it.message}") }
        Unit
    }
}
