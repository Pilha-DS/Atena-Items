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
import com.openappslabs.docs.data.model.Tag
import com.openappslabs.docs.data.source.DocsDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DescriptionRepositoryTest {

    private lateinit var database: DocsDatabase
    private lateinit var repository: DescriptionRepository

    @BeforeTest
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<DocsDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        repository = DescriptionRepository(database.folderDescriptionDao(), database.tagDao())
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a folder description is stored in sqlite and read back`() = runBlocking {
        assertTrue(repository.describeFolder("faculdade/2026", "provas e trabalhos do semestre"))

        assertEquals(
            mapOf("faculdade/2026" to "provas e trabalhos do semestre"),
            repository.folderNotes()
        )
        assertEquals(
            mapOf("faculdade/2026" to "provas e trabalhos do semestre"),
            repository.observeFolders().first()
        )
    }

    @Test
    fun `the path is normalized before it is stored`() = runBlocking {
        repository.describeFolder("/faculdade//2026/", "notas")

        assertEquals(listOf("faculdade/2026"), repository.folderNotes().keys.toList())
    }

    @Test
    fun `saving again replaces the sentence instead of adding a row`() = runBlocking {
        repository.describeFolder("extensao", "primeira versão")
        repository.describeFolder("extensao", "versão final")

        assertEquals(mapOf("extensao" to "versão final"), repository.folderNotes())
    }

    @Test
    fun `an empty description deletes the row`() = runBlocking {
        repository.describeFolder("extensao", "certificados")
        assertTrue(repository.describeFolder("extensao", "   "))

        assertTrue(repository.folderNotes().isEmpty())
        assertNull(database.folderDescriptionDao().find("extensao"))
    }

    @Test
    fun `line breaks and extra spaces become one line`() {
        assertEquals(
            "certificados de extensão com carga horária",
            DescriptionRepository.clean("  certificados de\nextensão   com\tcarga horária \n")
        )
    }

    @Test
    fun `a very long description is cut`() {
        val cleaned = DescriptionRepository.clean("x".repeat(500))
        assertEquals(DescriptionRepository.MAX_LENGTH, cleaned.length)
    }

    @Test
    fun `the root of the library cannot be described`() = runBlocking {
        assertFalse(repository.describeFolder("", "tudo"))
        assertTrue(repository.folderNotes().isEmpty())
    }

    @Test
    fun `a path that escapes the library is refused`() = runBlocking {
        assertFalse(repository.describeFolder("../fora", "nada"))
        assertTrue(repository.folderNotes().isEmpty())
    }

    @Test
    fun `a tag description is stored on the tag row`() = runBlocking {
        database.tagDao().insert(Tag(path = "academico/usp"))
        assertTrue(repository.describeTag("academico/usp", "matérias da graduação na USP"))

        assertEquals(
            mapOf("academico/usp" to "matérias da graduação na USP"),
            repository.tagNotes()
        )
        assertEquals(
            "matérias da graduação na USP",
            database.tagDao().findByPath("academico/usp")?.description
        )
    }

    @Test
    fun `describing a tag that does not exist writes nothing`() = runBlocking {
        assertFalse(repository.describeTag("inexistente", "nada"))
        assertTrue(repository.tagNotes().isEmpty())
    }

    @Test
    fun `clearing a tag description leaves the tag in place`() = runBlocking {
        database.tagDao().insert(Tag(path = "unip"))
        repository.describeTag("unip", "faculdade")
        repository.describeTag("unip", "")

        assertEquals("", database.tagDao().findByPath("unip")?.description)
        assertTrue(repository.tagNotes().isEmpty())
        assertEquals(listOf("unip"), database.tagDao().all().map { it.path })
    }

    @Test
    fun `deleting a folder forgets it and everything below it`() = runBlocking {
        repository.describeFolder("faculdade", "tudo da faculdade")
        repository.describeFolder("faculdade/2026", "este ano")
        repository.describeFolder("faculdade/2025", "ano passado")
        repository.describeFolder("pessoal", "documentos pessoais")

        repository.forgetFolder("faculdade")

        assertEquals(mapOf("pessoal" to "documentos pessoais"), repository.folderNotes())
    }

    @Test
    fun `forgetting a folder does not touch a folder with the same prefix`() = runBlocking {
        repository.describeFolder("faculdade", "tudo")
        // `faculdade2` starts with the same letters and must survive: the LIKE
        // pattern carries the separator, not just the prefix.
        repository.describeFolder("faculdade2", "outra coisa")

        repository.forgetFolder("faculdade")

        assertEquals(mapOf("faculdade2" to "outra coisa"), repository.folderNotes())
    }
}
