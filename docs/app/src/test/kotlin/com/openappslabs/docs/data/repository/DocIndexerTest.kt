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

package com.openappslabs.docs.data.repository

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.openappslabs.docs.data.model.DocStatus
import com.openappslabs.docs.data.model.DocText
import com.openappslabs.docs.data.source.DocsDatabase
import com.openappslabs.docs.data.source.sqlLikePattern
import com.openappslabs.docs.data.tags.TagPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the whole scan against a throwaway folder and an in-memory database:
 * walk, diff, write, read back through the same queries the UI uses.
 */
class DocIndexerTest {

    private lateinit var root: Path
    private lateinit var database: DocsDatabase
    private lateinit var indexer: DocIndexer

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("docs-index")
        database = Room.inMemoryDatabaseBuilder<DocsDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        indexer = DocIndexer(root, database.docDao(), database.docTextDao())
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    private fun write(relative: String, content: String = "conteúdo"): Path {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        return file
    }

    private fun docs(pattern: String = "", tag: String? = null) = database.docDao().observeByName(
        status = DocStatus.PRESENT,
        pattern = sqlLikePattern(pattern),
        tag = tag,
        tagPrefix = tag?.let(TagPaths::descendantPattern) ?: "",
        favoriteOnly = 0,
        recentSince = 0,
        folder = null,
        folderPrefix = ""
    )

    @Test
    fun `first scan indexes the tree`() = runBlocking {
        write("relatorio.pdf")
        write("faculdade/notas.docx")

        val outcome = indexer.scanNow()

        assertEquals(2, outcome.added)
        assertEquals(0, outcome.updated)
        assertEquals(0, outcome.missing)

        val all = database.docDao().all()
        assertEquals(listOf("faculdade/notas.docx", "relatorio.pdf"), all.map { it.relPath }.sorted())
        assertEquals(DocStatus.PRESENT, all.first().status)
    }

    @Test
    fun `untouched tree keeps identities and writes nothing`() = runBlocking {
        write("a.pdf")
        indexer.scanNow()
        val before = database.docDao().all().single()

        val outcome = indexer.scanNow()

        assertEquals(0, outcome.added)
        assertEquals(0, outcome.updated)
        assertEquals(0, outcome.missing)
        assertEquals(before.uuid, database.docDao().all().single().uuid)
    }

    @Test
    fun `a rescan keeps the favorite mark`() = runBlocking {
        write("a.pdf")
        indexer.scanNow()
        val before = database.docDao().all().single()
        database.docDao().setFavorite(before.id, true)

        indexer.scanNow()

        assertTrue(database.docDao().all().single().favorite)
    }

    @Test
    fun `renaming a file keeps identity, tags and favorite`() = runBlocking {
        val file = write("relatorio.pdf", "conteudo que nao muda")
        indexer.scanNow()
        val before = database.docDao().all().single()
        val tags = TagRepository(database.tagDao())
        tags.apply(before.id, "pessoal/financas")
        database.docDao().setFavorite(before.id, true)

        Files.move(file, root.resolve("relatorio final.pdf"))
        val outcome = indexer.scanNow()

        assertEquals(1, outcome.moved)
        assertEquals(0, outcome.added)
        assertEquals(0, outcome.missing)
        val after = database.docDao().all().single()
        assertEquals(before.id, after.id)
        assertEquals(before.uuid, after.uuid)
        assertEquals("relatorio final.pdf", after.relPath)
        assertTrue(after.favorite)
        assertEquals(
            listOf("pessoal/financas"),
            database.tagDao().observeAssignments(DocStatus.PRESENT).first().map { it.path }
        )
    }

    @Test
    fun `moving a file to another folder keeps its tags`() = runBlocking {
        val file = write("faculdade/prova.pdf", "x")
        indexer.scanNow()
        val before = database.docDao().all().single()
        TagRepository(database.tagDao()).apply(before.id, "academico")

        Files.createDirectories(root.resolve("arquivo"))
        Files.move(file, root.resolve("arquivo/prova.pdf"))
        val outcome = indexer.scanNow()

        assertEquals(1, outcome.moved)
        val after = database.docDao().all().single()
        assertEquals("arquivo/prova.pdf", after.relPath)
        assertEquals(before.id, after.id)
        assertEquals(1, database.tagDao().observeAssignments(DocStatus.PRESENT).first().size)
    }

