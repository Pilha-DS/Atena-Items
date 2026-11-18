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
import kotlin.test.assertTrue

class FolderTagInheritTest {

    @Test
    fun `folder chain walks ancestors then itself`() {
        assertEquals(
            listOf("profissional", "profissional/tebas", "profissional/tebas/notas-fiscais"),
            FolderTagInherit.folderChain("profissional/tebas/notas-fiscais")
        )
        assertEquals(listOf("a"), FolderTagInherit.folderChain("a"))
        assertEquals(emptyList(), FolderTagInherit.folderChain(""))
    }

    @Test
    fun `resolve unions tags from every ancestor folder`() {
        val byFolder = mapOf(
            "profissional" to listOf("trabalho"),
            "profissional/tebas/notas-fiscais" to listOf("nota-fiscal", "fiscal")
        )
        assertEquals(
            listOf("fiscal", "nota-fiscal", "trabalho"),
            FolderTagInherit.resolve("profissional/tebas/notas-fiscais/2024", byFolder)
        )
    }

    @Test
    fun `resolve on the declaring folder still inherits`() {
        val byFolder = mapOf("notas-fiscais" to listOf("nota-fiscal"))
        assertEquals(
            listOf("nota-fiscal"),
            FolderTagInherit.resolve("notas-fiscais", byFolder)
        )
    }

    @Test
    fun `empty folder and empty map inherit nothing`() {
        assertEquals(emptyList(), FolderTagInherit.resolve("", mapOf("a" to listOf("x"))))
        assertEquals(emptyList(), FolderTagInherit.resolve("a/b", emptyMap()))
    }

    @Test
    fun `isUnder matches the folder and its subfolders`() {
        assertTrue(FolderTagInherit.isUnder("notas-fiscais", "notas-fiscais"))
        assertTrue(FolderTagInherit.isUnder("notas-fiscais/2024", "notas-fiscais"))
        assertFalse(FolderTagInherit.isUnder("outro", "notas-fiscais"))
        assertFalse(FolderTagInherit.isUnder("notas-fiscais-old", "notas-fiscais"))
        assertFalse(FolderTagInherit.isUnder("x", ""))
    }

    @Test
    fun `inherited counts do not double-count the same tag from two folders`() {
        val byFolder = mapOf(
            "a" to listOf("nota-fiscal"),
            "a/b" to listOf("nota-fiscal")
        )
        val counts = FolderTagInherit.inheritedDocFoldersByTag(
            docFolders = listOf("a/b/c"),
            byFolder = byFolder
        )
        assertEquals(1, counts["nota-fiscal"])
    }
}
