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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocDateTest {

    /** The real text of an extension receipt, read from the library. */
    private val extension = """
        Trabalhos Acadêmicos
        Comprovante de envio
        Aluno
        Matrícula: R8602D3 (líder)
        Nome: JONATHAN SANCHES CARDOSO
        Atividade
        Tipo: Atividade de Extensão
        Curso: CIENCIA DA COMPUTACAO
        Data da atividade: 2026 - 2º Semestre
        Série: 4
        Entrega
        Data e hora: 13/11/2026 às 21:58
        Autenticação: 6fe52138-2bf5-4d51-be5d-4d455f8fd211
    """.trimIndent()

    @Test
    fun `the extension receipt is dated by its activity period`() {
        // Not by 13/11/2026 on "Data e hora" — that is the upload to the portal.
        // The document is about the 2º semestre.
        assertEquals("2026/semestre-2", DocDate.of(extension)?.tag)
        assertEquals("2º semestre de 2026", DocDate.of(extension)?.label)
    }

    @Test
    fun `OCR that splits Semestre onto the next line still finds the period`() {
        val ocr = """
            Data da atividade: 2025 - 2º
            Série: 2
            Semestre
            Entrega
            Data e hora: 10/11/2025 às 21:58
        """.trimIndent()

        assertEquals("2025/semestre-2", DocDate.of(ocr)?.tag)
    }

    @Test
    fun `a document that only names the semester gets a semester tag`() {
        val stamp = DocDate.of("Data da atividade: 2026 - 2º Semestre")

        assertEquals("2026/semestre-2", stamp?.tag)
        assertEquals("2º semestre de 2026", stamp?.label)
    }

    @Test
    fun `a document that only names the year gets the year`() {
        assertEquals("2025", DocDate.of("Declaro, para os fins do ano de 2025, que...")?.tag)
    }

    @Test
    fun `the month written in words is understood`() {
        assertEquals("2025/mes-4", DocDate.of("Referente a abril de 2025.")?.tag)
        assertEquals("2025/mes-4", DocDate.of("Competência: 04/2025")?.tag)
    }

    @Test
    fun `the ISO shape is understood`() {
        assertEquals("2024/mes-3", DocDate.of("Emitido em 2024-03-15.")?.tag)
    }

    @Test
    fun `a text without a date has no date tag`() {
        assertNull(DocDate.of("Recibo de aluguel, sem data nenhuma."))
        assertNull(DocDate.of(""))
        // A number that is not a date must not become one.
        assertNull(DocDate.of("50 horas de atividade, 17 pessoas."))
    }

    @Test
    fun `a date tag is only accepted when the text backs it`() {
        // The receipt is about a semester: November on "Data e hora" must not
        // authorize a month tag.
        assertFalse(DateTag.allowedIn("2026/mes-11", extension))
        assertFalse(DateTag.allowedIn("mes-11", extension))
        assertTrue(DateTag.allowedIn("2026/semestre-2", extension))
        assertTrue(DateTag.allowedIn("2026", extension))
        assertFalse(DateTag.allowedIn("2025/mes-4", extension))
    }

    @Test
    fun `a subject tag is never judged by the date`() {
        assertTrue(DateTag.allowedIn("extensao", ""))
        assertTrue(DateTag.allowedIn("unip/2026", ""))
    }
}
