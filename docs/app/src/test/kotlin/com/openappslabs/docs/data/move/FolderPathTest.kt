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

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderPathTest {

    @Test
    fun `the case of a real folder name is kept`() {
        assertEquals("Academico/2026", FolderPath.normalize("  Academico / 2026  "))
    }

    @Test
    fun `leading and trailing separators are forgiven`() {
        assertEquals("academico/2026", FolderPath.normalize("/academico/2026/"))
        assertEquals("academico/2026", FolderPath.normalize("//academico//2026//"))
    }

    @Test
    fun `an empty answer means the library root`() {
        assertEquals(FolderPath.ROOT, FolderPath.normalize(""))
        assertEquals(FolderPath.ROOT, FolderPath.normalize("   "))
        assertEquals(FolderPath.ROOT, FolderPath.normalize("/"))
        assertTrue(FolderPath.ROOT.isEmpty())
    }

    @Test
    fun `the model saying root means the library root`() {
        assertEquals(FolderPath.ROOT, FolderPath.normalize("raiz"))
        assertEquals(FolderPath.ROOT, FolderPath.normalize("  RAIZ "))
        assertEquals(FolderPath.ROOT, FolderPath.normalize("root"))
    }

    @Test
    fun `a path that climbs out of the library is refused`() {
        assertNull(FolderPath.normalize(".."))
        assertNull(FolderPath.normalize("../fora"))
        assertNull(FolderPath.normalize("a/../../etc"))
        assertNull(FolderPath.normalize("a/./b"))
    }

    @Test
    fun `a hidden folder is refused`() {
        assertNull(FolderPath.normalize(".git"))
        assertNull(FolderPath.normalize("a/.cache"))
    }

    @Test
    fun `a separator from another system or a tilde is refused`() {
        assertNull(FolderPath.normalize("a\\b"))
        assertNull(FolderPath.normalize("~/Documentos"))
        assertNull(FolderPath.normalize("/home/alguem/Documentos~"))
    }

    @Test
    fun `too deep, too long or with control characters is refused`() {
        assertNull(FolderPath.normalize("a/b/c/d/e/f"))
        assertNull(FolderPath.normalize("x".repeat(FolderPath.MAX_SEGMENT + 1)))
        assertNull(FolderPath.normalize((1..4).joinToString("/") { "x".repeat(60) }))
        assertNull(FolderPath.normalize("a\u0001b"))
    }

    @Test
    fun `resolve lands inside the root, root included`() {
        val root = createTempDirectory("docs-folder-path")

        assertEquals(
            root.resolve("a/b").normalize(),
            FolderPath.resolve(root, "a/b")
        )
        assertEquals(root.normalize(), FolderPath.resolve(root, FolderPath.ROOT))
        assertNull(FolderPath.resolve(root, "../fora"))
    }

    @Test
    fun `of and join are inverses`() {
        assertEquals("a/b", FolderPath.of("a/b/c.pdf"))
        assertEquals(FolderPath.ROOT, FolderPath.of("c.pdf"))
        assertEquals("a/b/c.pdf", FolderPath.join("a/b", "c.pdf"))
        assertEquals("c.pdf", FolderPath.join(FolderPath.ROOT, "c.pdf"))
    }

    @Test
    fun `reuseCase keeps the spelling that already exists on disk`() {
        val existing = listOf("Academico/2026", "Pessoal")

        assertEquals("Academico/2026/final", FolderPath.reuseCase("academico/2026/final", existing))
        assertEquals("Pessoal/x", FolderPath.reuseCase("pessoal/x", existing))
    }

    @Test
    fun `reuseCase changes nothing when there is no folder to copy`() {
        assertEquals("academico/2026", FolderPath.reuseCase("academico/2026", emptyList()))
        assertEquals(FolderPath.ROOT, FolderPath.reuseCase(FolderPath.ROOT, listOf("a")))
    }
}
