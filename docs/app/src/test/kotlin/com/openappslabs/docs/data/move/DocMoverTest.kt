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
import com.openappslabs.docs.data.model.DocTag
import com.openappslabs.docs.data.model.DocText
import com.openappslabs.docs.data.model.Tag
import com.openappslabs.docs.data.source.DocsDatabase
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real files in a throwaway folder, a real database, and the only code in the
 * app that writes to the user's tree. Every refusal is asserted twice: the
 * outcome *and* the disk, because "it said no" is worth little if the file
 * moved anyway.
 */
class DocMoverTest {

    private lateinit var database: DocsDatabase
    private lateinit var root: Path
    private lateinit var mover: DocMover

    @BeforeTest
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<DocsDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        root = createTempDirectory("docs-mover")
        mover = DocMover(root, database.docDao())
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    /** A document with a real file behind it, indexed the way a scan would. */
    private suspend fun addDoc(relPath: String, favorite: Boolean = false): Doc {
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
                modifiedTime = attributes.lastModifiedTime().toMillis(),
                favorite = favorite
            )
        )
        return database.docDao().all().first { it.id == id }
    }

    @Test
    fun `creates the folder and moves the file`() = runBlocking {
        val doc = addDoc("extensao.pdf")

        val outcome = mover.moveTo(doc, "academico/2026")

        assertEquals(MoveOutcome.Moved("extensao.pdf", "academico/2026/extensao.pdf"), outcome)
        assertFalse(Files.exists(root.resolve("extensao.pdf")))
        assertTrue(Files.isDirectory(root.resolve("academico/2026")))
        assertEquals(
            "conteúdo de extensao.pdf",
            Files.readString(root.resolve("academico/2026/extensao.pdf"))
        )
    }

    @Test
    fun `the row keeps its identity, so tags and favorite follow the file`() = runBlocking {
        val doc = addDoc("notas.pdf", favorite = true)
        database.tagDao().insert(Tag(path = "estudos/notas"))
        val tag = database.tagDao().findByPath("estudos/notas")!!
        database.tagDao().assign(DocTag(doc.id, tag.id))
        database.docTextDao().put(DocText(doc.id, "resumo das notas", "pdf", 16, 1))

        mover.moveTo(doc, "estudos")

        val moved = database.docDao().findByPath("estudos/notas.pdf")
        assertNotNull(moved)
        assertEquals(doc.id, moved.id)
        assertEquals(doc.uuid, moved.uuid)
        assertTrue(moved.favorite)
        assertEquals(listOf(tag.id), database.tagDao().tagIdsFor(doc.id))
        assertEquals("resumo das notas", database.docTextDao().get(doc.id)?.text)
        assertNull(database.docDao().findByPath("notas.pdf"))
    }

    @Test
    fun `the size and the date are read again after the move`() = runBlocking {
        val doc = addDoc("relatorio.pdf")

        mover.moveTo(doc, "academico")

        val moved = database.docDao().findByPath("academico/relatorio.pdf")!!
        val onDisk = Files.readAttributes(
            root.resolve("academico/relatorio.pdf"),
            BasicFileAttributes::class.java
        )
        assertEquals(onDisk.size(), moved.size)
        assertEquals(onDisk.lastModifiedTime().toMillis(), moved.modifiedTime)
    }

    @Test
    fun `a name already at the destination is a refusal, never an overwrite`() = runBlocking {
        val doc = addDoc("relatorio.pdf")
        val taken = root.resolve("academico/relatorio.pdf")
        Files.createDirectories(taken.parent)
        Files.writeString(taken, "outro relatório")

        val outcome = mover.moveTo(doc, "academico")

        assertEquals(MoveOutcome.Rejected(MoveIssue.TARGET_TAKEN), outcome)
        assertEquals("outro relatório", Files.readString(taken))
        assertEquals("conteúdo de relatorio.pdf", Files.readString(root.resolve("relatorio.pdf")))
    }

    @Test
    fun `a row already holding the destination path blocks the move`() = runBlocking {
        val doc = addDoc("a.pdf")
        // A stale index entry at the destination, with no file behind it: the
        // unique index would break after the move.
        database.docDao().insert(
            Doc(relPath = "guardada/a.pdf", name = "a.pdf", kind = DocKind.PDF, size = 1, modifiedTime = 1)
        )

        val outcome = mover.moveTo(doc, "guardada")

        assertEquals(MoveOutcome.Rejected(MoveIssue.TARGET_TAKEN), outcome)
        assertTrue(Files.exists(root.resolve("a.pdf")))
        assertFalse(Files.exists(root.resolve("guardada/a.pdf")))
    }

    @Test
    fun `a folder that escapes the library is refused`() = runBlocking {
        val doc = addDoc("a.pdf")

        val outcome = mover.moveTo(doc, "../docs-mover-fora")

        assertEquals(MoveOutcome.Rejected(MoveIssue.INVALID_FOLDER), outcome)
        assertTrue(Files.exists(root.resolve("a.pdf")))
        assertFalse(Files.exists(root.resolveSibling("docs-mover-fora")))
    }

    @Test
    fun `a file that is not on disk is not moved`() = runBlocking {
        val doc = addDoc("sumiu.pdf")
        Files.delete(root.resolve("sumiu.pdf"))

        val outcome = mover.moveTo(doc, "academico")

        assertEquals(MoveOutcome.Rejected(MoveIssue.SOURCE_MISSING), outcome)
        assertFalse(Files.exists(root.resolve("academico/sumiu.pdf")))
    }

    @Test
    fun `moving to the folder it is already in changes nothing`() = runBlocking {
        val doc = addDoc("academico/b.pdf")

        val outcome = mover.moveTo(doc, "academico")

        assertEquals(MoveOutcome.Rejected(MoveIssue.ALREADY_THERE), outcome)
        assertTrue(Files.exists(root.resolve("academico/b.pdf")))
    }

    @Test
    fun `moving back to the root works`() = runBlocking {
        val doc = addDoc("academico/2026/tcc.pdf")

        val outcome = mover.moveTo(doc, FolderPath.ROOT)

        assertEquals(MoveOutcome.Moved("academico/2026/tcc.pdf", "tcc.pdf"), outcome)
        assertTrue(Files.exists(root.resolve("tcc.pdf")))
        assertFalse(Files.exists(root.resolve("academico/2026/tcc.pdf")))
    }

    @Test
    fun `a folder that already exists is reused`() = runBlocking {
        val doc = addDoc("extensao.pdf")
        Files.createDirectories(root.resolve("academico"))
        Files.writeString(root.resolve("academico/outro.pdf"), "já estava aqui")

        val outcome = mover.moveTo(doc, "academico")

        assertEquals(MoveOutcome.Moved("extensao.pdf", "academico/extensao.pdf"), outcome)
        assertEquals("já estava aqui", Files.readString(root.resolve("academico/outro.pdf")))
        assertEquals(
            "conteúdo de extensao.pdf",
            Files.readString(root.resolve("academico/extensao.pdf"))
        )
    }

    @Test
    fun `a file name with characters a folder may not have is still moved`() = runBlocking {
        val doc = addDoc("nota~final #1.pdf")

        val outcome = mover.moveTo(doc, "academico")

        assertEquals(MoveOutcome.Moved("nota~final #1.pdf", "academico/nota~final #1.pdf"), outcome)
        assertTrue(Files.exists(root.resolve("academico/nota~final #1.pdf")))
    }

    @Test
    fun `a destination that cannot be created leaves the file where it was`() = runBlocking {
        val doc = addDoc("a.pdf")
        // A regular file where the folder should be: createDirectories refuses.
        Files.writeString(root.resolve("bloqueio"), "arquivo, não pasta")

        val outcome = mover.moveTo(doc, "bloqueio/sub")

        assertEquals(MoveOutcome.Rejected(MoveIssue.FAILED), outcome)
        assertEquals("conteúdo de a.pdf", Files.readString(root.resolve("a.pdf")))
        assertEquals("arquivo, não pasta", Files.readString(root.resolve("bloqueio")))
    }

    @Test
    fun `from the inbox a new folder is created on the way`() = runBlocking {
        val doc = addDoc("init/extensao.pdf")

        val outcome = mover.moveTo(doc, "academico/2026")

        assertEquals(
            MoveOutcome.Moved("init/extensao.pdf", "academico/2026/extensao.pdf"),
            outcome
        )
        assertTrue(Files.exists(root.resolve("academico/2026/extensao.pdf")))
        assertFalse(Files.exists(root.resolve("init/extensao.pdf")))
        assertEquals("academico/2026/extensao.pdf", database.docDao().findById(doc.id)!!.relPath)
    }

    @Test
    fun `from a subfolder of the inbox the rule is the same`() = runBlocking {
        val doc = addDoc("init/2026/matricula.pdf")

        val outcome = mover.moveTo(doc, "faculdade")

        assertEquals(
            MoveOutcome.Moved("init/2026/matricula.pdf", "faculdade/matricula.pdf"),
            outcome
        )
        assertTrue(Files.exists(root.resolve("faculdade/matricula.pdf")))
    }

    @Test
    fun `from the inbox a folder that already exists is a legal destination`() = runBlocking {
        val doc = addDoc("init/extensao.pdf")
        Files.createDirectories(root.resolve("academico"))
        Files.writeString(root.resolve("academico/outro.pdf"), "já estava aqui")

        val outcome = mover.moveTo(doc, "academico")

        assertEquals(MoveOutcome.Moved("init/extensao.pdf", "academico/extensao.pdf"), outcome)
        assertTrue(Files.exists(root.resolve("academico/extensao.pdf")))
    }

    @Test
    fun `from the inbox the top of the library is always a destination`() = runBlocking {
        val doc = addDoc("init/extensao.pdf")

        val outcome = mover.moveTo(doc, FolderPath.ROOT)

        // The root exists, so it is not a creation: it is the way out of init.
        assertEquals(MoveOutcome.Moved("init/extensao.pdf", "extensao.pdf"), outcome)
        assertTrue(Files.exists(root.resolve("extensao.pdf")))
    }

    @Test
    fun `the inbox rule does not reach a document outside it`() = runBlocking {
        val doc = addDoc("extensao.pdf")

        val outcome = mover.moveTo(doc, "academico/2026")

        // Outside the inbox, creating is still how the library grows.
        assertEquals(MoveOutcome.Moved("extensao.pdf", "academico/2026/extensao.pdf"), outcome)
        assertTrue(Files.isDirectory(root.resolve("academico/2026")))
    }

    @Test
    fun `a folder named like the inbox prefix is not treated as the inbox`() = runBlocking {
        val doc = addDoc("inicial/extensao.pdf")

        val outcome = mover.moveTo(doc, "academico")

        assertEquals(MoveOutcome.Moved("inicial/extensao.pdf", "academico/extensao.pdf"), outcome)
    }
}
