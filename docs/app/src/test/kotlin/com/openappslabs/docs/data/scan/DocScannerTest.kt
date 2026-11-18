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

package com.openappslabs.docs.data.scan

import com.openappslabs.docs.data.model.DocKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocScannerTest {

    private val root: Path = createTempDirectory("docs-scan")

    private fun write(relative: String, content: String = "conteúdo"): Path {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        return file
    }

    @Test
    fun `found files are relative to the root and keep their kind`() {
        write("relatorio.pdf")
        write("faculdade/notas.docx")

        val files = DocScanner.scan(root).files

        assertEquals(listOf("faculdade/notas.docx", "relatorio.pdf"), files.map { it.relPath })
        assertEquals(
            listOf(DocKind.WORD, DocKind.PDF),
            files.map { it.kind }
        )
        assertEquals("notas.docx", files.first().name)
    }

    @Test
    fun `size and modification time come from the file itself`() {
        val file = write("a.txt", "12345")

        val scanned = DocScanner.scan(root).files.single()

        assertEquals(5, scanned.size)
        assertEquals(Files.getLastModifiedTime(file).toMillis(), scanned.modifiedTime)
    }

    @Test
    fun `hidden files and hidden folders are not documents`() {
        write("visivel.pdf")
        write(".oculto.pdf")
        write(".git/config")
        write(".cache/lixo.png")

        val files = DocScanner.scan(root).files

        assertEquals(listOf("visivel.pdf"), files.map { it.relPath })
    }

    @Test
    fun `configured excludes are skipped but not their namesakes elsewhere`() {
        write("node_modules/pacote.pdf")
        write("projeto/node_modules.json")

        val files = DocScanner.scan(root, excludes = setOf("node_modules")).files

        assertEquals(listOf("projeto/node_modules.json"), files.map { it.relPath })
    }

    @Test
    fun `symlinks are not followed`() {
        val outside = createTempDirectory("docs-outside")
        Files.writeString(outside.resolve("longe.pdf"), "x")
        Files.createSymbolicLink(root.resolve("atalho.pdf"), outside.resolve("longe.pdf"))
        Files.createSymbolicLink(root.resolve("pasta"), outside)

        val files = DocScanner.scan(root).files

        assertTrue(files.isEmpty(), "esperava nenhum arquivo, veio ${files.map { it.relPath }}")
    }

    @Test
    fun `missing root scans as empty instead of failing`() {
        val result = DocScanner.scan(root.resolve("nao-existe"))

        assertTrue(result.files.isEmpty())
        assertEquals(0, result.unreadable)
    }

    @Test
    fun `empty folder is empty and valid`() {
        val result = DocScanner.scan(root)

        assertTrue(result.files.isEmpty())
        assertEquals(0, result.unreadable)
        assertFalse(Files.notExists(root))
    }
}
