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

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * 1 -> 2: tags arrived.
 *
 * Written by hand because the app is already installed on machines with a v1
 * index. The DDL has to match exactly what Room expects from the entities,
 * otherwise the schema check fails on the next open.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `tags` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`path` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`createdTime` INTEGER NOT NULL)"
        )
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_path` ON `tags` (`path`)")

        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `doc_tags` (" +
                "`docId` INTEGER NOT NULL, " +
                "`tagId` INTEGER NOT NULL, " +
                "PRIMARY KEY(`docId`, `tagId`))"
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_doc_tags_tagId` ON `doc_tags` (`tagId`)")
    }
}

/**
 * 2 -> 3: favorites and the folder view arrived.
 *
 * Adding the column is enough: the folder view is derived from `relPath`
 * at read time, so no table follows it.
 */
internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `documents` ADD COLUMN `favorite` INTEGER NOT NULL DEFAULT 0"
        )
    }
}

/**
 * 3 -> 4: the text read out of the files (search inside the content).
 *
 * A separate table keeps the list query cheap. The first scan after the update
 * fills it document by document; nothing else changes, so the tags and the
 * favorites are untouched.
 */
internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `doc_text` (" +
                "`docId` INTEGER NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`charCount` INTEGER NOT NULL, " +
                "`extractedTime` INTEGER NOT NULL, " +
                "PRIMARY KEY(`docId`))"
        )
    }
}

/**
 * 4 -> 5: descriptions arrived, for the AI to know what a folder or a tag is for.
 *
 * The tag description is a column on `tags` (it dies with the tag); the folder
 * one needs a table of its own, because a folder has no row in the index — the
 * folder is on the disk, the sentence about it is here.
 *
 * Both start empty: an existing index keeps its documents, tags and favorites,
 * and nothing pretends to be described.
 */
internal val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `tags` ADD COLUMN `description` TEXT NOT NULL DEFAULT ''")
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `folder_descriptions` (" +
                "`path` TEXT NOT NULL, " +
                "`description` TEXT NOT NULL, " +
                "`updatedTime` INTEGER NOT NULL, " +
                "PRIMARY KEY(`path`))"
        )
    }
}

/**
 * 5 -> 6: folders can declare tags that every document under them inherits.
 *
 * A separate join table, not rows in `doc_tags`: inheritance is a property of
 * the folder and must drop when the file leaves, without rewriting every
 * document. Existing indexes keep their documents, tags and descriptions.
 */
internal val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `folder_tags` (" +
                "`folderPath` TEXT NOT NULL, " +
                "`tagId` INTEGER NOT NULL, " +
                "PRIMARY KEY(`folderPath`, `tagId`))"
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_folder_tags_tagId` ON `folder_tags` (`tagId`)"
        )
    }
}

/**
 * 6 -> 7: search aliases of a tag (`nota-fiscal` → `nfe`).
 *
 * A separate table keeps the tag path clean: aliases are only nicknames for
 * the universal search, never a second hierarchy. Empty on every existing
 * index — nothing to invent.
 */
internal val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `tag_aliases` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`tagId` INTEGER NOT NULL, " +
                "`alias` TEXT NOT NULL)"
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tag_aliases_tagId_alias` " +
                "ON `tag_aliases` (`tagId`, `alias`)"
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tag_aliases_alias` ON `tag_aliases` (`alias`)"
        )
    }
}

/**
 * 7 -> 8: open-count frecency for the fuzzy search.
 *
 * Both columns start at 0: existing documents have never been "visited" from
 * the window's point of view, and the scan must not invent a history.
 */
internal val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `documents` ADD COLUMN `visitCount` INTEGER NOT NULL DEFAULT 0"
        )
        connection.execSQL(
            "ALTER TABLE `documents` ADD COLUMN `lastVisitedAt` INTEGER NOT NULL DEFAULT 0"
        )
    }
}
