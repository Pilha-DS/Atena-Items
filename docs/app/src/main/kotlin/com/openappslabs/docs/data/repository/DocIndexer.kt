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

package com.openappslabs.docs.data.repository

import com.openappslabs.docs.data.model.Doc
import com.openappslabs.docs.data.model.DocStatus
import com.openappslabs.docs.data.scan.DocScanner
import com.openappslabs.docs.data.scan.IndexPlan
import com.openappslabs.docs.data.source.DocDao
import com.openappslabs.docs.data.source.DocTextDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

data class IndexOutcome(
    val added: Int,
    val updated: Int,
    val moved: Int,
    val missing: Int,
    val unreadable: Int,
    val durationMillis: Long
)

/**
 * Applies one scan to the index.
 *
 * The writes are not wrapped in a single transaction on purpose: they are
 * idempotent, and a scan interrupted halfway only means the next one finishes
 * the job. That trade buys the ability to keep writing the list while a big
 * folder is being indexed, instead of holding a write lock for the whole walk.
 */
class DocIndexer(
    private val root: Path,
    private val dao: DocDao,
    /**
     * Optional so the index can be exercised without a text layer. When it is
     * present, a file whose size or date changed loses its stored text and is
     * read again on the next pass.
     */
    private val textDao: DocTextDao? = null
) {

    suspend fun scanNow(): IndexOutcome = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val result = DocScanner.scan(root)
        val existing = dao.all()
        val plan = IndexPlan.plan(existing, result.files)
        val now = System.currentTimeMillis()

        // An edited file is a different text: drop what was read before so the
        // content pass picks it up again. Moves keep it — the content is the
        // same, only the path changed.
        val edited = plan.updates
            .filter { it.existing.size != it.file.size || it.existing.modifiedTime != it.file.modifiedTime }
            .map { it.existing.id }
        if (edited.isNotEmpty()) textDao?.forget(edited)

        for (file in plan.inserts) {
            dao.insert(
                Doc(
                    relPath = file.relPath,
                    name = file.name,
                    kind = file.kind,
                    size = file.size,
                    modifiedTime = file.modifiedTime,
                    indexedTime = now
                )
            )
        }

        // A rename keeps the row — and therefore its tags, favorite and uuid.
        // It has to run before the inserts, otherwise the new path would be
        // taken by a fresh row.
        for (move in plan.moves) {
            dao.update(
                move.existing.copy(
                    relPath = move.file.relPath,
                    name = move.file.name,
                    kind = move.file.kind,
                    size = move.file.size,
                    modifiedTime = move.file.modifiedTime,
                    indexedTime = now,
                    status = DocStatus.PRESENT
                )
            )
        }

        for (update in plan.updates) {
            dao.update(
                update.existing.copy(
                    name = update.file.name,
                    kind = update.file.kind,
                    size = update.file.size,
                    modifiedTime = update.file.modifiedTime,
                    indexedTime = now,
                    status = DocStatus.PRESENT
                )
            )
        }

        if (plan.missing.isNotEmpty()) {
            dao.setStatus(plan.missing, DocStatus.MISSING)
        }

        IndexOutcome(
            added = plan.inserts.size,
            updated = plan.updates.size,
            moved = plan.moves.size,
            missing = plan.missing.size,
            unreadable = result.unreadable,
            durationMillis = System.currentTimeMillis() - startedAt
        )
    }
}
