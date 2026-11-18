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
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.openappslabs.docs.data.model.DocStatus
import com.openappslabs.docs.data.model.DocTag
import com.openappslabs.docs.data.model.DocText
import com.openappslabs.docs.data.model.FolderDescription
import com.openappslabs.docs.data.model.FolderTag
import com.openappslabs.docs.data.model.Tag
import com.openappslabs.docs.data.model.TagAlias
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An existing index must survive the update.
 *
 * The migration SQL is written by hand, so this test is the only thing keeping
 * it in sync with the entities: if the DDL drifts, Room refuses to open the
 * database and the test fails right here.
 */
class MigrationTest {

    private val documentsTable = listOf(
        "CREATE TABLE IF NOT EXISTS `documents` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`uuid` TEXT NOT NULL, " +
            "`relPath` TEXT NOT NULL, " +
            "`name` TEXT NOT NULL, " +
            "`kind` TEXT NOT NULL, " +
            "`size` INTEGER NOT NULL, " +
            "`modifiedTime` INTEGER NOT NULL, " +
            "`indexedTime` INTEGER NOT NULL, " +
            "`status` TEXT NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_documents_relPath` ON `documents` (`relPath`)",
        "CREATE INDEX IF NOT EXISTS `index_documents_name` ON `documents` (`name`)",
        "CREATE INDEX IF NOT EXISTS `index_documents_kind` ON `documents` (`kind`)",
        "CREATE INDEX IF NOT EXISTS `index_documents_modifiedTime` ON `documents` (`modifiedTime`)",
        "CREATE INDEX IF NOT EXISTS `index_documents_status` ON `documents` (`status`)"
    )

    private val tagsTables = listOf(
        "CREATE TABLE IF NOT EXISTS `tags` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`path` TEXT NOT NULL, " +
            "`name` TEXT NOT NULL, " +
            "`createdTime` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_path` ON `tags` (`path`)",
        "CREATE TABLE IF NOT EXISTS `doc_tags` (" +
            "`docId` INTEGER NOT NULL, " +
            "`tagId` INTEGER NOT NULL, " +
            "PRIMARY KEY(`docId`, `tagId`))",
        "CREATE INDEX IF NOT EXISTS `index_doc_tags_tagId` ON `doc_tags` (`tagId`)"
    )

    private fun seedDatabase(version: Int, vararg ddl: String): Path {
        val file = createTempDirectory("docs-migration").resolve("docs.db")
        BundledSQLiteDriver().open(file.toString()).use { connection ->
            ddl.forEach(connection::execSQL)
            connection.execSQL(
                "INSERT INTO documents (uuid, relPath, name, kind, size, modifiedTime, indexedTime, status) " +
                    "VALUES ('u1', 'a.pdf', 'a.pdf', 'PDF', 10, 100, 100, 'PRESENT')"
            )
            connection.execSQL("PRAGMA user_version = $version")
        }
        return file
    }

