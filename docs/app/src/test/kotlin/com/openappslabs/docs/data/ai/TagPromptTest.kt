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

class TagPromptTest {

    private fun doc(relPath: String) = Doc(
        relPath = relPath,
        name = relPath.substringAfterLast('/'),
        kind = DocKind.PDF,
        size = 1,
        modifiedTime = 1
    )

    @Test
    fun `the prompt names the file, the folder, the tags and the excerpt`() {
        val prompt = TagPrompt.build(
            doc = doc("UNIP/Extensao/certificado.pdf"),
            text = "Declaramos que o aluno concluiu a carga horária",
            existing = listOf("academico", "academico/extensao")
        )

        assertTrue("certificado.pdf" in prompt)
        assertTrue("UNIP/Extensao" in prompt)
        assertTrue("- academico/extensao" in prompt)
        assertTrue("Declaramos que o aluno concluiu" in prompt)
        assertTrue("nenhuma" in prompt)
    }

    @Test
    fun `without text the prompt asks to use the name and the folder`() {
        val prompt = TagPrompt.build(doc("a.pdf"), text = "", existing = emptyList())

        assertTrue("não tem texto legível" in prompt)
        assertTrue("Não há etiquetas ainda" in prompt)
    }

    @Test
    fun `the excerpt is cut`() {
        val prompt = TagPrompt.build(doc("a.pdf"), "x".repeat(10_000), listOf("a"))
        assertTrue(prompt.length < 10_000)
    }

    @Test
    fun `only a finite list of existing tags goes to the model`() {
        val many = (1..200).map { "tag/$it" }
        val prompt = TagPrompt.build(doc("a.pdf"), "texto", many)

        assertTrue("tag/${TagPrompt.MAX_EXISTING_TAGS}" in prompt)
        assertTrue("tag/200" !in prompt)
    }

    @Test
    fun `parse strips bullets, quotes and explanations`() {
        val parsed = TagPrompt.parse(
            listOf(
                "- academico/extensao",
                "* Pessoal",
                "\"Financas/2026\"",
                "nenhuma",
                "",
                "academico/extensao"
            )
        )

        assertEquals(listOf("academico/extensao", "pessoal", "financas/2026"), parsed)
    }

    @Test
    fun `parse drops the trailing period and splits a comma list`() {
        assertEquals(listOf("unip", "extensao"), TagPrompt.parse(listOf("unip., extensao.")))
        assertEquals(listOf("academico", "pessoal"), TagPrompt.parse(listOf("**academico**, pessoal.")))
    }

    @Test
    fun `the prompt shows what each tag is used for`() {
        val prompt = TagPrompt.build(
            doc = doc("init/curriculo.pdf"),
            text = "currículo atualizado",
            existing = listOf("unip"),
            examples = mapOf("unip" to listOf("extensao_1.pdf", "extensao_2.pdf"))
        )

        assertTrue("- unip — usada em: extensao_1.pdf, extensao_2.pdf" in prompt)
    }

    @Test
    fun `the prompt carries the type, the size and the dates`() {
        val prompt = TagPrompt.build(
            doc = doc("init/extensao_1.pdf"),
            text = "declaração",
            existing = emptyList(),
            facts = DocFacts(
                kind = "PDF",
                size = 67_477,
                createdTime = 1_773_000_000_000L,
                modifiedTime = 1_794_617_976_000L
            )
        )

        assertTrue("Tipo: PDF · 67,5 KB" in prompt)
        assertTrue("Criado no disco em: " in prompt)
        assertTrue("Modificado no disco em: " in prompt)
        // The dates are of the file, not of the document, and the prompt says
        // so: that is what stops a November copy from becoming a `mes-11` tag.
        assertTrue("nunca viram etiqueta de data" in prompt)
        assertTrue("nunca proponha palavra de formulário" in prompt.lowercase())
    }

    @Test
    fun `the prompt names the document's own date as the tag to reuse`() {
        val prompt = TagPrompt.build(
            doc = doc("init/extensao_1.pdf"),
            text = "Data da atividade: 2026 - 2º Semestre",
            existing = emptyList(),
            documentDate = DocDate.of("Data da atividade: 2026 - 2º Semestre")
        )

        assertTrue("Data do documento (lida no conteúdo): 2026/semestre-2" in prompt)
        assertTrue("prefira as etiquetas de ano" in prompt)
    }

