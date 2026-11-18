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
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocFactsTest {

    private val zone = ZoneId.of("America/Sao_Paulo")

    /** 08/03/2026 17:00 em São Paulo. */
    private val created = 1_773_000_000_000L

    @Test
    fun `the type and the size share a line`() {
        val facts = DocFacts(kind = "PDF", size = 67_477)

        assertEquals(listOf("Tipo: PDF · 67,5 KB"), facts.lines(zone))
    }

    @Test
    fun `a dot decimal becomes a comma, like the rest of the app`() {
        assertEquals("1,5 MB", DocFacts.sizeLabel(1_500_000))
        assertEquals("512 B", DocFacts.sizeLabel(512))
        assertEquals("2,0 GB", DocFacts.sizeLabel(2_000_000_000))
        assertEquals("999 KB", DocFacts.sizeLabel(999_000))
    }

    @Test
    fun `the creation date and the modification date are separate lines`() {
        val facts = DocFacts(kind = "PDF", size = 1, createdTime = created, modifiedTime = created)

        val lines = facts.lines(zone)

        assertTrue(lines.any { it.startsWith("Criado no disco em: 08/03/2026") })
        assertTrue(lines.any { it.startsWith("Modificado no disco em: 08/03/2026") })
    }

    @Test
    fun `a filesystem without a birth time omits the line instead of lying`() {
        // Zero and null are both "unknown": a 1970 date would send the model
        // looking for a document from the seventies.
        assertEquals(
            listOf("Tipo: PDF · 1 B", "Modificado no disco em: 08/03/2026"),
            DocFacts(kind = "PDF", size = 1, createdTime = 0, modifiedTime = created).lines(zone)
        )
        assertEquals(
            listOf("Tipo: PDF · 1 B"),
            DocFacts(kind = "PDF", size = 1).lines(zone)
        )
    }

    @Test
    fun `an unknown size does not print a zero`() {
        assertEquals(listOf("Tipo: imagem"), DocFacts(kind = "imagem", size = 0).lines(zone))
    }

    @Test
    fun `the facts come from the document the index already has`() {
        val doc = Doc(
            relPath = "init/nota.xml",
            name = "nota.xml",
            kind = DocKind.CODE,
            size = 5_813,
            modifiedTime = created
        )

        val facts = DocFacts.of(doc, createdTime = null)

        assertEquals("código ou dado estruturado", facts.kind)
        assertEquals(5_813, facts.size)
        assertEquals(created, facts.modifiedTime)
        assertEquals(null, facts.createdTime)
    }

    @Test
    fun `every kind has a label in portuguese`() {
        DocKind.entries.forEach { kind ->
            assertTrue(DocFacts.kindLabel(kind).isNotBlank(), "sem rótulo: $kind")
            assertFalse(DocFacts.kindLabel(kind).contains('_'), "rótulo cru: $kind")
        }
    }
}
