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
import com.openappslabs.docs.data.model.Doc
import com.openappslabs.docs.data.model.DocKind
import com.openappslabs.docs.data.model.DocStatus
import com.openappslabs.docs.data.source.DocsDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TagRepositoryTest {

    private lateinit var database: DocsDatabase
    private lateinit var repository: TagRepository

    @BeforeTest
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<DocsDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        repository = TagRepository(
            dao = database.tagDao(),
            aliases = database.tagAliasDao()
        )
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    private suspend fun doc(name: String = "a.pdf"): Long = database.docDao().insert(
        Doc(
            relPath = name,
            name = name,
            kind = DocKind.fromFileName(name),
            size = 1,
            modifiedTime = 1
        )
    )

    private suspend fun paths(): List<String> = database.tagDao().all().map { it.path }

    @Test
    fun `creating a leaf creates its whole chain`() = runBlocking {
        assertNotNull(repository.ensure("academico/faculdade/2026"))

        assertEquals(
            listOf("academico", "academico/faculdade", "academico/faculdade/2026"),
            paths()
        )
        assertEquals(
            listOf("2026", "academico", "faculdade"),
            database.tagDao().all().map { it.name }.sorted()
        )
    }

    @Test
    fun `creating twice keeps one row and the same id`() = runBlocking {
        val first = repository.ensure("pessoal")
        val second = repository.ensure("pessoal")

        assertEquals(first, second)
        assertEquals(listOf("pessoal"), paths())
    }

    @Test
    fun `applying a tag links it to the document`() = runBlocking {
        val id = doc()

        assertTrue(repository.apply(id, "academico/prova"))

        val rows = database.tagDao().observeAssignments(DocStatus.PRESENT).first()
        // Both ancestors were created, but only the leaf is applied.
        assertEquals(listOf("academico/prova"), rows.map { it.path })
        assertEquals(id, rows.single().docId)
    }

    @Test
    fun `applying the same tag twice is not duplicated`() = runBlocking {
        val id = doc()

        repository.apply(id, "pessoal")
        repository.apply(id, "pessoal")

        assertEquals(1, database.tagDao().observeAssignments(DocStatus.PRESENT).first().size)
    }

    @Test
    fun `removing a tag keeps the tag itself`() = runBlocking {
        val id = doc()
        repository.apply(id, "pessoal/financas")

        assertTrue(repository.remove(id, "pessoal/financas"))

        assertTrue(database.tagDao().observeAssignments(DocStatus.PRESENT).first().isEmpty())
        assertEquals(listOf("pessoal", "pessoal/financas"), paths())
    }

    @Test
    fun `removing an unknown tag reports failure`() = runBlocking {
        val id = doc()

        assertFalse(repository.remove(id, "nao-existe"))
    }

    @Test
    fun `counts ignore documents whose file is gone`() = runBlocking {
        val present = doc("presente.pdf")
        val missing = doc("sumido.pdf")
        repository.apply(present, "pessoal")
        repository.apply(missing, "pessoal")
        database.docDao().setStatus(listOf(missing), DocStatus.MISSING)

        val counts = database.tagDao().observeCounts(DocStatus.PRESENT).first()

        assertEquals(1, counts.single().count)
    }

    @Test
    fun `a tag path with wildcards is only applied literally`() = runBlocking {
        val first = doc("um.pdf")
        val second = doc("dois.pdf")
        repository.apply(first, "50%")
        repository.apply(second, "50x")

        val rows = database.tagDao().observeAssignments(DocStatus.PRESENT).first()

        assertEquals(listOf("50%", "50x"), rows.map { it.path }.sorted())
    }

    @Test
    fun `applying a tag that exists assigns it without duplicating the row`() = runBlocking {
        val id = doc()
        repository.ensure("academico")

        assertTrue(repository.apply(id, "academico"))
        assertTrue(repository.apply(id, "academico"))

        assertEquals(listOf("academico"), paths())
        assertEquals(
            listOf("academico"),
            database.tagDao().observeAssignments(DocStatus.PRESENT).first().map { it.path }
        )
    }

    @Test
    fun `applying a missing tag creates the whole chain`() = runBlocking {
        val id = doc()

        assertTrue(repository.apply(id, "faculdade/2026"))

        assertEquals(listOf("faculdade", "faculdade/2026"), paths())
        assertTrue(database.tagDao().observeAssignments(DocStatus.PRESENT).first().isNotEmpty())
    }

    @Test
    fun `deleting a tag takes its subtree and the assignments`() = runBlocking {
        val prova = doc("prova.pdf")
        val resumo = doc("resumo.pdf")
        repository.apply(prova, "academico/faculdade")
        repository.apply(resumo, "academico")
        repository.apply(resumo, "pessoal")

        val removal = repository.removeTree("academico")

        assertEquals(TagRemoval(tags = 2, documents = 2), removal)
        assertEquals(listOf("pessoal"), paths())
        assertEquals(
            listOf("pessoal"),
            database.tagDao().observeAssignments(DocStatus.PRESENT).first().map { it.path }
        )
    }

    @Test
    fun `deleting a tag leaves its parent alone`() = runBlocking {
        repository.ensure("academico/faculdade/2026")

        val removal = repository.removeTree("academico/faculdade")

        assertEquals(TagRemoval(tags = 2, documents = 0), removal)
        assertEquals(listOf("academico"), paths())
    }

    @Test
    fun `a tag with wildcards is deleted literally`() = runBlocking {
        repository.ensure("50%")
        repository.ensure("50x")

        assertEquals(TagRemoval(tags = 1, documents = 0), repository.removeTree("50%"))
        assertEquals(listOf("50x"), paths())
    }

    @Test
    fun `deleting an unknown tag removes nothing`() = runBlocking {
        assertEquals(TagRemoval(tags = 0, documents = 0), repository.removeTree("nao-existe"))
        assertEquals(TagRemoval(tags = 0, documents = 0), repository.removeTree(""))
    }

    @Test
    fun `aliases are stored under the tag and cleared with it`() = runBlocking {
        repository.ensure("nota-fiscal")
        assertTrue(repository.addAlias("nota-fiscal", "NFe"))
        assertTrue(repository.addAlias("nota-fiscal", "danfe"))
        assertFalse(repository.addAlias("nota-fiscal", "nota-fiscal"))
        assertEquals(listOf("danfe", "nfe"), repository.aliasesOf("nota-fiscal"))

        repository.removeTree("nota-fiscal")

        assertEquals(emptyList(), repository.aliasesOf("nota-fiscal"))
        assertTrue(database.tagAliasDao().observeRows().first().isEmpty())
    }

    @Test
    fun `renaming a tag keeps assignments and remaps the subtree`() = runBlocking {
        val id = doc()
        assertTrue(repository.apply(id, "nota-fiscal/2026"))
        assertTrue(repository.addAlias("nota-fiscal", "nfe"))

        assertTrue(repository.rename("nota-fiscal", "nf"))

        assertEquals(listOf("nf", "nf/2026"), paths())
        assertEquals(
            listOf("nf/2026"),
            database.tagDao().observeAssignments(DocStatus.PRESENT).first().map { it.path }
        )
        assertEquals(listOf("nfe"), repository.aliasesOf("nf"))
        assertEquals(emptyList(), repository.aliasesOf("nota-fiscal"))
    }

    @Test
    fun `renaming refuses a path already taken`() = runBlocking {
        repository.ensure("nota-fiscal")
        repository.ensure("nf")

        assertFalse(repository.rename("nota-fiscal", "nf"))
        assertEquals(listOf("nf", "nota-fiscal"), paths())
    }
}