    @Test
    fun `a word that appears in every form is not offered as a tag`() {
        // `aluno` and `atividade` are printed in every academic document: a tag
        // made of one filters the whole library and finds nothing.
        val proposals = HeuristicTagger.proposeNew(
            doc = doc("init/extensao_1_sem_4.pdf"),
            text = "Aluno: JONATHAN. Atividade: Atividade de Extensão. Aluno.",
            existing = emptyList()
        )

        assertTrue(proposals.none { it.path in setOf("aluno", "atividade") })
    }

    @Test
    fun `the prompt shows what the user said each tag means`() {
        val prompt = TagPrompt.build(
            doc = doc("init/matricula.pdf"),
            text = "matrícula na universidade",
            existing = listOf("unip", "pessoal"),
            examples = mapOf("unip" to listOf("boletim.pdf")),
            descriptions = mapOf("unip" to "tudo que é da faculdade")
        )

        // The description comes before the examples: it is the definition.
        assertTrue("- unip — descrição: tudo que é da faculdade — usada em: boletim.pdf" in prompt)
        // A tag with no description is listed as before, without a blank label.
        assertTrue("- pessoal\n" in prompt)
        assertTrue("A \"descrição\" de uma etiqueta foi escrita pelo usuário" in prompt)
    }

    @Test
    fun `without any description the prompt does not mention them`() {
        val prompt = TagPrompt.build(doc("a.pdf"), "texto", listOf("unip"))

        assertTrue("descrição" !in prompt)
    }

    @Test
    fun `the prompt warns against historico and nota false friends`() {
        val prompt = TagPrompt.build(
            doc = doc("Trabalho 1 - Estrutura de Dados.pdf"),
            text = "Histórico de operações. Nota parcial.",
            existing = listOf("historico/escolar", "nfe")
        )

        assertTrue("histórico escolar" in prompt.lowercase() || "historico/escolar" in prompt)
        assertTrue("Polissemia" in prompt)
        assertTrue("estrutura de dados" in prompt.lowercase())
        assertTrue("nota-fiscal" in prompt || "nfe" in prompt)
    }

    @Test
    fun `a long description is cut before it reaches the model`() {
        val prompt = TagPrompt.build(
            doc = doc("a.pdf"),
            text = "texto",
            existing = listOf("unip"),
            descriptions = mapOf("unip" to "y".repeat(500))
        )

        assertTrue("y".repeat(TagPrompt.MAX_DESCRIPTION_CHARS) in prompt)
        assertTrue("y".repeat(TagPrompt.MAX_DESCRIPTION_CHARS + 1) !in prompt)
    }

    @Test
    fun `examples stop when their budget is spent`() {
        val existing = (1..TagPrompt.MAX_EXISTING_TAGS).map { "tag/$it" }
        val examples = existing.associateWith { listOf("documento_com_nome_bem_longo_$it.pdf") }
        val prompt = TagPrompt.build(doc("a.pdf"), "texto", existing, examples)

        val exampleChars = prompt.lines()
            .filter { EXAMPLE_MARK in it }
            .sumOf { it.substringAfter(EXAMPLE_MARK).length }
        assertTrue("tag/1$EXAMPLE_MARK" in prompt)
        assertTrue("tag/${TagPrompt.MAX_EXISTING_TAGS}$EXAMPLE_MARK" !in prompt)
        assertTrue(exampleChars <= TagPrompt.EXAMPLE_BUDGET_CHARS)
    }

    private companion object {
        const val EXAMPLE_MARK = " — usada em: "
    }

    @Test
    fun `parse drops what is not a valid tag path`() {
        val parsed = TagPrompt.parse(listOf("Etiqueta muito comprida ".repeat(20), "ok"))
        assertEquals(listOf("ok"), parsed)
    }

    @Test
    fun `parse keeps the hierarchy and lowercases`() {
        assertEquals(listOf("academico/usp/2026"), TagPrompt.parse(listOf("Academico/USP/2026")))
    }

    @Test
    fun `parse strips the numbering a model likes to add`() {
        val parsed = TagPrompt.parse(
            listOf(
                "1. academico",
                "2) extensao",
                "3. financiamento"
            )
        )
        assertEquals(listOf("academico", "extensao", "financiamento"), parsed)
    }
}
