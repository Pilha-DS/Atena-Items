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

package com.openappslabs.docs.platform

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The library root decides where the user's files are read from, so getting it
 * wrong either hides documents or, worse, points the index at the wrong tree.
 */
class DocsPathsTest {

    private val home: Path = createTempDirectory("docs-home")

    private fun userDirs(content: String): Path {
        val file = home.resolve("user-dirs.dirs")
        Files.writeString(file, content)
        return file
    }

    @Test
    fun `uses the localized documents dir declared by the distro`() {
        val file = userDirs("""XDG_DOCUMENTS_DIR="${'$'}HOME/Documentos"""")

        assertEquals(
            home.resolve("Documentos"),
            DocsPaths.documentsDir(home, file)
        )
        assertEquals(
            home.resolve("Documentos/AtenaData/Docs"),
            DocsPaths.libraryRoot(home, file)
        )
    }

    @Test
    fun `accepts an absolute documents path`() {
        val file = userDirs("XDG_DOCUMENTS_DIR=\"/mnt/dados/Docs\"")

        assertEquals(Path.of("/mnt/dados/Docs"), DocsPaths.documentsDir(home, file))
    }

    @Test
    fun `falls back to Documentos when user-dirs does not exist`() {
        val missing = home.resolve("user-dirs.dirs")

        assertEquals(home.resolve("Documentos"), DocsPaths.documentsDir(home, missing))
    }

    @Test
    fun `ignores blank and relative values`() {
        val blank = userDirs("XDG_DOCUMENTS_DIR=\"\"")
        assertEquals(home.resolve("Documentos"), DocsPaths.documentsDir(home, blank))

        val relative = userDirs("XDG_DOCUMENTS_DIR=\"Documentos\"")
        assertEquals(home.resolve("Documentos"), DocsPaths.documentsDir(home, relative))
    }

    @Test
    fun `prefers Documents when only that one exists`() {
        Files.createDirectories(home.resolve("Documents"))
        val missing = home.resolve("user-dirs.dirs")

        assertEquals(home.resolve("Documents"), DocsPaths.documentsDir(home, missing))
    }

    @Test
    fun `keeps the localized folder when both exist`() {
        Files.createDirectories(home.resolve("Documents"))
        Files.createDirectories(home.resolve("Documentos"))
        val missing = home.resolve("user-dirs.dirs")

        assertEquals(home.resolve("Documentos"), DocsPaths.documentsDir(home, missing))
    }

    @Test
    fun `override wins over user-dirs`() {
        val file = userDirs("""XDG_DOCUMENTS_DIR="${'$'}HOME/Documentos"""")

        assertEquals(
            Path.of("/srv/biblioteca/AtenaData/Docs"),
            DocsPaths.libraryRoot(home, file, "/srv/biblioteca")
        )
    }

    @Test
    fun `blank override is ignored`() {
        val file = userDirs("""XDG_DOCUMENTS_DIR="${'$'}HOME/Documentos"""")

        assertEquals(
            home.resolve("Documentos/AtenaData/Docs"),
            DocsPaths.libraryRoot(home, file, "   ")
        )
    }

    @Test
    fun `expands tilde and home in the override`() {
        assertEquals(home.resolve("Docs"), DocsPaths.expand("~/Docs", home))
        assertEquals(home, DocsPaths.expand("~", home))
        assertEquals(home.resolve("Docs"), DocsPaths.expand("${'$'}HOME/Docs", home))
        assertNull(DocsPaths.expand("Docs", home))
        assertNull(DocsPaths.expand("", home))
    }

    @Test
    fun `normalizes a traversal in the override`() {
        assertEquals(home.resolve("Docs"), DocsPaths.expand("~/a/../Docs", home))
    }

    @Test
    fun `user-dirs parser skips comments and keeps quoted values`() {
        val file = userDirs(
            """
            # written by the session
            XDG_DESKTOP_DIR="${'$'}HOME/Desktop"
            XDG_DOCUMENTS_DIR="${'$'}HOME/Documentos"
            broken-line
            """.trimIndent()
        )

        val parsed = DocsPaths.readUserDirs(file)
        // Quotes are kept raw here; `expand` is what strips them.
        assertEquals("\"${'$'}HOME/Documentos\"", parsed["XDG_DOCUMENTS_DIR"])
        assertTrue(parsed.containsKey("XDG_DESKTOP_DIR"))
        assertEquals(2, parsed.size)
    }
}
