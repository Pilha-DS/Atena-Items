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

package com.openappslabs.docs.data.tags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TagPathsTest {

    @Test
    fun `trims, lowercases and collapses separators`() {
        assertEquals("acadêmico/faculdade", TagPaths.normalize("  Acadêmico / Faculdade  "))
        assertEquals("pessoal", TagPaths.normalize("PESSOAL"))
        assertEquals("a/b", TagPaths.normalize("a//b"))
        assertEquals("a/b", TagPaths.normalize("/a/b/"))
    }

    @Test
    fun `keeps accents because they change the word`() {
        assertEquals("acadêmico", TagPaths.normalize("Acadêmico"))
    }

    @Test
    fun `rejects empty input`() {
        assertNull(TagPaths.normalize(""))
        assertNull(TagPaths.normalize("   "))
        assertNull(TagPaths.normalize("//"))
    }

    @Test
    fun `rejects too deep, too long and traversal segments`() {
        assertNull(TagPaths.normalize("a/b/c/d/e/f"))
        assertNotNull(TagPaths.normalize("a/b/c/d/e"))
        assertNull(TagPaths.normalize("a".repeat(TagPaths.MAX_SEGMENT + 1)))
        assertNull(TagPaths.normalize("a/" + "b".repeat(TagPaths.MAX_SEGMENT + 1)))
        assertNull(TagPaths.normalize("a/" + "b".repeat(TagPaths.MAX_SEGMENT + 1) + "/c"))
        assertNull(TagPaths.normalize(".."))
        assertNull(TagPaths.normalize("a/../b"))
    }

    @Test
    fun `rejects control characters`() {
        assertNull(TagPaths.normalize("a\tb"))
        assertNull(TagPaths.normalize("a\nb"))
    }

    @Test
    fun `chain lists every parent`() {
        assertEquals(listOf("a"), TagPaths.chain("a"))
        assertEquals(listOf("a", "a/b", "a/b/c"), TagPaths.chain("a/b/c"))
        assertEquals(emptyList(), TagPaths.chain(""))
    }

    @Test
    fun `name and depth come from the path`() {
        assertEquals("faculdade", TagPaths.name("academico/faculdade"))
        assertEquals(0, TagPaths.depth("pessoal"))
        assertEquals(2, TagPaths.depth("a/b/c"))
    }

    @Test
    fun `descendant pattern escapes wildcards`() {
        assertEquals("50\\%\\_off/%", TagPaths.descendantPattern("50%_off"))
    }

    @Test
    fun `covers matches the filter and its descendants only`() {
        assertTrue(TagPaths.covers(listOf("academico/faculdade"), "academico"))
        assertTrue(TagPaths.covers(listOf("mes-9"), "mes-9"))
        assertFalse(TagPaths.covers(listOf("mes-9"), "mes-90"))
        assertFalse(TagPaths.covers(listOf("mes-10"), "mes-1"))
        assertFalse(TagPaths.covers(listOf("nota-fiscal"), "mes-9"))
    }
}
