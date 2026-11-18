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

package com.openappslabs.docs.data.source

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.openappslabs.docs.data.model.Doc
import com.openappslabs.docs.data.model.DocKind
import com.openappslabs.docs.data.model.DocStatus
import com.openappslabs.docs.data.model.DocText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The search must look inside the text, not only at the file name — and it
 * must keep escaping the wildcards, now that the same pattern also runs
 * against a much bigger column.
 */
class DocTextSearchTest {

    private lateinit var database: DocsDatabase

    @BeforeTest
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<DocsDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    private fun doc(relative: String): Doc = Doc(
        relPath = relative,
        name = relative.substringAfterLast('/'),
        kind = DocKind.PDF,
        size = 10,
        modifiedTime = 100,
        indexedTime = 100
    )

    private fun search(query: String) = database.docDao().observeByName(
        status = DocStatus.PRESENT,
        pattern = sqlLikePattern(query),
        tag = null,
        tagPrefix = "",
        favoriteOnly = 0,
        recentSince = 0,
        folder = null,
        folderPrefix = ""
    )

    private fun text(docId: Long, body: String) = DocText(
        docId = docId,
        text = body,
        source = "pdf",
        charCount = body.length,
        extractedTime = 1
    )

    @Test
    fun `a term inside the text finds the document`() = runBlocking {
        val certificado = doc("UNIP/certificado.pdf")
        val outro = doc("UNIP/contrato.pdf")
        val certificadoId = database.docDao().insert(certificado)
        database.docDao().insert(outro)
        database.docTextDao().put(
            text(certificadoId, "Declaramos que o aluno concluiu a carga horária de extensão")
        )

        val found = search("carga horária").first()

        assertEquals(listOf("certificado.pdf"), found.map { it.name })
    }

    @Test
    fun `the name still matches without any text`() = runBlocking {
        database.docDao().insert(doc("notas/faculdade.pdf"))

        assertEquals(listOf("faculdade.pdf"), search("faculdade").first().map { it.name })
    }

    @Test
    fun `name and text matches come together, once each`() = runBlocking {
        val a = doc("extensao.pdf")
        val b = doc("outro.pdf")
        val aId = database.docDao().insert(a)
        val bId = database.docDao().insert(b)
        database.docTextDao().put(text(aId, "projeto de extensao universitária"))
        database.docTextDao().put(text(bId, "também cita extensão no corpo"))

        val found = search("extens").first()

        // Both match; `a` matches twice (name and text) and must not be duplicated.
        assertEquals(listOf("extensao.pdf", "outro.pdf"), found.map { it.name }.sorted())
    }

    @Test
    fun `wildcards in the query stay literal, even in the text`() = runBlocking {
        val a = doc("a.pdf")
        val b = doc("b.pdf")
        val aId = database.docDao().insert(a)
        val bId = database.docDao().insert(b)
        database.docTextDao().put(text(aId, "desconto de 50% no curso"))
        database.docTextDao().put(text(bId, "desconto de 50 reais no curso"))

        val found = search("50%").first()

        assertEquals(listOf("a.pdf"), found.map { it.name })
    }

    @Test
    fun `capitalization does not matter`() = runBlocking {
        val a = database.docDao().insert(doc("a.pdf"))
        // LIKE is case-insensitive for ASCII; accented letters keep their case,
        // which is why the app never lowercases the stored text.
        database.docTextDao().put(text(a, "DECLARACAO DE MATRICULA"))

        assertTrue(search("declaracao").first().isNotEmpty())
    }
}
