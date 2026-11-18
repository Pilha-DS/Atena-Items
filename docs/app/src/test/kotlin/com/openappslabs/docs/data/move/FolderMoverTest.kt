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

package com.openappslabs.docs.data.move

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.openappslabs.docs.data.model.Doc
import com.openappslabs.docs.data.model.DocKind
import com.openappslabs.docs.data.model.FolderDescription
import com.openappslabs.docs.data.model.FolderTag
import com.openappslabs.docs.data.model.Tag
import com.openappslabs.docs.data.source.DocsDatabase
import com.openappslabs.docs.data.source.sqlLikeUnder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real folders on disk and a real index: every refusal is checked against both
 * the outcome and the tree that must not have moved.
 */
class FolderMoverTest {

    private lateinit var database: DocsDatabase
    private lateinit var root: Path
    private lateinit var mover: FolderMover

    @BeforeTest
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<DocsDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        root = createTempDirectory("docs-folder-mover")
        mover = FolderMover(
            root = root,
            dao = database.docDao(),
            descriptions = database.folderDescriptionDao(),
            folderTags = database.folderTagDao()
        )
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    private suspend fun addDoc(relPath: String): Doc {
        val file = root.resolve(relPath)
        Files.createDirectories(file.parent)
        Files.writeString(file, "conteúdo de $relPath")
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
        val id = database.docDao().insert(
            Doc(
                relPath = relPath,
                name = relPath.substringAfterLast('/'),
                kind = DocKind.fromFileName(relPath),
                size = attributes.size(),
                modifiedTime = attributes.lastModifiedTime().toMillis()
            )
        )
        return database.docDao().all().first { it.id == id }
    }

    private fun mkdir(rel: String) {
        Files.createDirectories(root.resolve(rel))
    }

    @Test
    fun `moves a folder with children and remaps document paths`() = runBlocking {
        mkdir("a/b")
        mkdir("c")
        addDoc("a/b/x.pdf")
        addDoc("a/b/d/y.pdf")

        val outcome = mover.moveTo("a/b", "c")

        assertEquals(FolderMoveOutcome.Moved("a/b", "c/b"), outcome)
        assertFalse(Files.exists(root.resolve("a/b")))
        assertTrue(Files.isDirectory(root.resolve("c/b/d")))
        assertEquals("conteúdo de a/b/x.pdf", Files.readString(root.resolve("c/b/x.pdf")))
        assertEquals("c/b/x.pdf", database.docDao().findByPath("c/b/x.pdf")?.relPath)
        assertEquals("c/b/d/y.pdf", database.docDao().findByPath("c/b/d/y.pdf")?.relPath)
        assertTrue(database.docDao().underPrefix(sqlLikeUnder("a/b")).isEmpty())
    }

    @Test
    fun `moves to the library root`() = runBlocking {
        mkdir("pai/filho")
        addDoc("pai/filho/nota.pdf")

        val outcome = mover.moveTo("pai/filho", "")

        assertEquals(FolderMoveOutcome.Moved("pai/filho", "filho"), outcome)
        assertTrue(Files.isDirectory(root.resolve("filho")))
        assertEquals("filho/nota.pdf", database.docDao().findByPath("filho/nota.pdf")?.relPath)
    }

    @Test
    fun `remaps folder descriptions and inherited tags`() = runBlocking {
        mkdir("origem/sub")
        mkdir("destino")
        database.folderDescriptionDao().put(
            FolderDescription(path = "origem", description = "pai", updatedTime = 1L)
        )
        database.folderDescriptionDao().put(
            FolderDescription(path = "origem/sub", description = "filho", updatedTime = 2L)
        )
        val tagId = database.tagDao().insert(Tag(path = "fiscal", name = "fiscal"))
        database.folderTagDao().insert(FolderTag(folderPath = "origem", tagId = tagId))
        database.folderTagDao().insert(FolderTag(folderPath = "origem/sub", tagId = tagId))

        val outcome = mover.moveTo("origem", "destino")

        assertEquals(FolderMoveOutcome.Moved("origem", "destino/origem"), outcome)
        assertEquals(
            "pai",
            database.folderDescriptionDao().find("destino/origem")?.description
        )
        assertEquals(
            "filho",
            database.folderDescriptionDao().find("destino/origem/sub")?.description
        )
        assertEquals(null, database.folderDescriptionDao().find("origem"))
        val tags = database.folderTagDao().allRows().map { it.folderPath }.sorted()
        assertEquals(listOf("destino/origem", "destino/origem/sub"), tags)
    }

    @Test
    fun `refuses the inbox as source and as parent`() = runBlocking {
        mkdir("init")
        mkdir("init/dentro")
        mkdir("outra")

        assertEquals(
            FolderMoveOutcome.Rejected(FolderMoveIssue.INBOX),
            mover.moveTo("init", "outra")
        )
        assertTrue(Files.isDirectory(root.resolve("init")))

        assertEquals(
            FolderMoveOutcome.Rejected(FolderMoveIssue.INBOX),
            mover.moveTo("outra", "init")
        )
        assertTrue(Files.isDirectory(root.resolve("outra")))
    }

    @Test
    fun `refuses moving into itself or a descendant`() = runBlocking {
        mkdir("a/b/c")

        assertEquals(
            FolderMoveOutcome.Rejected(FolderMoveIssue.INTO_SELF),
            mover.moveTo("a", "a/b")
        )
        // Same parent would keep the path identical (`a/b` → under `a` stays `a/b`).
        assertEquals(
            FolderMoveOutcome.Rejected(FolderMoveIssue.INTO_SELF),
            mover.moveTo("a/b", "a")
        )
        assertTrue(Files.isDirectory(root.resolve("a/b/c")))
    }

    @Test
    fun `refuses a name collision`() = runBlocking {
        mkdir("a/b")
        mkdir("c/b")

        assertEquals(
            FolderMoveOutcome.Rejected(FolderMoveIssue.TARGET_TAKEN),
            mover.moveTo("a/b", "c")
        )
        assertTrue(Files.isDirectory(root.resolve("a/b")))
    }

    @Test
    fun `refuses a move that would exceed max depth`() = runBlocking {
        mkdir("a/b/c/d/e")
        mkdir("x/y/z")
        // Moving `e` under `x/y/z` → `x/y/z/e` is depth 4, fine; make a deeper tree.
        mkdir("deep/1/2/3/4/5")
        mkdir("side")

        assertEquals(
            FolderMoveOutcome.Rejected(FolderMoveIssue.TOO_DEEP),
            mover.moveTo("deep/1", "side")
        )
        // `deep/1` has relative depth 4 below it (2/3/4/5); side/1 would be depth 1+4=5 OK.
        // Make parent deep so 2 + children exceed.
        mkdir("p/q/r/s")
        assertEquals(
            FolderMoveOutcome.Rejected(FolderMoveIssue.TOO_DEEP),
            mover.moveTo("deep/1", "p/q/r/s")
        )
        assertTrue(Files.isDirectory(root.resolve("deep/1/2/3/4/5")))
    }

    @Test
    fun `refuses the library root as source`() = runBlocking {
        assertEquals(
            FolderMoveOutcome.Rejected(FolderMoveIssue.ROOT),
            mover.moveTo("", "a")
        )
    }

    @Test
    fun `refuses a missing folder`() = runBlocking {
        mkdir("destino")
        assertEquals(
            FolderMoveOutcome.Rejected(FolderMoveIssue.NOT_FOUND),
            mover.moveTo("fantasma", "destino")
        )
    }
}
