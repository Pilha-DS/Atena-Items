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
import com.openappslabs.docs.data.source.DocsDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FolderTagRepositoryTest {

    private lateinit var database: DocsDatabase
    private lateinit var tags: TagRepository
    private lateinit var repository: FolderTagRepository

    @BeforeTest
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<DocsDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        tags = TagRepository(database.tagDao(), database.folderTagDao())
        repository = FolderTagRepository(database.folderTagDao(), tags)
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    @Test
    fun `setInherited creates tags and replaces the folder set`() = runBlocking {
        assertTrue(repository.setInherited("profissional/tebas/notas-fiscais", listOf("nota-fiscal", "Fiscal/2026")))
        val first = repository.observeByFolder().first()
        assertEquals(
            listOf("fiscal/2026", "nota-fiscal"),
            first["profissional/tebas/notas-fiscais"]
        )

        assertTrue(repository.setInherited("profissional/tebas/notas-fiscais", listOf("nota-fiscal")))
        val second = repository.observeByFolder().first()
        assertEquals(listOf("nota-fiscal"), second["profissional/tebas/notas-fiscais"])
    }

    @Test
    fun `empty list clears inheritance`() = runBlocking {
        assertTrue(repository.setInherited("notas-fiscais", listOf("nota-fiscal")))
        assertTrue(repository.setInherited("notas-fiscais", emptyList()))
        assertEquals(emptyMap(), repository.observeByFolder().first())
    }

    @Test
    fun `root and inbox are refused`() = runBlocking {
        assertFalse(repository.setInherited("", listOf("x")))
        assertFalse(repository.setInherited("init", listOf("x")))
        assertEquals(emptyMap(), repository.observeByFolder().first())
    }

    @Test
    fun `forgetFolder clears the subtree`() = runBlocking {
        assertTrue(repository.setInherited("a", listOf("um")))
        assertTrue(repository.setInherited("a/b", listOf("dois")))
        assertTrue(repository.setInherited("c", listOf("tres")))
        repository.forgetFolder("a")
        val left = repository.observeByFolder().first()
        assertEquals(setOf("c"), left.keys)
        assertEquals(listOf("tres"), left["c"])
    }

    @Test
    fun `deleting a tag drops folder links`() = runBlocking {
        assertTrue(repository.setInherited("notas-fiscais", listOf("nota-fiscal/2026")))
        tags.removeTree("nota-fiscal")
        assertEquals(emptyMap(), repository.observeByFolder().first())
    }
}
