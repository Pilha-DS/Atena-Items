/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Atena Docs
 *
 * Atena Docs is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Atena Docs is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Atena Docs.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.docs.data.source

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.openappslabs.docs.platform.Xdg
import kotlinx.coroutines.Dispatchers

/**
 * Builds the Room database on a plain JVM.
 *
 * The bundled driver is what makes Room run outside Android; it ships the
 * native SQLite, so the machine does not need the system library and the
 * behaviour is the same on every distro.
 */
object DatabaseFactory {

    fun create(): DocsDatabase {
        Xdg.ensureDirectories()
        return Room.databaseBuilder<DocsDatabase>(name = Xdg.databaseFile.toString())
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            // No destructive fallback: a missing migration must fail loudly.
            // Losing the index is cheap (the scan rebuilds it), but silently
            // dropping the tags is not — they exist nowhere else.
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8
            )
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(connection: SQLiteConnection) {
                    // The index is reconstructible, so NORMAL is the right
                    // trade: one fsync per checkpoint instead of per commit.
                    connection.execSQL("PRAGMA synchronous = NORMAL")
                    connection.execSQL("PRAGMA temp_store = MEMORY")
                    // The scan writes while the UI reads the list.
                    connection.execSQL("PRAGMA busy_timeout = 3000")
                }
            })
            .build()
    }
}
