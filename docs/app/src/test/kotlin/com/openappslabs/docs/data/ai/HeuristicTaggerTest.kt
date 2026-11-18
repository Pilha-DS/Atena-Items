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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeuristicTaggerTest {

    private fun doc(relPath: String) = Doc(
        relPath = relPath,
        name = relPath.substringAfterLast('/'),
        kind = DocKind.PDF,
        size = 1,
        modifiedTime = 1
    )

    @Test
    fun `a word of an existing tag in the text is a suggestion`() {
        val suggestions = HeuristicTagger.suggest(
            doc = doc("UNIP/relatorio.pdf"),
            text = "Relatório do projeto de extensão universitária, com carga horária",
            existing = listOf("academico/extensao", "pessoal/receitas")
        )

        assertEquals(listOf("academico/extensao"), suggestions.map { it.path })
        assertEquals("aparece no texto", suggestions.single().reason)
        assertTrue(!suggestions.single().fromModel)
    }

    @Test
    fun `the file name weighs more than the body`() {
        val suggestions = HeuristicTagger.suggest(
            doc = doc("notas/faculdade.pdf"),
            text = "o contrato menciona receitas e despesas várias vezes",
            existing = listOf("estudos/faculdade", "financas/receitas")
        )

        // `faculdade` in the name beats `receitas` repeated in the text.
        assertEquals(listOf("estudos/faculdade", "financas/receitas"), suggestions.map { it.path })
    }

    @Test
    fun `accents and case do not matter`() {
        val suggestions = HeuristicTagger.suggest(
            doc = doc("x.pdf"),
            text = "MATRÍCULA confirmada",
            existing = listOf("academico/matricula")
        )

        assertEquals(listOf("academico/matricula"), suggestions.map { it.path })
    }

    @Test
    fun `without tags there is nothing to suggest`() {
        assertTrue(
            HeuristicTagger.suggest(doc("a.pdf"), "texto qualquer", emptyList()).isEmpty()
        )
    }

    @Test
    fun `a tag with no word in the document is not suggested`() {
        val suggestions = HeuristicTagger.suggest(
            doc = doc("a.pdf"),
            text = "texto curto",
            existing = listOf("financas/imposto")
        )

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `short fragments are ignored`() {
        // "de" is too short to mean anything.
        val suggestions = HeuristicTagger.suggest(
            doc = doc("a.pdf"),
            text = "declaração de matrícula",
            existing = listOf("de")
        )

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `the list respects the limit and the order`() {
        val existing = listOf("a/extensao", "b/extensao", "c/extensao", "d/extensao")
        val suggestions = HeuristicTagger.suggest(
            doc = doc("extensao.pdf"),
            text = "extensao extensao extensao",
            existing = existing,
            limit = 2
        )

        assertEquals(2, suggestions.size)
    }

    @Test
    fun `fold removes accents and lowercases`() {
        assertEquals("extensao academico", HeuristicTagger.fold("Extensão ACADÊMICO"))
    }

    @Test
    fun `new tags come from the words of the name`() {
        val proposals = HeuristicTagger.proposeNew(
            doc = doc("Contrato de aluguel 2026.pdf"),
            text = "",
            existing = emptyList()
        )

        assertEquals(listOf("contrato", "aluguel"), proposals.map { it.path })
        assertTrue(proposals.all { it.isNew })
        assertTrue(proposals.none { it.fromModel })
    }

    @Test
    fun `a word the vocabulary already uses is not a new tag`() {
        assertTrue(
            HeuristicTagger.proposeNew(
                doc = doc("extensao.pdf"),
                text = "extensão universitária",
                existing = listOf("academico/extensao")
            ).isEmpty()
        )
    }

    @Test
    fun `type noise is never a new tag`() {
        assertTrue(
            HeuristicTagger.proposeNew(doc("IMG_20260101_scan.pdf"), "", emptyList()).isEmpty()
        )
    }

    @Test
    fun `a word repeated in the text becomes a new tag`() {
        val proposals = HeuristicTagger.proposeNew(
            doc = doc("scan.pdf"),
            text = "aluguel pago; aluguel do mês",
            existing = emptyList()
        )

        assertEquals(listOf("aluguel"), proposals.map { it.path })
    }

    @Test
    fun `a form label repeated in the text is not a tag`() {
        // `matricula`, `aluno`, `atividade` are field labels: they appear in
        // every academic document, so a tag made of one filters nothing.
        val proposals = HeuristicTagger.proposeNew(
            doc = doc("scan.pdf"),
            text = "matrícula confirmada; matrícula assinada; aluno; aluno",
            existing = emptyList()
        )

        assertTrue(proposals.isEmpty())
    }

    @Test
    fun `a compound tag is not unlocked by a single shared word`() {
        // Coursework talking about "nota" (grade) must not become nota-fiscal.
        val suggestions = HeuristicTagger.suggest(
            doc = doc("ProjetoVelinhaNP2_semestre4.pdf"),
            text = "Projeto da disciplina. Nota parcial NP2 do semestre 4 na UNIP.",
            existing = listOf("nota-fiscal", "unip", "comprovante")
        )

        assertTrue("nota-fiscal" !in suggestions.map { it.path })
        assertEquals(listOf("unip"), suggestions.map { it.path })
    }

    @Test
    fun `nota-fiscal matches when the invoice phrase is present`() {
        val suggestions = HeuristicTagger.suggest(
            doc = doc("danfe.pdf"),
            text = "Nota fiscal eletrônica emitida pelo prestador de serviços.",
            existing = listOf("nota-fiscal", "unip")
        )

        assertEquals(listOf("nota-fiscal"), suggestions.map { it.path })
    }

    @Test
    fun `description repeating path fragments does not unlock a compound tag`() {
        // APS that says "nota" (grade) plus a description that only echoes
        // "nota fiscal" must not suggest the invoice tag — even when UNIP
        // correctly matches from the filename.
        val suggestions = HeuristicTagger.suggest(
            doc = doc("APS-UNIP-semestre4.pdf"),
            text = "Aspectos relacionados ao projeto. Nota parcial do semestre.",
            existing = listOf("nota-fiscal", "unip"),
            descriptions = mapOf(
                "nota-fiscal" to "É relacionado a nota fiscal de compra e venda",
                "unip" to "Documentos da universidade UNIP"
            )
        )

        assertTrue("nota-fiscal" !in suggestions.map { it.path })
        assertEquals(listOf("unip"), suggestions.map { it.path })
    }

    @Test
    fun `a distinctive description word still suggests without the path word`() {
        val suggestions = HeuristicTagger.suggest(
            doc = doc("matricula.pdf"),
            text = "Confirmação de matrícula na universidade estadual.",
            existing = listOf("academico/usp"),
            descriptions = mapOf("academico/usp" to "Documentos da universidade de São Paulo")
        )

        assertEquals(listOf("academico/usp"), suggestions.map { it.path })
        assertEquals("a descrição da etiqueta cita o texto", suggestions.single().reason)
    }

    @Test
    fun `nfe description with nota-fiscal does not unlock on academic nota`() {
        // Renamed invoice tag: path is `nfe`, description still says nota-fiscal.
        val suggestions = HeuristicTagger.suggest(
            doc = doc("Trabalho 1 - Estrutura de Dados_2026_unip_semestre4.pdf"),
            text = "Lista de exercícios. Nota parcial NP1. Histórico de operações na estrutura.",
            existing = listOf("nfe", "unip", "historico"),
            descriptions = mapOf(
                "nfe" to "É relacionado a nota-fiscal de compra ou venda de produtos",
                "unip" to "Relacionados a faculdade UNIP - Universidade paulista."
            )
        )

        assertTrue("nfe" !in suggestions.map { it.path })
        assertTrue("unip" in suggestions.map { it.path })
    }

    @Test
    fun `nfe still matches when the invoice phrase is in the text`() {
        val suggestions = HeuristicTagger.suggest(
            doc = doc("danfe.pdf"),
            text = "Nota fiscal eletrônica de compra e venda de produtos.",
            existing = listOf("nfe", "unip"),
            descriptions = mapOf(
                "nfe" to "É relacionado a nota-fiscal de compra ou venda de produtos"
            )
        )

        assertEquals(listOf("nfe"), suggestions.map { it.path })
    }

    @Test
    fun `historico de operacoes in coursework is not historico escolar`() {
        val suggestions = HeuristicTagger.suggest(
            doc = doc("Trabalho 1 - Estrutura de Dados_2026_unip_semestre4.pdf"),
            text = "Lista de exercícios. Histórico de operações na estrutura de dados.",
            existing = listOf("historico", "historico/escolar", "unip")
        )

        assertTrue("historico" !in suggestions.map { it.path })
        assertTrue("historico/escolar" !in suggestions.map { it.path })
        assertEquals(listOf("unip"), suggestions.map { it.path })
    }

    @Test
    fun `historico escolar still matches the official transcript`() {
        val suggestions = HeuristicTagger.suggest(
            doc = doc("ImpressaoHistoricoEscolar.aspx_2026_unip.pdf"),
            text = "Impressão do histórico escolar do aluno na universidade.",
            existing = listOf("historico", "historico/escolar", "unip")
        )

        assertTrue(
            suggestions.map { it.path }.any { it == "historico" || it == "historico/escolar" }
        )
    }

    @Test
    fun `a hierarchy still matches on the leaf alone`() {
        val suggestions = HeuristicTagger.suggest(
            doc = doc("relatorio.pdf"),
            text = "Relatório do projeto de extensão universitária",
            existing = listOf("academico/extensao")
        )

        assertEquals(listOf("academico/extensao"), suggestions.map { it.path })
    }
}
