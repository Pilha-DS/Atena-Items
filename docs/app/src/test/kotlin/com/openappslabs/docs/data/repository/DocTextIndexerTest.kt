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
import com.openappslabs.docs.data.model.Doc
import com.openappslabs.docs.data.model.DocKind
import com.openappslabs.docs.data.model.DocStatus
import com.openappslabs.docs.data.source.DocsDatabase
import com.openappslabs.docs.data.text.Harvest
import com.openappslabs.docs.data.text.TextHarvester
import com.openappslabs.docs.data.text.TextSource
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocTextIndexerTest {

    private lateinit var root: Path
    private lateinit var database: DocsDatabase
    private var lastFile: Path? = null

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("docs-text")
        database = Room.inMemoryDatabaseBuilder<DocsDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    private fun addDoc(relative: String, kind: DocKind = DocKind.TEXT): Doc = runBlocking {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, "conteúdo")
        val doc = Doc(
            relPath = relative,
            name = relative.substringAfterLast('/'),
            kind = kind,
            size = 10,
            modifiedTime = 100,
            indexedTime = 100
        )
        val id = database.docDao().insert(doc)
        doc.copy(id = id)
    }

    private fun indexer(harvester: TextHarvester) =
        DocTextIndexer(root, database.docTextDao(), harvester)

    @Test
    fun `pending documents are read and stored in the text table`() = runBlocking {
        val doc = addDoc("notas/reuniao.txt")
        val harvester = TextHarvester { file, _ ->
            lastFile = file
            Harvest("pauta da reunião", TextSource.TEXT)
        }

        val outcome = indexer(harvester).indexPending()

        assertEquals(1, outcome.read)
        assertEquals(1, outcome.withText)
        assertEquals(0, outcome.failed)
        assertEquals(root.resolve("notas/reuniao.txt"), lastFile)
        val row = assertNotNull(database.docTextDao().get(doc.id))
        assertEquals("pauta da reunião", row.text)
        assertEquals("text", row.source)
        assertEquals(16, row.charCount)
        assertTrue(row.hasText)
    }

    @Test
    fun `a document already read is not read again`() = runBlocking {
        addDoc("a.txt")
        var calls = 0
        val harvester = TextHarvester { _, _ ->
            calls += 1
            Harvest("texto", TextSource.TEXT)
        }

        indexer(harvester).indexPending()
        val second = indexer(harvester).indexPending()

        assertEquals(1, calls)
        assertEquals(0, second.read)
    }

    @Test
    fun `an image with nothing inside is stored as empty so it is not retried`() = runBlocking {
        val doc = addDoc("scan.png", DocKind.IMAGE)
        var calls = 0
        val harvester = TextHarvester { _, _ ->
            calls += 1
            Harvest.none
        }

        val first = indexer(harvester).indexPending()
        val second = indexer(harvester).indexPending()

        assertEquals(1, first.empty)
        assertEquals(0, second.read)
        assertEquals(1, calls)
        val row = assertNotNull(database.docTextDao().get(doc.id))
        assertEquals("none", row.source)
        assertEquals(0, row.charCount)
    }

    @Test
    fun `a broken file is remembered as a failure`() = runBlocking {
        val doc = addDoc("quebrado.pdf", DocKind.PDF)
        val harvester = TextHarvester { _, _ -> Harvest.failed }

        val outcome = indexer(harvester).indexPending()

        assertEquals(1, outcome.failed)
        assertEquals("failed", database.docTextDao().get(doc.id)?.source)
    }

    @Test
    fun `the batch limit is respected and the newest come first`() = runBlocking {
        database.docDao().insert(
            Doc(
                relPath = "antigo.txt", name = "antigo.txt", kind = DocKind.TEXT,
                size = 1, modifiedTime = 10, indexedTime = 10
            )
        )
        database.docDao().insert(
            Doc(
                relPath = "novo.txt", name = "novo.txt", kind = DocKind.TEXT,
                size = 1, modifiedTime = 900, indexedTime = 900
            )
        )
        val read = mutableListOf<String>()
        val harvester = TextHarvester { file, _ ->
            read += file.fileName.toString()
            Harvest("x", TextSource.TEXT)
        }

        val outcome = indexer(harvester).indexPending(limit = 1)

        assertEquals(1, outcome.read)
        assertEquals(listOf("novo.txt"), read)
    }

    @Test
    fun `a document whose file vanished is skipped and stays pending`() = runBlocking {
        val doc = addDoc("sumido.txt")
        Files.deleteIfExists(root.resolve("sumido.txt"))
        val harvester = TextHarvester { _, _ -> null }

        val outcome = indexer(harvester).indexPending()

        assertEquals(0, outcome.read)
        assertNull(database.docTextDao().get(doc.id))
        assertEquals(1, database.docTextDao().pending(10).size)
    }

    @Test
    fun `only present documents are read`() = runBlocking {
        val doc = addDoc("apagado.txt")
        database.docDao().setStatus(listOf(doc.id), DocStatus.MISSING)
        var calls = 0
        val harvester = TextHarvester { _, _ ->
            calls += 1
            Harvest("texto", TextSource.TEXT)
        }

        indexer(harvester).indexPending()

        assertEquals(0, calls)
    }
}
