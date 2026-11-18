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

package com.openappslabs.docs.data.ai

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.openappslabs.docs.data.model.Doc
import com.openappslabs.docs.data.model.DocKind
import com.openappslabs.docs.data.model.DocTag
import com.openappslabs.docs.data.model.DocText
import com.openappslabs.docs.data.model.Tag
import com.openappslabs.docs.data.source.DocsDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TagSuggesterTest {

    private lateinit var database: DocsDatabase
    private var promptSeen: String? = null

    private fun fakeModel(answer: List<String>, trouble: ModelTrouble? = null) = object : TagModel {
        override suspend fun isAvailable() = trouble == null
        override suspend fun suggest(prompt: String): List<String> {
            promptSeen = prompt
            return answer
        }

        override fun trouble() = trouble
    }

    @BeforeTest
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<DocsDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    private suspend fun addDoc(relPath: String = "UNIP/extensao.pdf"): Doc {
        val id = database.docDao().insert(
            Doc(
                relPath = relPath,
                name = relPath.substringAfterLast('/'),
                kind = DocKind.PDF,
                size = 1,
                modifiedTime = 1
            )
        )
        return database.docDao().all().first { it.id == id }
    }

    private suspend fun addTag(path: String): Tag {
        database.tagDao().insert(Tag(path = path))
        return database.tagDao().findByPath(path)!!
    }

    /** Inserts a tag that already carries the sentence the user wrote about it. */
    private suspend fun addDescribedTag(path: String, description: String): Tag {
        database.tagDao().insert(Tag(path = path, description = description))
        return database.tagDao().findByPath(path)!!
    }

    private suspend fun addText(docId: Long, body: String) = database.docTextDao().put(
        DocText(docId = docId, text = body, source = "pdf", charCount = body.length, extractedTime = 1)
    )

    private fun suggester(model: TagModel?) =
        TagSuggester(database.docTextDao(), database.tagDao(), model)

    @Test
    fun `year and month tags are shown so the model can reuse them`() = runBlocking {
        // The library keeps `2018`…`2027` and `mes-1`…`mes-12` as separate tags.
        // Hiding them made every suggestion invent `2025/11` as new.
        val doc = addDoc()
        addTag("extensao")
        addTag("mes-11")
        addTag("2026")
        addText(doc.id, "comprovante de atividade de extensão 2026 - 2º semestre")

        val result = suggester(fakeModel(listOf("mes-11", "extensao"))).suggest(doc)

        assertTrue(promptSeen!!.contains("- mes-11"))
        assertTrue(promptSeen!!.contains("- 2026"))
        // November is not the document's month (2º semestre, no day/month), so
        // `mes-11` from the model is still refused — only reuse when the text
        // backs the month.
        assertEquals(listOf("extensao", "2026"), result.suggestions.map { it.path })
        assertTrue(result.suggestions.none { it.path == "mes-11" })
    }

    @Test
    fun `a date the text does not mention is dropped from the answer`() = runBlocking {
        // `13/11/2026` is the copy date, and the model reaches for it. The text
        // is about the second semester of 2026, so November is not supported.
        val doc = addDoc()
        addTag("extensao")
        addText(doc.id, "comprovante de atividade de extensão 2026 - 2º semestre")

        val result = suggester(fakeModel(listOf("2026/mes-11", "extensao"))).suggest(doc)

        // The document date (2º semestre) is offered; November from the model is not.
        assertEquals(listOf("extensao", "2026/semestre-2"), result.suggestions.map { it.path })
        assertTrue(result.suggestions.none { it.path == "2026/mes-11" })
    }

    @Test
    fun `a date the document declares is kept`() = runBlocking {
        val doc = addDoc()
        addTag("extensao")
        addText(doc.id, "Comprovante de envio. Entrega: 13/11/2026 às 21:58.")

        val result = suggester(fakeModel(listOf("2026/mes-11", "extensao"))).suggest(doc)

        // Existing matches come first; the date the model (or the text) named
        // still appears.
        assertEquals(listOf("extensao", "2026/mes-11"), result.suggestions.map { it.path })
    }

    @Test
    fun `existing tags still appear when the model invents a date`() = runBlocking {
        // The bug: the model answered only `2025/11`, marked new, and the
        // dialog hid every tag the library already had. Reuse has to win —
        // and November from the upload must not override the semester.
        val doc = addDoc("init/extensao_1_sem_2.pdf")
        addTag("extensao")
        addTag("certificado")
        addTag("2025")
        addText(
            doc.id,
            """
            Comprovante de envio
            Atividade de Extensão
            Data da atividade: 2025 - 2º Semestre
            Entrega
            Data e hora: 10/11/2025
            """.trimIndent()
        )

        val result = suggester(fakeModel(listOf("2025/11"))).suggest(doc)

        assertEquals("extensao", result.suggestions.first().path)
        assertFalse(result.suggestions.first().isNew)
        assertTrue(result.suggestions.any { it.path == "2025" && !it.isNew })
        assertTrue(result.suggestions.none { it.path == "2025/11" })
        assertTrue(result.suggestions.none { it.path == "mes-11" || it.path == "2025/mes-11" })
    }

    @Test
    fun `separate year and month tags are reused instead of inventing a compound`() =
        runBlocking {
            val doc = addDoc("init/extensao_1_sem_2.pdf")
            addTag("extensao")
            addTag("2025")
            addTag("mes-11")
            addText(doc.id, "Comprovante de extensão. Entrega: 10/11/2025.")

            val result = suggester(fakeModel(listOf("2025/11"))).suggest(doc)

            assertTrue(result.suggestions.any { it.path == "2025" && !it.isNew })
            assertTrue(result.suggestions.any { it.path == "mes-11" && !it.isNew })
            assertTrue(result.suggestions.none { it.path == "2025/mes-11" })
            assertTrue(result.suggestions.none { it.path == "2025/11" })
        }

    @Test
    fun `a model date that already exists is not marked new`() = runBlocking {
        val doc = addDoc()
        addTag("2025/mes-11")
        addTag("extensao")
        addText(doc.id, "Comprovante de extensão. Entrega: 10/11/2025.")

        val result = suggester(fakeModel(listOf("2025/11", "extensao"))).suggest(doc)

        val date = result.suggestions.first { it.path == "2025/mes-11" }
        assertFalse(date.isNew)
        assertTrue(result.suggestions.any { it.path == "extensao" && !it.isNew })
    }

    @Test
    fun `without a model the document date and its type are proposed`() = runBlocking {
        // The dialog used to fall back to form words like `atividade` and
        // `aluno`. What makes a document findable is the subject, the kind of
        // document and the date it is about.
        val doc = addDoc("init/extensao_1_sem_4.pdf")
        addText(
            doc.id,
            """
            Trabalhos Acadêmicos
            Comprovante de envio
            Aluno: JONATHAN
            Atividade: Atividade de Extensão
            Data da atividade: 2026 - 2º Semestre
            Entrega
            Data e hora: 13/11/2026 às 21:58
            """.trimIndent()
        )

        val result = suggester(model = null).suggest(doc)

        // Period of the activity, not "Data e hora" of the upload.
        assertEquals(
            listOf("2026/semestre-2", "comprovante", "extensao"),
            result.suggestions.map { it.path }
        )
        assertTrue(result.suggestions.all { it.isNew })
        assertTrue(result.suggestions.none { it.path in setOf("aluno", "atividade", "data", "mes-11") })
    }

    @Test
    fun `the heuristic does not fall back to a date tag`() = runBlocking {
        val doc = addDoc()
        addTag("mes-11")
        addText(doc.id, "comprovante de atividade de extensão")

        // No model: the word list must not pick the month either.
        val result = suggester(null).suggest(doc)

        assertTrue(result.suggestions.none { it.path == "mes-11" })
    }

    @Test
    fun `the model answer becomes the suggestion`() = runBlocking {
        val doc = addDoc()
        addTag("academico/extensao")
        addText(doc.id, "declaração de conclusão")

        val result = suggester(fakeModel(listOf("academico/extensao", "unip/2026")))
            .suggest(doc)

        assertTrue(result.usedModel)
        assertFalse(result.modelUnavailable)
        // `academico/extensao` also matches the file name, so the heuristic
        // claims it first; the model still contributes `unip/2026`.
        assertEquals(listOf("academico/extensao", "unip/2026"), result.suggestions.map { it.path })
        assertFalse(result.suggestions.first().fromModel)
        assertTrue(result.suggestions.first { it.path == "unip/2026" }.fromModel)
        assertTrue(promptSeen!!.contains("declaração de conclusão"))
    }

    @Test
    fun `the model is shown what the user said each tag means`() = runBlocking {
        val doc = addDoc()
        addTag("unip")
        addDescribedTag("academico", "tudo que é da faculdade, de qualquer semestre")
        addText(doc.id, "matrícula assinada")

        suggester(fakeModel(listOf("unip"))).suggest(doc)

        assertTrue(
            promptSeen!!.contains(
                "- academico — descrição: tudo que é da faculdade, de qualquer semestre"
            )
        )
    }

    @Test
    fun `without a model the description points at the right tag`() = runBlocking {
        // The file name says `aluguel`; neither tag repeats it. Only the
        // sentence about `moradia` tells it apart from `pessoal`.
        val doc = addDoc("init/aluguel.pdf")
        addTag("pessoal")
        addDescribedTag("moradia", "aluguel, condomínio e contas da casa")
        addText(doc.id, "recibo do aluguel do mês")

        val result = suggester(model = null).suggest(doc)

        assertEquals("moradia", result.suggestions.first().path)
        assertEquals(
            "a descrição da etiqueta cita o nome do arquivo",
            result.suggestions.first().reason
        )
    }

    @Test
    fun `a document never read is read when the suggestion is asked for`() = runBlocking {
        // No row in doc_text: the background batch has not reached this file
        // yet. Without an on-demand read the model would get "no text" and look
        // like it never opened the file.
        val doc = addDoc()
        addTag("academico/extensao")

        val result = TagSuggester(
            textDao = database.docTextDao(),
            tagDao = database.tagDao(),
            modelSource = { fakeModel(listOf("academico/extensao")) },
            textSource = { "declaração de conclusão de curso de extensão" }
        ).suggest(doc)

        assertTrue(promptSeen!!.contains("declaração de conclusão de curso de extensão"))
        assertTrue(result.usedModel)
    }

    @Test
    fun `a document read as empty is not read again`() = runBlocking {
        // A row with no words is an answer: the file was read and has nothing
        // inside. Re-running OCR at every suggestion would be worse than
        // answering from the name.
        val doc = addDoc()
        addTag("academico")
        addText(doc.id, "")
        var reads = 0

        TagSuggester(
            textDao = database.docTextDao(),
            tagDao = database.tagDao(),
            modelSource = { fakeModel(listOf("academico")) },
            textSource = {
                reads += 1
                "conteúdo que veio do disco"
            }
        ).suggest(doc)

        assertEquals(0, reads)
        assertTrue(promptSeen!!.contains("não tem texto legível"))
    }

    @Test
    fun `the prompt carries the type, the size and the dates`() = runBlocking {
        val doc = addDoc()
        addTag("academico")

        TagSuggester(
            textDao = database.docTextDao(),
            tagDao = database.tagDao(),
            modelSource = { fakeModel(listOf("academico")) },
            createdTimeSource = { 1_773_000_000_000L }
        ).suggest(doc)

        assertTrue(promptSeen!!.contains("Tipo: PDF"))
        assertTrue(promptSeen!!.contains("Criado no disco em: 08/03/2026"))
    }

    @Test
    fun `a tag already applied is not suggested again`() = runBlocking {
        val doc = addDoc()
        val tag = addTag("academico/extensao")
        database.tagDao().assign(DocTag(doc.id, tag.id))

        val result = suggester(fakeModel(listOf("academico/extensao", "pessoal")))
            .suggest(doc)

        assertEquals(listOf("pessoal"), result.suggestions.map { it.path })
    }

    @Test
    fun `without a model the text and the existing tags are enough`() = runBlocking {
        val doc = addDoc()
        addTag("academico/extensao")
        addText(doc.id, "carga horária de extensão")

        val result = suggester(model = null).suggest(doc)

        assertFalse(result.usedModel)
        assertFalse(result.modelUnavailable)
        assertEquals(listOf("academico/extensao"), result.suggestions.map { it.path })
    }

    @Test
    fun `an offline model falls back to the text and says so`() = runBlocking {
        val doc = addDoc()
        addTag("academico/extensao")
        addText(doc.id, "carga horária de extensão")

        val result = suggester(fakeModel(emptyList(), ModelTrouble(ModelIssue.UNREACHABLE))).suggest(doc)

        assertFalse(result.usedModel)
        assertTrue(result.modelUnavailable)
        assertEquals(ModelIssue.UNREACHABLE, result.trouble?.issue)
        assertEquals(listOf("academico/extensao"), result.suggestions.map { it.path })
    }

    @Test
    fun `an empty model answer also falls back`() = runBlocking {
        val doc = addDoc()
        addTag("academico/extensao")
        addText(doc.id, "carga horária de extensão")

        val result = suggester(fakeModel(listOf("nenhuma"))).suggest(doc)

        assertFalse(result.usedModel)
        assertEquals(listOf("academico/extensao"), result.suggestions.map { it.path })
    }

    @Test
    fun `the model cannot sneak in an invalid tag`() = runBlocking {
        val doc = addDoc()
        addText(doc.id, "texto")

        val result = suggester(fakeModel(listOf("a/b/c/d/e/f", "", "   ", "ok"))).suggest(doc)

        assertEquals(listOf("ok"), result.suggestions.map { it.path })
    }

    @Test
    fun `at most three suggestions`() = runBlocking {
        val doc = addDoc()
        addText(doc.id, "texto")

        val result = suggester(
            fakeModel(listOf("a", "b", "c", "d", "e"))
        ).suggest(doc)

        assertEquals(listOf("a", "b", "c"), result.suggestions.map { it.path })
    }

    @Test
    fun `a failure inside the model does not break the fallback`() = runBlocking {
        val doc = addDoc()
        addTag("academico/extensao")
        addText(doc.id, "extensão")
        val exploding = object : TagModel {
            override suspend fun isAvailable(): Boolean = throw IllegalStateException("sem rede")
            override suspend fun suggest(prompt: String) = emptyList<String>()
        }

        val result = suggester(exploding).suggest(doc)

        assertEquals(listOf("academico/extensao"), result.suggestions.map { it.path })
    }

    @Test
    fun `a tag that does not exist yet is marked as new`() = runBlocking {
        val doc = addDoc()
        addTag("academico/extensao")
        addText(doc.id, "declaração")

        val result = suggester(fakeModel(listOf("academico/extensao", "unip/2026")))
            .suggest(doc)

        // The inbox greys out exactly these: applying them would create a tag.
        assertEquals(listOf(false, true), result.suggestions.map { it.isNew })
    }

    @Test
    fun `the prompt shows what each tag is used for`() = runBlocking {
        val doc = addDoc("init/curriculo.pdf")
        val tag = addTag("unip")
        val other = addDoc("UNIP/extensao_1.pdf")
        database.tagDao().assign(DocTag(other.id, tag.id))
        addText(doc.id, "currículo atualizado")

        suggester(fakeModel(listOf("unip"))).suggest(doc)

        // A bare list of paths does not tell the model that `unip` covers
        // certificates; the example does.
        assertTrue(promptSeen!!.contains("unip — usada em: extensao_1.pdf"))
    }

    @Test
    fun `a model that only repeats applied tags says so`() = runBlocking {
        val doc = addDoc("init/contrato.pdf")
        val tag = addTag("unip")
        database.tagDao().assign(DocTag(doc.id, tag.id))
        addText(doc.id, "texto")

        val result = suggester(fakeModel(listOf("unip"))).suggest(doc)

        assertTrue(result.onlyApplied)
        // The fallback still offers something to accept — the new tag from the
        // file name — instead of an empty dialog.
        assertTrue(result.suggestions.any { it.isNew })
    }

    @Test
    fun `the text fallback only proposes tags that already exist, so none is new`() = runBlocking {
        val doc = addDoc()
        addTag("academico/extensao")
        addText(doc.id, "carga horária de extensão")

        val result = suggester(model = null).suggest(doc)

        assertTrue(result.suggestions.isNotEmpty())
        assertTrue(result.suggestions.none { it.isNew })
    }

    @Test
    fun `without any tag new ones are proposed from the name`() = runBlocking {
        val doc = addDoc("init/Contrato de aluguel 2026.pdf")

        val result = suggester(model = null).suggest(doc)

        assertEquals(listOf("contrato", "aluguel"), result.suggestions.map { it.path })
        assertTrue(result.suggestions.all { it.isNew })
        assertFalse(result.usedModel)
    }

    @Test
    fun `a word already used by a tag is not proposed again`() = runBlocking {
        val doc = addDoc("extensao.pdf")
        addTag("academico/extensao")

        val result = suggester(model = null).suggest(doc)

        assertEquals(listOf("academico/extensao"), result.suggestions.map { it.path })
        assertTrue(result.suggestions.none { it.isNew })
    }

    @Test
    fun `a word repeated in the text becomes a proposed tag`() = runBlocking {
        val doc = addDoc("init/scan.pdf")
        addText(doc.id, "aluguel pago. aluguel do mês seguinte.")

        val result = suggester(model = null).suggest(doc)

        assertEquals(listOf("aluguel"), result.suggestions.map { it.path })
        assertTrue(result.suggestions.single().isNew)
    }

    @Test
    fun `a model that fails reports the reason`() = runBlocking {
        val doc = addDoc("init/extensao.pdf")

        val result = suggester(
            fakeModel(
                emptyList(),
                ModelTrouble(ModelIssue.MODEL_REFUSED, status = 400, model = "deepseek-flash")
            )
        ).suggest(doc)

        assertEquals(ModelIssue.MODEL_REFUSED, result.trouble?.issue)
        assertEquals(400, result.trouble?.status)
        assertEquals("deepseek-flash", result.trouble?.model)
    }

    @Test
    fun `a polite refusal is not a model failure`() = runBlocking {
        val doc = addDoc("init/extensao.pdf")

        val result = suggester(fakeModel(listOf("nenhuma"))).suggest(doc)

        assertEquals(null, result.trouble)
    }
}
