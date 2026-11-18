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

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Importing chosen files into the inbox. Every assertion looks at the disk too:
 * an import that reports success but leaves the file behind is worse than one
 * that fails honestly.
 */
class InboxImportTest {

    private val root: Path = createTempDirectory("docs-import")
    private val inbox = Inbox.path(root)
    private val importer = InboxImport(root)

    private fun source(directory: Path, name: String, body: String = "conteúdo"): Path {
        Files.createDirectories(directory)
        val file = directory.resolve(name)
        Files.writeString(file, body)
        return file
    }

    @Test
    fun `a file chosen elsewhere is moved into the inbox`() = runBlocking {
        val downloads = createTempDirectory("docs-downloads")
        val file = source(downloads, "contrato.pdf", "texto do contrato")

        val report = importer.import(listOf(file))

        assertEquals(ImportReport(imported = 1), report)
        assertFalse(Files.exists(file))
        assertEquals("texto do contrato", Files.readString(inbox.resolve("contrato.pdf")))
    }

    @Test
    fun `the inbox is created when it is missing`() = runBlocking {
        val downloads = createTempDirectory("docs-downloads")
        val file = source(downloads, "a.pdf")

        assertFalse(Files.exists(inbox))
        importer.import(listOf(file))

        assertTrue(Files.isDirectory(inbox))
        assertTrue(Files.exists(inbox.resolve("a.pdf")))
    }

    @Test
    fun `several files in one go`() = runBlocking {
        val downloads = createTempDirectory("docs-downloads")
        val first = source(downloads, "a.pdf")
        val second = source(downloads, "b.docx")

        val report = importer.import(listOf(first, second))

        assertEquals(2, report.imported)
        assertEquals(
            listOf("a.pdf", "b.docx"),
            Files.list(inbox).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() }
        )
    }

    @Test
    fun `a name already taken is renamed instead of overwritten`() = runBlocking {
        val downloads = createTempDirectory("docs-downloads")
        Inbox.ensure(root)
        Files.writeString(inbox.resolve("contrato.pdf"), "o primeiro")
        val file = source(downloads, "contrato.pdf", "o segundo")

        val report = importer.import(listOf(file))

        assertEquals(1, report.imported)
        assertEquals(1, report.renamed)
        assertEquals("o primeiro", Files.readString(inbox.resolve("contrato.pdf")))
        assertEquals("o segundo", Files.readString(inbox.resolve("contrato (2).pdf")))
    }

    @Test
    fun `the next free number is picked when the copies already exist`() = runBlocking {
        val downloads = createTempDirectory("docs-downloads")
        Inbox.ensure(root)
        Files.writeString(inbox.resolve("nota.txt"), "primeiro")
        Files.writeString(inbox.resolve("nota (2).txt"), "ocupado")
        val file = source(downloads, "nota.txt", "novo")

        importer.import(listOf(file))

        assertEquals("novo", Files.readString(inbox.resolve("nota (3).txt")))
        assertEquals("primeiro", Files.readString(inbox.resolve("nota.txt")))
        assertEquals("ocupado", Files.readString(inbox.resolve("nota (2).txt")))
    }

    @Test
    fun `a file already in the inbox is skipped`() = runBlocking {
        Inbox.ensure(root)
        val inside = source(inbox, "ja-estava.pdf", "conteudo")

        val report = importer.import(listOf(inside))

        assertEquals(ImportReport(skipped = 1), report)
        assertTrue(Files.exists(inside))
    }

    @Test
    fun `folders, missing files and a batch with nothing usable are skipped`() = runBlocking {
        Inbox.ensure(root)
        val downloads = createTempDirectory("docs-downloads")
        val directory = Files.createDirectories(downloads.resolve("pasta"))
        val missing = downloads.resolve("sumiu.pdf")

        val report = importer.import(listOf(directory, missing))

        assertEquals(ImportReport(skipped = 2), report)
        assertTrue(Files.isDirectory(directory))
    }

    @Test
    fun `an empty selection does nothing`() = runBlocking {
        assertEquals(ImportReport(), importer.import(emptyList()))
        assertFalse(Files.exists(inbox))
    }

    @Test
    fun `a name without extension still gets a distinguishable copy`() = runBlocking {
        val downloads = createTempDirectory("docs-downloads")
        Inbox.ensure(root)
        Files.writeString(inbox.resolve("LEIAME"), "primeiro")
        val file = source(downloads, "LEIAME", "segundo")

        importer.import(listOf(file))

        assertEquals("segundo", Files.readString(inbox.resolve("LEIAME (2)")))
    }
}
