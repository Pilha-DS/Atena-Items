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

package com.openappslabs.jotter.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.openappslabs.jotter.data.repository.CategoryRepository
import com.openappslabs.jotter.data.repository.CategoryRepositoryImpl
import com.openappslabs.jotter.data.repository.NotesRepository
import com.openappslabs.jotter.data.repository.NotesRepositoryImpl
import com.openappslabs.jotter.data.source.CategoryDao
import com.openappslabs.jotter.data.source.JotterDatabase
import com.openappslabs.jotter.data.source.NoteDao
import com.openappslabs.jotter.data.source.SyncDao
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DatabaseModule {

    @Binds
    @Singleton
    abstract fun bindNotesRepository(
        notesRepositoryImpl: NotesRepositoryImpl
    ): NotesRepository

    @Binds
    @Singleton
    abstract fun bindCategoryRepository(
        categoryRepositoryImpl: CategoryRepositoryImpl
    ): CategoryRepository


    companion object {
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_isArchived_isTrashed` ON `notes` (`isArchived`, `isTrashed`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_category` ON `notes` (`category`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_isPinned_updatedTime` ON `notes` (`isPinned`, `updatedTime`)")
            }
        }

        /** Per-note "open in edit mode" override; NULL keeps the global default. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `openInEditMode` INTEGER DEFAULT NULL")
            }
        }

        /** Per-note encryption: ciphertext + salt alongside a `isEncrypted` flag. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `isEncrypted` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `encryptionSalt` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `encryptedContent` TEXT DEFAULT NULL")
            }
        }

        /** Renderer used when a note is read; the content stays plain text. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `format` TEXT NOT NULL DEFAULT 'text'")
            }
        }

        /**
         * Sync identity.
         *
         * Existing notes get a fresh uuid and are flagged for upload, otherwise
         * the first sync would only pull and the notes already on the phone
         * would never reach the server. The unique index is created after the
         * backfill, because every row starts as an empty string.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `uuid` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `needsSync` INTEGER NOT NULL DEFAULT 0")

                val ids = mutableListOf<Long>()
                db.query("SELECT `id` FROM `notes` WHERE `uuid` = ''").use { cursor ->
                    while (cursor.moveToNext()) ids.add(cursor.getLong(0))
                }
                ids.forEach { id ->
                    db.execSQL("UPDATE `notes` SET `uuid` = '${UUID.randomUUID()}' WHERE `id` = $id")
                }
                db.execSQL("UPDATE `notes` SET `needsSync` = 1")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_notes_uuid` ON `notes` (`uuid`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `deleted_notes` " +
                        "(`uuid` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, PRIMARY KEY(`uuid`))"
                )
            }
        }

        @Provides
        @Singleton
        fun provideDatabase(@ApplicationContext context: Context): JotterDatabase {
            return Room.databaseBuilder(
                context,
                JotterDatabase::class.java,
                "jotter_db"
            )
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        val prefs = context.getSharedPreferences("jotter_internal", Context.MODE_PRIVATE)
                        val count = prefs.getInt("archive_migration_runs", 0)
                        if (count < 3) {
                            db.execSQL("UPDATE notes SET isArchived = 0 WHERE isArchived = 1")
                            prefs.edit().putInt("archive_migration_runs", count + 1).apply()
                        }
                    }
                })
                .build()
        }

        @Provides
        fun provideNoteDao(database: JotterDatabase): NoteDao {
            return database.noteDao()
        }

        @Provides
        fun provideCategoryDao(database: JotterDatabase): CategoryDao {
            return database.categoryDao()
        }

        @Provides
        fun provideSyncDao(database: JotterDatabase): SyncDao {
            return database.syncDao()
        }
    }
}