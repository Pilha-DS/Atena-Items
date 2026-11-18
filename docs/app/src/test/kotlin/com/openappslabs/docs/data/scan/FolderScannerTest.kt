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

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The folder list the sidebar needs: the disk tree, not the index tree.
 * An empty folder has to appear — that is the whole reason this exists.
 */
class FolderScannerTest {

    private fun folder(root: java.nio.file.Path, path: String) = Files.createDirectories(root.resolve(path))

    @Test
    fun `lists nested folders including the empty ones`() {
        val root = createTempDirectory("docs-folders")
        folder(root, "academico/2026/semestre-1")
        folder(root, "vazia")

        val folders = FolderScanner.scan(root)

        assertEquals(
            listOf("academico", "academico/2026", "academico/2026/semestre-1", "vazia"),
            folders
        )
    }

    @Test
    fun `the root itself is not a folder row`() {
        val root = createTempDirectory("docs-folders")

        assertTrue(FolderScanner.scan(root).isEmpty())
    }

    @Test
    fun `hidden folders and excluded names are skipped whole`() {
        val root = createTempDirectory("docs-folders")
        folder(root, ".git/objects")
        folder(root, "node_modules/pacote")
        folder(root, "visivel")

        assertEquals(listOf("visivel"), FolderScanner.scan(root))
    }

    @Test
    fun `a missing root is an empty list, not an exception`() {
        val root = createTempDirectory("docs-folders").resolve("nao-existe")

        assertTrue(FolderScanner.scan(root).isEmpty())
    }

    @Test
    fun `a file where a folder name is repeated still lists the folder once`() {
        val root = createTempDirectory("docs-folders")
        folder(root, "notas")
        Files.writeString(root.resolve("notas/resumo.txt"), "texto")

        assertEquals(listOf("notas"), FolderScanner.scan(root))
    }
}