    @Test
    fun `edited file updates the same row`() = runBlocking {
        write("a.pdf", "curto")
        indexer.scanNow()
        val before = database.docDao().all().single()

        val edited = write("a.pdf", "conteúdo bem maior")
        val outcome = indexer.scanNow()

        assertEquals(1, outcome.updated)
        assertEquals(0, outcome.added)
        val after = database.docDao().all().single()
        assertEquals(before.id, after.id)
        assertEquals(before.uuid, after.uuid)
        assertEquals(Files.size(edited), after.size)
    }

    @Test
    fun `deleted file stops being listed and comes back when restored`() = runBlocking {
        val file = write("a.pdf")
        indexer.scanNow()

        Files.delete(file)
        val deleted = indexer.scanNow()
        assertEquals(1, deleted.missing)
        assertTrue(docs().first().isEmpty())

        write("a.pdf")
        val restored = indexer.scanNow()
        assertEquals(1, restored.updated)
        assertEquals(1, docs().first().size)
    }

    @Test
    fun `search matches part of the name and ignores case`() = runBlocking {
        write("Relatorio-Final.pdf")
        write("outro.docx")
        indexer.scanNow()

        assertEquals(listOf("Relatorio-Final.pdf"), docs("relatorio").first().map { it.name })
    }

    @Test
    fun `search treats percent and underscore as literal characters`() = runBlocking {
        write("50%_desconto.pdf")
        write("50x-desconto.pdf")
        indexer.scanNow()

        assertEquals(listOf("50%_desconto.pdf"), docs("%_").first().map { it.name })
    }

    @Test
    fun `count follows only present documents`() = runBlocking {
        write("a.pdf")
        write("b.pdf")
        indexer.scanNow()
        Files.delete(root.resolve("b.pdf"))
        indexer.scanNow()

        assertEquals(1, database.docDao().observeCount(DocStatus.PRESENT).first())
    }

    @Test
    fun `unreadable subfolder does not abort the scan`() = runBlocking {
        write("ok.pdf")
        val locked = Files.createDirectories(root.resolve("trancada"))
        Files.writeString(locked.resolve("segredo.pdf"), "x")
        // chmod 000: the walk reports the folder as failed and keeps going.
        val permissions = Files.getPosixFilePermissions(root)
        Files.setPosixFilePermissions(locked, emptySet())
        try {
            val outcome = indexer.scanNow()
            assertEquals(1, outcome.added)
            assertEquals(0, outcome.missing)
        } finally {
            Files.setPosixFilePermissions(locked, permissions)
        }
    }

    private suspend fun readText(docId: Long) = database.docTextDao().get(docId)

    private suspend fun storeText(docId: Long, body: String) = database.docTextDao().put(
        DocText(
            docId = docId,
            text = body,
            source = "pdf",
            charCount = body.length,
            extractedTime = 1
        )
    )

    @Test
    fun `editing a file drops the text read before`() = runBlocking {
        write("a.pdf", "primeira versão")
        indexer.scanNow()
        val doc = database.docDao().all().single()
        storeText(doc.id, "primeira versão")

        write("a.pdf", "segunda versão, bem maior")
        indexer.scanNow()

        assertNull(readText(doc.id), "o texto antigo não pode continuar valendo")
        assertEquals(1, database.docTextDao().pending(10).size)
    }

    @Test
    fun `moving a file keeps the text it already had`() = runBlocking {
        val file = write("a.pdf", "conteúdo estável")
        indexer.scanNow()
        val doc = database.docDao().all().single()
        storeText(doc.id, "conteúdo estável")

        Files.move(file, root.resolve("b.pdf"))
        indexer.scanNow()

        assertEquals(1, database.docDao().all().single().id)
        assertEquals("conteúdo estável", readText(doc.id)?.text)
        assertEquals(0, database.docTextDao().pending(10).size)
    }
}
