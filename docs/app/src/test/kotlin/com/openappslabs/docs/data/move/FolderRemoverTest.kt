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

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Deleting folders from the window: what it takes with it, and what it refuses. */
class FolderRemoverTest {

    private val root: Path = createTempDirectory("docs-remove")
    private val remover = FolderRemover(root)

    private fun putFile(relPath: String) {
        val file = root.resolve(relPath)
        Files.createDirectories(file.parent)
        Files.writeString(file, "conteudo")
    }

    @Test
    fun `deletes an empty folder`() {
        Files.createDirectories(root.resolve("vazia"))

        val outcome = remover.remove("vazia")

        assertEquals(RemoveOutcome.Removed("vazia", 0), outcome)
        assertFalse(Files.exists(root.resolve("vazia")))
    }

    @Test
    fun `takes the whole subtree with it`() {
        putFile("faculdade/2026/semestre-1/prova.pdf")
        putFile("faculdade/2026/semestre-1/resumo.pdf")
        putFile("faculdade/rascunho.txt")

        val outcome = remover.remove("faculdade")

        assertEquals(RemoveOutcome.Removed("faculdade", 3), outcome)
        assertFalse(Files.exists(root.resolve("faculdade")))
        // Only the branch: the library itself stays.
        assertTrue(Files.isDirectory(root))
    }

    @Test
    fun `counts what is inside before the confirmation`() {
        putFile("academico/a.pdf")
        putFile("academico/2026/b.pdf")
        Files.createDirectories(root.resolve("academico/2026/vazia"))

        assertEquals(2, remover.count("academico"))
        // Counting is not deleting.
        assertTrue(Files.exists(root.resolve("academico/a.pdf")))
    }

    @Test
    fun `a folder that is not there is reported`() {
        assertEquals(RemoveOutcome.Rejected(RemoveIssue.NOT_FOUND), remover.remove("academico"))
        assertEquals(0, remover.count("academico"))
    }

    @Test
    fun `a file with the folder name is not a folder to delete`() {
        putFile("relatorio.pdf")

        assertEquals(RemoveOutcome.Rejected(RemoveIssue.NOT_FOUND), remover.remove("relatorio.pdf"))
        assertTrue(Files.exists(root.resolve("relatorio.pdf")))
    }

    @Test
    fun `the top of the library is refused`() {
        assertEquals(RemoveOutcome.Rejected(RemoveIssue.ROOT), remover.remove(""))
        assertEquals(RemoveOutcome.Rejected(RemoveIssue.ROOT), remover.remove("raiz"))
        assertTrue(Files.isDirectory(root))
    }

    @Test
    fun `the inbox is refused and its subfolders are not`() {
        putFile("init/contrato.pdf")
        putFile("init/2026/nota.pdf")

        assertEquals(RemoveOutcome.Rejected(RemoveIssue.INBOX), remover.remove("init"))
        assertTrue(Files.exists(root.resolve("init/contrato.pdf")))

        assertEquals(RemoveOutcome.Removed("init/2026", 1), remover.remove("init/2026"))
        assertTrue(Files.exists(root.resolve("init/contrato.pdf")))
    }

    @Test
    fun `escaping the library is invalid and touches nothing outside`() {
        val outside = createTempDirectory("docs-fora")
        Files.writeString(outside.resolve("importante.pdf"), "nao apague")

        assertEquals(RemoveOutcome.Rejected(RemoveIssue.INVALID), remover.remove("../${outside.fileName}"))
        // A leading `/` is read as "inside the library" (the same rule the
        // suggestions follow), so an absolute path is simply not there.
        assertEquals(
            RemoveOutcome.Rejected(RemoveIssue.NOT_FOUND),
            remover.remove(outside.toAbsolutePath().toString())
        )
        assertTrue(Files.exists(outside.resolve("importante.pdf")))
    }

    @Test
    fun `a symlink inside the folder is deleted, never its target`() {
        val outside = createTempDirectory("docs-link")
        Files.writeString(outside.resolve("original.pdf"), "conteudo")
        Files.createDirectories(root.resolve("academico"))
        Files.createSymbolicLink(root.resolve("academico/atalho"), outside)

        val outcome = remover.remove("academico")

        assertEquals(RemoveOutcome.Removed("academico", 1), outcome)
        assertFalse(Files.exists(root.resolve("academico")))
        assertTrue(Files.exists(outside.resolve("original.pdf")))
    }
}
