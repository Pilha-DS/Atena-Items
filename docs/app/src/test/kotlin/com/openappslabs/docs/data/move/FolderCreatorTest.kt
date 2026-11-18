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

/** Creating folders from the window: what it makes, and what it refuses. */
class FolderCreatorTest {

    private val root: Path = createTempDirectory("docs-create")
    private val creator = FolderCreator(root)

    @Test
    fun `creates a folder at the top of the library`() {
        val outcome = creator.create("academico")

        assertEquals(CreateOutcome.Created("academico"), outcome)
        assertTrue(Files.isDirectory(root.resolve("academico")))
    }

    @Test
    fun `creates the whole branch in one go`() {
        val outcome = creator.create("faculdade/2026/semestre-1")

        assertEquals(CreateOutcome.Created("faculdade/2026/semestre-1"), outcome)
        assertTrue(Files.isDirectory(root.resolve("faculdade/2026/semestre-1")))
    }

    @Test
    fun `trims the spacing and keeps the case the user typed`() {
        val outcome = creator.create("  Acadêmico / USP  ")

        assertEquals(CreateOutcome.Created("Acadêmico/USP"), outcome)
        assertTrue(Files.isDirectory(root.resolve("Acadêmico/USP")))
    }

    @Test
    fun `a folder that already exists is refused, not reused`() {
        Files.createDirectories(root.resolve("academico"))

        assertEquals(CreateOutcome.Rejected(CreateIssue.EXISTS), creator.create("academico"))
    }

    @Test
    fun `a file with that name also counts as taken`() {
        Files.writeString(root.resolve("academico"), "arquivo")

        assertEquals(CreateOutcome.Rejected(CreateIssue.EXISTS), creator.create("academico"))
        assertFalse(Files.isDirectory(root.resolve("academico")))
    }

    @Test
    fun `inside the inbox creating works like anywhere else`() {
        assertEquals(CreateOutcome.Created("init/faculdade"), creator.create("init/faculdade"))
        assertTrue(Files.isDirectory(root.resolve("init/faculdade")))
    }

    @Test
    fun `unusable paths are invalid`() {
        assertEquals(CreateOutcome.Rejected(CreateIssue.INVALID), creator.create("../fora"))
        assertEquals(CreateOutcome.Rejected(CreateIssue.INVALID), creator.create(""))
        assertEquals(CreateOutcome.Rejected(CreateIssue.INVALID), creator.create("raiz"))
        assertEquals(CreateOutcome.Rejected(CreateIssue.INVALID), creator.create(".oculta"))
        assertEquals(CreateOutcome.Rejected(CreateIssue.INVALID), creator.create("a/b/c/d/e/f"))
        assertFalse(Files.exists(root.resolveSibling("fora")))
    }

    @Test
    fun `a blocked parent is a failure and leaves nothing behind`() {
        Files.writeString(root.resolve("bloqueio"), "arquivo, não pasta")

        assertEquals(CreateOutcome.Rejected(CreateIssue.FAILED), creator.create("bloqueio/sub"))
        assertEquals("arquivo, não pasta", Files.readString(root.resolve("bloqueio")))
    }
}
