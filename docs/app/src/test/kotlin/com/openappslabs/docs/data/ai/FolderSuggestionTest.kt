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

import com.openappslabs.docs.data.model.Doc
import com.openappslabs.docs.data.model.DocKind
import com.openappslabs.docs.data.move.FolderPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeuristicFoldersTest {

    private fun doc(relPath: String) = Doc(
        relPath = relPath,
        name = relPath.substringAfterLast('/'),
        kind = DocKind.PDF,
        size = 1,
        modifiedTime = 1
    )

    @Test
    fun `an existing folder with a word of the name is suggested`() {
        val suggestions = HeuristicFolders.suggest(
            doc = doc("extensao-universitaria.pdf"),
            text = "declaração de conclusão",
            existing = listOf("academico/extensao", "pessoal/receitas")
        )

        assertEquals(listOf("academico/extensao"), suggestions.map { it.target })
        assertEquals("o nome do arquivo cita essa pasta", suggestions.single().reason)
        assertFalse(suggestions.single().isNew)
        assertFalse(suggestions.single().fromModel)
    }

    @Test
    fun `a word only in the text still points at the folder`() {
        val suggestions = HeuristicFolders.suggest(
            doc = doc("documento-final.pdf"),
            text = "relatório do estágio supervisionado",
            existing = listOf("unip/estagio")
        )

        assertEquals(listOf("unip/estagio"), suggestions.map { it.target })
        assertEquals("o texto cita essa pasta", suggestions.single().reason)
    }

    @Test
    fun `a sibling that only shares a parent word is not suggested`() {
        // The certificate is inside `Certificados`. `Original` is a sibling
        // whose leaf has nothing to do with the file, and `Extensao` is above
        // it: neither is a place this document belongs.
        val suggestions = HeuristicFolders.suggest(
            doc = doc("Extensao/Certificados/extensao_1_sem_2.pdf"),
            text = "certificado de extensão universitária",
            existing = listOf("Extensao", "Extensao/Certificados", "Extensao/Original")
        )

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `a parent of the current folder is never suggested`() {
        val suggestions = HeuristicFolders.suggest(
            doc = doc("academico/2026/matricula.pdf"),
            text = "matrícula assinada",
            existing = listOf("academico", "academico/2026")
        )

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `the current folder is never suggested`() {
        val suggestions = HeuristicFolders.suggest(
            doc = doc("academico/extensao.pdf"),
            text = "extensão universitária",
            existing = listOf("academico")
        )

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `accents and case do not matter`() {
        val suggestions = HeuristicFolders.suggest(
            doc = doc("MATRICULA.pdf"),
            text = "",
            existing = listOf("Academico/Matrícula")
        )

        assertEquals(listOf("Academico/Matrícula"), suggestions.map { it.target })
    }

    @Test
    fun `a deeper segment weighs more than its parent`() {
        val suggestions = HeuristicFolders.suggest(
            doc = doc("2026.pdf"),
            text = "",
            existing = listOf("faculdade/2026", "faculdade/2025")
        )

        assertEquals(listOf("faculdade/2026"), suggestions.map { it.target })
    }

    @Test
    fun `without folders there is nothing to suggest`() {
        assertTrue(HeuristicFolders.suggest(doc("a.pdf"), "texto qualquer", emptyList()).isEmpty())
    }

    @Test
    fun `a folder whose description names the document is suggested first`() {
        // Neither leaf says anything about the file: only the sentence the user
        // wrote tells `Extensao/B` apart from `Extensao/A`.
        val suggestions = HeuristicFolders.suggest(
            doc = doc("certificado.pdf"),
            text = "declaração de conclusão",
            existing = listOf("Extensao/A", "Extensao/B"),
            descriptions = mapOf("Extensao/B" to "certificados de cursos de extensão")
        )

        assertEquals(listOf("Extensao/B"), suggestions.map { it.target })
        assertEquals("a descrição da pasta cita o nome do arquivo", suggestions.single().reason)
    }

    @Test
    fun `a description that repeats only a common word does not make a folder a candidate`() {
        val suggestions = HeuristicFolders.suggest(
            doc = doc("a.pdf"),
            text = "texto curto para dos das",
            existing = listOf("financas/impostos"),
            descriptions = mapOf("financas/impostos" to "para dos das")
        )

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `a description outranks a weaker folder name match`() {
        val suggestions = HeuristicFolders.suggest(
            doc = doc("certificado.pdf"),
            text = "declaração emitida pela universidade",
            existing = listOf("Geral/universidade", "UNIP/certificados"),
            descriptions = mapOf("UNIP/certificados" to "certificados assinados e digitalizados")
        )

        // `universidade` repeats a word of the text; the description is the
        // user's definition of the other folder and wins.
        assertEquals("UNIP/certificados", suggestions.first().target)
    }

    @Test
    fun `a folder with no word in the document is not suggested`() {
        val suggestions = HeuristicFolders.suggest(
            doc = doc("a.pdf"),
            text = "texto curto",
            existing = listOf("financas/impostos")
        )

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `the list respects the limit`() {
        val existing = listOf("a/extensao", "b/extensao", "c/extensao", "d/extensao")

        val suggestions = HeuristicFolders.suggest(
            doc = doc("extensao.pdf"),
            text = "extensao",
            existing = existing,
            limit = 2
        )

        assertEquals(2, suggestions.size)
    }

    @Test
    fun `a new folder comes from the name, below the current folder`() {
        val suggestions = HeuristicFolders.proposeNew(
            doc = doc("UNIP/Contrato de aluguel.pdf"),
            existing = listOf("UNIP")
        )

        assertEquals(listOf("UNIP/Contrato", "UNIP/aluguel"), suggestions.map { it.target })
        assertTrue(suggestions.all { it.isNew })
        assertTrue(suggestions.none { it.fromModel })
    }

    @Test
    fun `a word that is already a folder is not proposed again`() {
        assertTrue(
            HeuristicFolders.proposeNew(doc("UNIP/UNIP.pdf"), listOf("UNIP")).isEmpty()
        )
    }

    @Test
    fun `type noise is never a folder`() {
        assertTrue(HeuristicFolders.proposeNew(doc("IMG_20260101.pdf"), emptyList()).isEmpty())
    }

    @Test
    fun `a document in the inbox proposes at the top of the library`() {
        val suggestions = HeuristicFolders.proposeNew(
            doc = doc("init/RELATORIO.pdf"),
            existing = emptyList()
        )

        assertEquals(listOf("RELATORIO"), suggestions.map { it.target })
    }

    @Test
    fun `a form label in the name is not a folder`() {
        // `matricula` is a field of the form, not a place to file the file.
        assertTrue(HeuristicFolders.proposeNew(doc("init/MATRICULA.pdf"), emptyList()).isEmpty())
    }
}

class FolderPromptTest {

    private fun doc(relPath: String) = Doc(
        relPath = relPath,
        name = relPath.substringAfterLast('/'),
        kind = DocKind.PDF,
        size = 1,
        modifiedTime = 1
    )

    @Test
    fun `the prompt names the file, the current folder and the excerpt`() {
        val prompt = FolderPrompt.build(
            doc = doc("UNIP/extensao.pdf"),
            text = "carga horária de extensão universitária",
            existing = listOf("academico", "pessoal")
        )

        assertTrue(prompt.contains("extensao.pdf"))
        assertTrue(prompt.contains("Pasta atual: UNIP"))
        assertTrue(prompt.contains("- academico"))
        assertTrue(prompt.contains("carga horária de extensão"))
        assertTrue(prompt.contains(FolderPrompt.ROOT_ANSWER))
    }

    @Test
    fun `a document at the root says so`() {
        val prompt = FolderPrompt.build(doc("a.pdf"), "", emptyList())

        assertTrue(prompt.contains("Pasta atual: ${FolderPrompt.ROOT_ANSWER}"))
        assertTrue(prompt.contains("não tem texto legível"))
    }

    @Test
    fun `the answer is cleaned down to paths`() {
        val parsed = FolderPrompt.parse(
            listOf(
                "- academico/2026",
                "* \"pessoal/financas\"",
                "faculdade — porque o texto fala de estágio",
                "academico/2026",
                ""
            )
        )

        assertEquals(listOf("academico/2026", "pessoal/financas", "faculdade"), parsed)
    }

    @Test
    fun `a trailing period does not become part of the folder`() {
        assertEquals(listOf("academico/2026"), FolderPrompt.parse(listOf("academico/2026.")))
        assertEquals(
            listOf("academico", "pessoal"),
            FolderPrompt.parse(listOf("**academico**, pessoal."))
        )
    }

    @Test
    fun `the prompt shows what each folder holds`() {
        val prompt = FolderPrompt.build(
            doc = doc("init/extensao.pdf"),
            text = "certificado de extensão",
            existing = listOf("UNIP/Certificados", "UNIP/Original", "Geral"),
            contents = mapOf(
                "UNIP/Certificados" to listOf("extensao_1.pdf", "extensao_2.pdf"),
                "UNIP/Original" to listOf("contrato.pdf")
            )
        )

        assertTrue("- UNIP/Certificados — 2 documentos: extensao_1.pdf, extensao_2.pdf" in prompt)
        assertTrue("- UNIP/Original — 1 documento: contrato.pdf" in prompt)
        assertTrue("- Geral — vazia, sem documento indexado" in prompt)
    }

    @Test
    fun `the root answer becomes the empty target`() {
        assertEquals(listOf(FolderPath.ROOT), FolderPrompt.parse(listOf("raiz")))
        assertEquals(listOf(FolderPath.ROOT), FolderPrompt.parse(listOf("ROOT")))
    }

    @Test
    fun `a refusal or an escape is dropped`() {
        assertEquals(emptyList(), FolderPrompt.parse(listOf("nenhuma")))
        assertEquals(emptyList(), FolderPrompt.parse(listOf("../fora")))
        assertEquals(emptyList(), FolderPrompt.parse(listOf(".git")))
        assertEquals(emptyList(), FolderPrompt.parse(listOf("")))
    }

    @Test
    fun `numbering is stripped before the path is read`() {
        val parsed = FolderPrompt.parse(listOf("1. academico/2026", "2) pessoal"))
        assertEquals(listOf("academico/2026", "pessoal"), parsed)
    }

    @Test
    fun `the numbering of a path is part of the path and stays`() {
        // `2026` is a legal folder name: only a list marker at the start goes.
        assertEquals(listOf("2026/extensao"), FolderPrompt.parse(listOf("2026/extensao")))
    }

    @Test
    fun `a document in the inbox is told the inbox is a waiting room`() {
        val prompt = FolderPrompt.build(doc("init/extensao.pdf"), "", listOf("academico"))

        assertTrue(prompt.contains("fila de triagem"))
    }

    @Test
    fun `a huge tree is ordered by relevance so the cut keeps the best ones`() {
        val many = (1..FolderPrompt.MAX_FOLDERS).map { "pasta-$it" } + "academico/extensao"

        val prompt = FolderPrompt.build(
            doc = doc("extensao.pdf"),
            text = "carga horária de extensão",
            existing = many
        )

        assertTrue("- academico/extensao" in prompt)
        // The last candidate of a list that did not fit is the one that goes.
        assertTrue("- pasta-${FolderPrompt.MAX_FOLDERS}\n" !in prompt)
    }
}
