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

package com.openappslabs.docs.data.search

import com.openappslabs.docs.data.model.Doc
import com.openappslabs.docs.data.model.DocKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UniversalSearchTest {

    private fun doc(
        id: Long,
        name: String,
        size: Long = 1000,
        modifiedTime: Long = 1_700_000_000_000L,
        visitCount: Int = 0,
        lastVisitedAt: Long = 0L
    ) = Doc(
        id = id,
        relPath = name,
        name = name,
        kind = DocKind.fromFileName(name),
        size = size,
        modifiedTime = modifiedTime,
        visitCount = visitCount,
        lastVisitedAt = lastVisitedAt
    )

    private val byName = compareBy(String.CASE_INSENSITIVE_ORDER) { d: Doc -> d.name }
    private val now = 1_700_000_000_000L

    @Test
    fun `a strong name match outranks a weak content hit`() {
        val named = doc(1, "contrato-aluguel.pdf")
        val contentOnly = doc(2, "arquivo.bin")
        val ranked = UniversalSearch.rank(
            query = "contrato",
            docs = listOf(contentOnly, named),
            tagsOf = emptyMap(),
            aliasesByTag = emptyMap(),
            texts = mapOf(2L to "mencao isolada a contrato no meio do OCR"),
            metaOf = { emptyList() },
            secondary = byName,
            nowMs = now
        )
        assertEquals(listOf("contrato-aluguel.pdf", "arquivo.bin"), ranked.map { it.name })
        val scores = UniversalSearch.score(
            query = "contrato",
            docs = listOf(named, contentOnly),
            tagsOf = emptyMap(),
            aliasesByTag = emptyMap(),
            texts = mapOf(2L to "mencao isolada a contrato no meio do OCR"),
            metaOf = { emptyList() },
            nowMs = now
        ).associate { it.doc.id to it.score }
        assertTrue(scores.getValue(1L) > scores.getValue(2L))
    }

    @Test
    fun `typing an alias finds the tagged document`() {
        val hit = doc(1, "danfe.pdf")
        val miss = doc(2, "outro.pdf")
        val ranked = UniversalSearch.rank(
            query = "nfe",
            docs = listOf(miss, hit),
            tagsOf = mapOf(1L to listOf("nota-fiscal")),
            aliasesByTag = mapOf("nota-fiscal" to listOf("nfe")),
            texts = emptyMap(),
            metaOf = { emptyList() },
            secondary = byName,
            nowMs = now
        )
        assertEquals(listOf("danfe.pdf"), ranked.map { it.name })
    }

    @Test
    fun `empty query ranks by frecency`() {
        val habit = doc(1, "b.pdf", visitCount = 12, lastVisitedAt = now)
        val idle = doc(2, "a.pdf")
        val ranked = UniversalSearch.rank(
            query = "",
            docs = listOf(idle, habit),
            tagsOf = emptyMap(),
            aliasesByTag = emptyMap(),
            texts = emptyMap(),
            metaOf = { emptyList() },
            secondary = byName,
            nowMs = now
        )
        assertEquals(listOf("b.pdf", "a.pdf"), ranked.map { it.name })
    }

    @Test
    fun `nothing matches when the query is nowhere`() {
        val ranked = UniversalSearch.rank(
            query = "xyzzy",
            docs = listOf(doc(1, "a.pdf")),
            tagsOf = mapOf(1L to listOf("mes-9")),
            aliasesByTag = emptyMap(),
            texts = mapOf(1L to "texto sem a palavra"),
            metaOf = { listOf("PDF") },
            secondary = byName,
            nowMs = now
        )
        assertEquals(emptyList(), ranked)
    }
}
