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

package com.openappslabs.docs.data.scan

import com.openappslabs.docs.data.model.Doc
import com.openappslabs.docs.data.model.DocKind
import com.openappslabs.docs.data.model.DocStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The diff is where the dangerous mistakes live: a row marked missing hides a
 * document from the user, and a needless update rewrites the whole table on
 * every launch.
 */
class IndexPlanTest {

    private fun doc(
        id: Long,
        relPath: String,
        size: Long = 10,
        modifiedTime: Long = 100,
        status: DocStatus = DocStatus.PRESENT
    ) = Doc(
        id = id,
        relPath = relPath,
        name = relPath.substringAfterLast('/'),
        kind = DocKind.fromFileName(relPath),
        size = size,
        modifiedTime = modifiedTime,
        status = status
    )

    private fun file(relPath: String, size: Long = 10, modifiedTime: Long = 100) = ScannedFile(
        relPath = relPath,
        name = relPath.substringAfterLast('/'),
        kind = DocKind.fromFileName(relPath),
        size = size,
        modifiedTime = modifiedTime
    )

    @Test
    fun `empty index plus files is all inserts`() {
        val plan = IndexPlan.plan(emptyList(), listOf(file("a.pdf"), file("b/c.docx")))

        assertEquals(listOf("a.pdf", "b/c.docx"), plan.inserts.map { it.relPath })
        assertTrue(plan.updates.isEmpty())
        assertTrue(plan.moves.isEmpty())
        assertTrue(plan.missing.isEmpty())
    }

    @Test
    fun `same size and time means nothing to do`() {
        val plan = IndexPlan.plan(listOf(doc(1, "a.pdf")), listOf(file("a.pdf")))

        assertTrue(plan.isEmpty)
        assertEquals(1, plan.unchanged)
    }

    @Test
    fun `new size marks the row for update`() {
        val plan = IndexPlan.plan(
            listOf(doc(1, "a.pdf", size = 10)),
            listOf(file("a.pdf", size = 20))
        )

        val update = plan.updates.single()
        assertEquals(1L, update.existing.id)
        assertEquals(20, update.file.size)
        assertTrue(plan.inserts.isEmpty())
        assertTrue(plan.missing.isEmpty())
    }

    @Test
    fun `new modification time marks the row for update`() {
        val plan = IndexPlan.plan(
            listOf(doc(1, "a.pdf", modifiedTime = 100)),
            listOf(file("a.pdf", modifiedTime = 200))
        )

        assertEquals(1, plan.updates.size)
    }

    @Test
    fun `a row absent from disk is missing`() {
        val plan = IndexPlan.plan(
            listOf(doc(1, "a.pdf"), doc(2, "b.pdf")),
            listOf(file("a.pdf"))
        )

        assertEquals(listOf(2L), plan.missing)
        assertTrue(plan.inserts.isEmpty())
        assertTrue(plan.updates.isEmpty())
    }

    @Test
    fun `a missing row that reappears is restored`() {
        val plan = IndexPlan.plan(
            listOf(doc(1, "a.pdf", status = DocStatus.MISSING)),
            listOf(file("a.pdf"))
        )

        val update = plan.updates.single()
        assertEquals(1L, update.existing.id)
        assertTrue(plan.missing.isEmpty())
        // Restoring keeps the identity: tags attached to the row survive.
        assertTrue(plan.inserts.isEmpty())
    }

