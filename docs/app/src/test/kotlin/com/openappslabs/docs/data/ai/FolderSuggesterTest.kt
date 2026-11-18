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
import com.openappslabs.docs.data.model.DocText
import com.openappslabs.docs.data.move.FolderPath
import com.openappslabs.docs.data.source.DocsDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FolderSuggesterTest {

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

    private suspend fun addDoc(relPath: String): Doc {
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

    private suspend fun addText(docId: Long, body: String) = database.docTextDao().put(
        DocText(docId = docId, text = body, source = "pdf", charCount = body.length, extractedTime = 1)
    )

    private fun suggester(
        model: TagModel?,
        folderSource: (suspend () -> List<String>)? = null,
        descriptionSource: (suspend () -> Map<String, String>)? = null
    ) = FolderSuggester(
        textDao = database.docTextDao(),
        dao = database.docDao(),
        modelSource = { model },
        folderSource = folderSource,
        descriptionSource = descriptionSource
    )

    @Test
    fun `the model answer becomes the folder suggestion`() = runBlocking {
        val doc = addDoc("extensao.pdf")
        addDoc("academico/matricula.pdf")
        addText(doc.id, "declaração de conclusão de curso")

        val result = suggester(fakeModel(listOf("academico", "faculdade/2026"))).suggest(doc)

        assertTrue(result.usedModel)
        assertFalse(result.modelUnavailable)
        assertEquals(listOf("academico", "faculdade/2026"), result.suggestions.map { it.target })
        assertFalse(result.suggestions[0].isNew)
        assertTrue(result.suggestions[1].isNew)
        assertTrue(result.suggestions.all { it.fromModel })
        assertTrue(promptSeen!!.contains("declaração de conclusão"))
        assertTrue(promptSeen!!.contains("academico"))
    }

    @Test
    fun `the folder the document is already in is not suggested`() = runBlocking {
        val doc = addDoc("academico/extensao.pdf")
        addDoc("pessoal/receitas.pdf")

        val result = suggester(fakeModel(listOf("academico", "pessoal"))).suggest(doc)

        assertEquals(listOf("pessoal"), result.suggestions.map { it.target })
    }

    @Test
    fun `the model answer is re-cased to the folder that exists`() = runBlocking {
        val doc = addDoc("extensao.pdf")
        addDoc("Academico/matricula.pdf")

        val result = suggester(fakeModel(listOf("academico"))).suggest(doc)

        assertEquals(listOf("Academico"), result.suggestions.map { it.target })
        assertFalse(result.suggestions.single().isNew)
    }

    @Test
    fun `the model may send the document back to the root`() = runBlocking {
        val doc = addDoc("academico/extensao.pdf")

        val result = suggester(fakeModel(listOf("raiz"))).suggest(doc)

        assertEquals(listOf(FolderPath.ROOT), result.suggestions.map { it.target })
        assertTrue(result.suggestions.single().target.isEmpty())
        assertFalse(result.suggestions.single().isNew)
    }

    @Test
    fun `an offline model still suggests the folders that exist`() = runBlocking {
        val doc = addDoc("extensao-universitaria.pdf")
        addDoc("academico/extensao/declaracao.pdf")
        addText(doc.id, "carga horária de extensão")

        val result = suggester(fakeModel(emptyList(), ModelTrouble(ModelIssue.UNREACHABLE))).suggest(doc)

        assertFalse(result.usedModel)
        assertTrue(result.modelUnavailable)
        assertEquals("academico/extensao", result.suggestions.first().target)
        assertFalse(result.suggestions.first().fromModel)
        // Nothing else existed, so a folder that does not exist yet follows.
        assertTrue(result.suggestions.drop(1).all { it.isNew && !it.fromModel })
    }

    @Test
    fun `without a model the text alone is used`() = runBlocking {
        val doc = addDoc("conclusao.pdf")
        addDoc("academico/extensao/declaracao.pdf")
        addText(doc.id, "projeto de extensão")

        val result = suggester(null).suggest(doc)

        assertFalse(result.usedModel)
        assertFalse(result.modelUnavailable)
        assertEquals("academico/extensao", result.suggestions.first().target)
        assertFalse(result.suggestions.first().isNew)
    }

    @Test
    fun `an unusable model answer falls back instead of proposing rubbish`() = runBlocking {
        val doc = addDoc("extensao.pdf")
        addDoc("academico/extensao/declaracao.pdf")

        val result = suggester(fakeModel(listOf("../../fora", ".git", "nenhuma"))).suggest(doc)

        assertFalse(result.usedModel)
        assertEquals(listOf("academico/extensao"), result.suggestions.map { it.target })
    }

    @Test
    fun `an empty library has nothing to offer and says nothing about the model`() = runBlocking {
        val doc = addDoc("a.pdf")

        val result = suggester(fakeModel(emptyList())).suggest(doc)

        assertTrue(result.isEmpty)
        assertFalse(result.modelUnavailable)
    }

    @Test
    fun `an empty folder on disk counts as existing`() = runBlocking {
        // `UNIP` has no document in it, so the index knows nothing about it; the
        // disk does, and proposing it as new would be wrong.
        val doc = addDoc("init/extensao.pdf")

        val result = suggester(
            fakeModel(listOf("UNIP")),
            folderSource = { listOf("init", "UNIP", "UNIP/2026") }
        ).suggest(doc)

        assertEquals(listOf("UNIP"), result.suggestions.map { it.target })
        assertFalse(result.suggestions.single().isNew)
    }

    @Test
    fun `the model is shown the folders that exist on disk`() = runBlocking {
        val doc = addDoc("init/extensao.pdf")

        suggester(
            fakeModel(listOf("UNIP")),
            folderSource = { listOf("UNIP", "Provas/2026") }
        ).suggest(doc)

        assertTrue(promptSeen!!.contains("- Provas/2026"))
        assertTrue(promptSeen!!.contains("proponha uma pasta nova"))
    }

    @Test
    fun `the model is shown what the user said each folder is for`() = runBlocking {
        val doc = addDoc("init/extensao.pdf")

        suggester(
            fakeModel(listOf("UNIP/Certificados")),
            folderSource = { listOf("UNIP/Certificados", "UNIP/Original") },
            descriptionSource = {
                mapOf("UNIP/Certificados" to "certificados de cursos, com carga horária")
            }
        ).suggest(doc)

        assertTrue(
            promptSeen!!.contains(
                "- UNIP/Certificados — vazia, sem documento indexado" +
                    " — descrição: certificados de cursos, com carga horária"
            )
        )
        // The folder without a note is listed as before, with no empty label.
        assertTrue(promptSeen!!.contains("- UNIP/Original — vazia, sem documento indexado\n"))
        assertTrue(promptSeen!!.contains("siga essa descrição acima do nome da pasta"))
    }

    @Test
    fun `without a model the description points at the right folder`() = runBlocking {
        // Neither sibling leaf appears in the file name: the note is the only
        // thing that tells them apart, which is exactly why it exists.
        val doc = addDoc("init/certificado.pdf")
        addText(doc.id, "declaração de conclusão de curso")

        val result = suggester(
            model = null,
            folderSource = { listOf("UNIP/Certificados", "UNIP/Original") },
            descriptionSource = {
                mapOf("UNIP/Certificados" to "certificados e declarações de conclusão")
            }
        ).suggest(doc)

        assertEquals("UNIP/Certificados", result.suggestions.first().target)
        assertEquals(
            "a descrição da pasta cita o nome do arquivo",
            result.suggestions.first().reason
        )
    }

    @Test
    fun `the model is shown what each folder holds`() = runBlocking {
        val doc = addDoc("init/extensao.pdf")
        addDoc("UNIP/Certificados/extensao_1.pdf")
        addDoc("UNIP/Original/contrato.pdf")

        suggester(
            fakeModel(listOf("UNIP/Certificados")),
            folderSource = { listOf("UNIP/Certificados", "UNIP/Original", "Geral") }
        ).suggest(doc)

        // The leaf `Certificados` and the leaf `Original` are equally plausible
        // names; the contents are what tell them apart.
        assertTrue(promptSeen!!.contains("- UNIP/Certificados — 1 documento: extensao_1.pdf"))
        assertTrue(promptSeen!!.contains("- UNIP/Original — 1 documento: contrato.pdf"))
        assertTrue(promptSeen!!.contains("- Geral — vazia, sem documento indexado"))
    }

    @Test
    fun `a model that answers the current folder keeps that answer`() = runBlocking {
        // The model says the document is already where it belongs. The text
        // heuristic would otherwise point at the sibling `Original`, because
        // the file name repeats the parent word `extensao`.
        val doc = addDoc("UNIP/Certificados/extensao.pdf")
        addDoc("UNIP/Original/certificado.pdf")

        val result = suggester(
            fakeModel(listOf("UNIP/Certificados")),
            folderSource = { listOf("UNIP/Certificados", "UNIP/Original") }
        ).suggest(doc)

        assertTrue(result.usedModel)
        assertTrue(result.staysHere)
        assertTrue(result.suggestions.none { it.target == "UNIP/Original" })
        // "Already organized" does not mean "nothing to create".
        assertEquals(
            listOf("UNIP/Certificados/extensao"),
            result.suggestions.map { it.target }
        )
        assertTrue(result.suggestions.single().isNew)
    }

    @Test
    fun `without any folder a new one is proposed from the name`() = runBlocking {
        val doc = addDoc("init/Contrato de aluguel.pdf")

        val result = suggester(null).suggest(doc)

        assertEquals(listOf("Contrato", "aluguel"), result.suggestions.map { it.target })
        assertTrue(result.suggestions.all { it.isNew })
        assertTrue(result.suggestions.none { it.fromModel })
    }

    @Test
    fun `a new folder is proposed below the folder the document is in`() = runBlocking {
        val doc = addDoc("UNIP/relatorio-final.pdf")

        val result = suggester(null, folderSource = { listOf("UNIP") }).suggest(doc)

        assertEquals(listOf("UNIP/relatorio"), result.suggestions.map { it.target })
        assertTrue(result.suggestions.single().isNew)
    }

    @Test
    fun `the prompt carries the type, the size and the dates`() {
        val doc = Doc(
            relPath = "init/extensao_1.pdf",
            name = "extensao_1.pdf",
            kind = DocKind.PDF,
            size = 1,
            modifiedTime = 1
        )
        val prompt = FolderPrompt.build(
            doc = doc,
            text = "",
            existing = listOf("UNIP"),
            facts = DocFacts(kind = "PDF", size = 67_477, modifiedTime = 1_794_617_976_000L)
        )

        assertTrue("Tipo: PDF · 67,5 KB" in prompt)
        assertTrue("Modificado no disco em: " in prompt)
        // With no text, the metadata is what the model has to work with, and
        // the prompt says so instead of pretending there is content.
        assertTrue("não tem texto legível" in prompt)
        assertTrue("não do conteúdo" in prompt)
        assertTrue("Não proponha pasta que seja só uma data" in prompt)
    }

    @Test
    fun `a document never read is read when the folder is asked for`() = runBlocking {
        val doc = addDoc("init/extensao.pdf")
        addDoc("UNIP/Certificados/prova.pdf")

        FolderSuggester(
            textDao = database.docTextDao(),
            dao = database.docDao(),
            modelSource = { fakeModel(listOf("UNIP/Certificados")) },
            folderSource = { listOf("UNIP/Certificados") },
            textSource = { "declaração de conclusão de curso de extensão" }
        ).suggest(doc)

        assertTrue(promptSeen!!.contains("declaração de conclusão de curso de extensão"))
    }

    @Test
    fun `the suggestion sends the type, the size and the dates`() = runBlocking {
        val doc = addDoc("init/extensao.pdf")
        addDoc("UNIP/prova.pdf")

        FolderSuggester(
            textDao = database.docTextDao(),
            dao = database.docDao(),
            modelSource = { fakeModel(listOf("UNIP")) },
            folderSource = { listOf("UNIP") },
            createdTimeSource = { 1_773_000_000_000L }
        ).suggest(doc)

        assertTrue(promptSeen!!.contains("Tipo: PDF"))
        assertTrue(promptSeen!!.contains("Criado no disco em: 08/03/2026"))
    }

    @Test
    fun `a model that fails reports the reason next to the suggestions`() = runBlocking {
        val doc = addDoc("extensao.pdf")
        addDoc("academico/extensao/declaracao.pdf")
        addText(doc.id, "carga horária de extensão")

        val result = suggester(
            fakeModel(
                emptyList(),
                ModelTrouble(ModelIssue.MODEL_REFUSED, status = 400, model = "deepseek-flash")
            )
        ).suggest(doc)

        assertEquals(ModelIssue.MODEL_REFUSED, result.trouble?.issue)
        assertEquals(400, result.trouble?.status)
        assertEquals(listOf("academico/extensao"), result.suggestions.map { it.target })
    }
}