    private fun open(file: Path) = Room.databaseBuilder<DocsDatabase>(file.toString())
        .setDriver(BundledSQLiteDriver())
        .addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8
        )
        .build()

    private val descriptionTables = listOf(
        "ALTER TABLE `tags` ADD COLUMN `description` TEXT NOT NULL DEFAULT ''",
        "CREATE TABLE IF NOT EXISTS `folder_descriptions` (" +
            "`path` TEXT NOT NULL, `description` TEXT NOT NULL, `updatedTime` INTEGER NOT NULL, " +
            "PRIMARY KEY(`path`))"
    )

    @Test
    fun `a version 1 database becomes current and keeps the index`() = runBlocking {
        val file = seedDatabase(1, *documentsTable.toTypedArray())
        val database = open(file)

        try {
            val doc = database.docDao().all().single()
            assertEquals("a.pdf", doc.relPath)
            assertFalse(doc.favorite)
            database.tagDao().insert(Tag(path = "pessoal"))
            assertEquals(listOf("pessoal"), database.tagDao().all().map { it.path })
            database.docDao().setFavorite(doc.id, true)
            assertTrue(database.docDao().all().single().favorite)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a version 2 database gains the favorite column with its tags intact`() = runBlocking {
        val file = seedDatabase(2, *documentsTable.toTypedArray(), *tagsTables.toTypedArray())
        val database = open(file)

        try {
            assertEquals(1, database.docDao().all().size)
            assertFalse(database.docDao().all().single().favorite)
            val doc = database.docDao().all().single()
            database.docDao().setFavorite(doc.id, true)
            assertTrue(database.docDao().all().single().favorite)
            assertEquals(1, database.docDao().observeFavoriteCount(DocStatus.PRESENT).first())
        } finally {
            database.close()
        }
    }

    @Test
    fun `a version 3 database gains the text table and keeps the tags and the favorite`() =
        runBlocking {
            val file = seedDatabase(
                3,
                *documentsTable.toTypedArray(),
                "ALTER TABLE `documents` ADD COLUMN `favorite` INTEGER NOT NULL DEFAULT 0",
                *tagsTables.toTypedArray()
            )
            val database = open(file)

            try {
                val doc = database.docDao().all().single()
                database.docDao().setFavorite(doc.id, true)
                database.tagDao().insert(Tag(path = "academico/2026"))
                val tagId = database.tagDao().all().single().id
                database.tagDao().assign(DocTag(doc.id, tagId))

                // The new table is usable right away and does not need a scan.
                assertEquals(0, database.docTextDao().count())
                database.docTextDao().put(
                    DocText(
                        docId = doc.id,
                        text = "conteúdo antigo do pdf",
                        source = "pdf",
                        charCount = 18,
                        extractedTime = 1
                    )
                )
                assertEquals(1, database.docTextDao().observeWithText().first())
                assertEquals(listOf("academico/2026"), database.tagDao().all().map { it.path })
                assertTrue(database.docDao().all().single().favorite)
            } finally {
                database.close()
            }
        }

    @Test
    fun `a version 4 database gains the tag description and the folder table`() = runBlocking {
        val file = seedDatabase(
            4,
            *documentsTable.toTypedArray(),
            "ALTER TABLE `documents` ADD COLUMN `favorite` INTEGER NOT NULL DEFAULT 0",
            *tagsTables.toTypedArray(),
            "CREATE TABLE IF NOT EXISTS `doc_text` (" +
                "`docId` INTEGER NOT NULL, `text` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                "`charCount` INTEGER NOT NULL, `extractedTime` INTEGER NOT NULL, " +
                "PRIMARY KEY(`docId`))"
        )
        val database = open(file)

        try {
            // The tag that was already there survives with an empty description.
            val doc = database.docDao().all().single()
            database.tagDao().insert(Tag(path = "academico"))
            val tag = database.tagDao().all().single()
            assertEquals("", tag.description)
            // Tag unchanged on the way, still assigned to nothing, still usable.
            database.tagDao().setDescription("academico", "matérias da graduação")
            assertEquals("matérias da graduação", database.tagDao().findByPath("academico")?.description)
            assertEquals(tag.id, database.tagDao().findByPath("academico")?.id)

            // The new table is usable right away and does not need a rescan.
            assertEquals(null, database.folderDescriptionDao().find("faculdade/2026"))
            database.folderDescriptionDao().put(
                FolderDescription(path = "faculdade/2026", description = "provas do semestre")
            )
            assertEquals(
                "provas do semestre",
                database.folderDescriptionDao().find("faculdade/2026")?.description
            )
            assertEquals(1, database.folderDescriptionDao().all().size)

            // Nothing else was touched.
            assertEquals(doc.relPath, database.docDao().all().single().relPath)
            assertFalse(database.docDao().all().single().favorite)
            assertEquals(1, database.docDao().all().size)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a version 5 database gains folder_tags and keeps descriptions`() = runBlocking {
        val file = seedDatabase(
            5,
            *documentsTable.toTypedArray(),
            "ALTER TABLE `documents` ADD COLUMN `favorite` INTEGER NOT NULL DEFAULT 0",
            *tagsTables.toTypedArray(),
            "CREATE TABLE IF NOT EXISTS `doc_text` (" +
                "`docId` INTEGER NOT NULL, `text` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                "`charCount` INTEGER NOT NULL, `extractedTime` INTEGER NOT NULL, " +
                "PRIMARY KEY(`docId`))",
            *descriptionTables.toTypedArray()
        )
        BundledSQLiteDriver().open(file.toString()).use { connection ->
            connection.execSQL(
                "INSERT INTO folder_descriptions (path, description, updatedTime) " +
                    "VALUES ('notas-fiscais', 'notas da empresa', 1)"
            )
            connection.execSQL(
                "INSERT INTO tags (path, name, createdTime, description) " +
                    "VALUES ('nota-fiscal', 'nota-fiscal', 1, '')"
            )
        }
        val database = open(file)

        try {
            assertEquals(
                "notas da empresa",
                database.folderDescriptionDao().find("notas-fiscais")?.description
            )
            val tagId = database.tagDao().findByPath("nota-fiscal")!!.id
            database.folderTagDao().insert(FolderTag(folderPath = "notas-fiscais", tagId = tagId))
            val rows = database.folderTagDao().allRows()
            assertEquals(1, rows.size)
            assertEquals("notas-fiscais", rows.single().folderPath)
            assertEquals("nota-fiscal", rows.single().tagPath)
            assertEquals(1, database.docDao().all().size)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a version 6 database gains tag_aliases and keeps the tags`() = runBlocking {
        val file = seedDatabase(
            6,
            *documentsTable.toTypedArray(),
            "ALTER TABLE `documents` ADD COLUMN `favorite` INTEGER NOT NULL DEFAULT 0",
            *tagsTables.toTypedArray(),
            "CREATE TABLE IF NOT EXISTS `doc_text` (" +
                "`docId` INTEGER NOT NULL, `text` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                "`charCount` INTEGER NOT NULL, `extractedTime` INTEGER NOT NULL, " +
                "PRIMARY KEY(`docId`))",
            *descriptionTables.toTypedArray(),
            "CREATE TABLE IF NOT EXISTS `folder_tags` (" +
                "`folderPath` TEXT NOT NULL, `tagId` INTEGER NOT NULL, " +
                "PRIMARY KEY(`folderPath`, `tagId`))",
            "CREATE INDEX IF NOT EXISTS `index_folder_tags_tagId` ON `folder_tags` (`tagId`)"
        )
        BundledSQLiteDriver().open(file.toString()).use { connection ->
            connection.execSQL(
                "INSERT INTO tags (path, name, createdTime, description) " +
                    "VALUES ('nota-fiscal', 'nota-fiscal', 1, '')"
            )
        }
        val database = open(file)

        try {
            val tagId = database.tagDao().findByPath("nota-fiscal")!!.id
            database.tagAliasDao().insert(TagAlias(tagId = tagId, alias = "nfe"))
            assertEquals(listOf("nfe"), database.tagAliasDao().aliasesOf("nota-fiscal"))
            assertEquals(1, database.docDao().all().size)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a version 7 database gains visit columns with zero habit`() = runBlocking {
        val file = seedDatabase(
            7,
            *documentsTable.toTypedArray(),
            "ALTER TABLE `documents` ADD COLUMN `favorite` INTEGER NOT NULL DEFAULT 0",
            *tagsTables.toTypedArray(),
            "CREATE TABLE IF NOT EXISTS `doc_text` (" +
                "`docId` INTEGER NOT NULL, `text` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                "`charCount` INTEGER NOT NULL, `extractedTime` INTEGER NOT NULL, " +
                "PRIMARY KEY(`docId`))",
            *descriptionTables.toTypedArray(),
            "CREATE TABLE IF NOT EXISTS `folder_tags` (" +
                "`folderPath` TEXT NOT NULL, `tagId` INTEGER NOT NULL, " +
                "PRIMARY KEY(`folderPath`, `tagId`))",
            "CREATE INDEX IF NOT EXISTS `index_folder_tags_tagId` ON `folder_tags` (`tagId`)",
            "CREATE TABLE IF NOT EXISTS `tag_aliases` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`tagId` INTEGER NOT NULL, `alias` TEXT NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tag_aliases_tagId_alias` " +
                "ON `tag_aliases` (`tagId`, `alias`)",
            "CREATE INDEX IF NOT EXISTS `index_tag_aliases_alias` ON `tag_aliases` (`alias`)"
        )
        val database = open(file)

        try {
            val doc = database.docDao().all().single()
            assertEquals(0, doc.visitCount)
            assertEquals(0L, doc.lastVisitedAt)
            database.docDao().recordVisit(doc.id, 1_700_000_000_000L)
            val visited = database.docDao().all().single()
            assertEquals(1, visited.visitCount)
            assertEquals(1_700_000_000_000L, visited.lastVisitedAt)
        } finally {
            database.close()
        }
    }
}