    @Test
    fun `an already missing row is not reported missing again`() {
        val plan = IndexPlan.plan(
            listOf(doc(1, "a.pdf", status = DocStatus.MISSING)),
            emptyList()
        )

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `a renamed file keeps its row when size and time match`() {
        val plan = IndexPlan.plan(
            listOf(doc(1, "velho.pdf", size = 42, modifiedTime = 500)),
            listOf(file("novo.pdf", size = 42, modifiedTime = 500))
        )

        val move = plan.moves.single()
        assertEquals(1L, move.existing.id)
        assertEquals("novo.pdf", move.file.relPath)
        assertTrue(plan.inserts.isEmpty())
        assertTrue(plan.missing.isEmpty())
        assertEquals(0, plan.unchanged)
    }

    @Test
    fun `moving to another folder is also a move`() {
        val plan = IndexPlan.plan(
            listOf(doc(1, "faculdade/prova.pdf", size = 7, modifiedTime = 900)),
            listOf(file("arquivo/prova.pdf", size = 7, modifiedTime = 900))
        )

        assertEquals(1L, plan.moves.single().existing.id)
        assertTrue(plan.missing.isEmpty())
    }

    @Test
    fun `renaming and editing at the same time is not a move`() {
        val plan = IndexPlan.plan(
            listOf(doc(1, "velho.pdf", size = 42, modifiedTime = 500)),
            listOf(file("novo.pdf", size = 99, modifiedTime = 700))
        )

        assertTrue(plan.moves.isEmpty())
        assertEquals(listOf(1L), plan.missing)
        assertEquals(listOf("novo.pdf"), plan.inserts.map { it.relPath })
    }

    @Test
    fun `same size and time but only one arrival is the move`() {
        val plan = IndexPlan.plan(
            listOf(doc(1, "a.pdf", size = 42, modifiedTime = 500)),
            listOf(
                file("relatorio.pdf", size = 42, modifiedTime = 500),
                file("copia.pdf", size = 42, modifiedTime = 999)
            )
        )

        // Only the arrival with the same mtime can be the same file.
        assertEquals("relatorio.pdf", plan.moves.single().file.relPath)
        assertEquals(listOf("copia.pdf"), plan.inserts.map { it.relPath })
        assertTrue(plan.missing.isEmpty())
    }

    @Test
    fun `two identical files pick the closest name`() {
        val plan = IndexPlan.plan(
            listOf(doc(1, "relatorio-final.pdf", size = 10, modifiedTime = 100)),
            listOf(
                file("outra-coisa.pdf", size = 10, modifiedTime = 100),
                file("relatorio-final-2026.pdf", size = 10, modifiedTime = 100)
            )
        )

        assertEquals("relatorio-final-2026.pdf", plan.moves.single().file.relPath)
        assertEquals(listOf("outra-coisa.pdf"), plan.inserts.map { it.relPath })
        assertTrue(plan.missing.isEmpty())
    }

    @Test
    fun `an already missing row is not mistaken for a move`() {
        val plan = IndexPlan.plan(
            listOf(doc(1, "sumido.pdf", size = 10, modifiedTime = 100, status = DocStatus.MISSING)),
            listOf(file("novo.pdf", size = 10, modifiedTime = 100))
        )

        assertTrue(plan.moves.isEmpty())
        assertEquals(listOf("novo.pdf"), plan.inserts.map { it.relPath })
    }

    @Test
    fun `the file that stayed in the same folder wins`() {
        val plan = IndexPlan.plan(
            listOf(doc(1, "materia/relatorio.pdf", size = 10, modifiedTime = 100)),
            listOf(
                file("materia/zzz.pdf", size = 10, modifiedTime = 100),
                file("outra/relatorio.pdf", size = 10, modifiedTime = 100)
            )
        )

        // The name is identical in `outra`, but staying in the folder is the
        // stronger signal: names are edited all the time, folders are not.
        assertEquals("materia/zzz.pdf", plan.moves.single().file.relPath)
        assertEquals(listOf("outra/relatorio.pdf"), plan.inserts.map { it.relPath })
    }

    @Test
    fun `copies in two folders each keep their own row`() {
        val plan = IndexPlan.plan(
            listOf(
                doc(1, "Trabalhos.pdf", size = 67453, modifiedTime = 700),
                doc(2, "extensao/Trabalhos.pdf", size = 67453, modifiedTime = 700)
            ),
            listOf(
                file("extensao/atividade.pdf", size = 67453, modifiedTime = 700),
                file("atividade.pdf", size = 67453, modifiedTime = 700)
            )
        )

        val byId = plan.moves.associate { it.existing.id to it.file.relPath }
        assertEquals("atividade.pdf", byId[1])
        assertEquals("extensao/atividade.pdf", byId[2])
        assertTrue(plan.inserts.isEmpty())
        assertTrue(plan.missing.isEmpty())
    }

    @Test
    fun `name similarity rewards what stayed in place`() {
        assertTrue(
            IndexPlan.nameSimilarity("relatorio-final.pdf", "relatorio-final-2026.pdf") >
                IndexPlan.nameSimilarity("relatorio-final.pdf", "zzz.pdf")
        )
        assertTrue(
            IndexPlan.nameSimilarity("relatorio.pdf", "relatorio final.pdf") >
                IndexPlan.nameSimilarity("relatorio.pdf", "zzz.pdf")
        )
    }

    @Test
    fun `mixed tree is split correctly`() {
        val existing = listOf(
            doc(1, "igual.pdf"),
            doc(2, "mudou.pdf", size = 1),
            doc(3, "sumiu.pdf")
        )
        val scanned = listOf(
            file("igual.pdf"),
            file("mudou.pdf", size = 2),
            // Different size and time: a genuinely new file, not a rename.
            file("novo.pdf", size = 3, modifiedTime = 400)
        )

        val plan = IndexPlan.plan(existing, scanned)

        assertTrue(plan.moves.isEmpty())
        assertEquals(listOf("novo.pdf"), plan.inserts.map { it.relPath })
        assertEquals(listOf(2L), plan.updates.map { it.existing.id })
        assertEquals(listOf(3L), plan.missing)
        assertEquals(1, plan.unchanged)
    }
}
