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
import com.openappslabs.docs.data.model.DocText
import com.openappslabs.docs.data.source.DocTextDao
import com.openappslabs.docs.data.text.Harvest
import com.openappslabs.docs.data.text.TextHarvester
import com.openappslabs.docs.data.text.TextSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

data class TextOutcome(
    val read: Int,
    val withText: Int,
    val failed: Int,
    val empty: Int,
    val durationMillis: Long
)

/**
 * Reads the content of the documents that have not been read yet.
 *
 * Runs in small batches, newest first, so the search becomes useful within
 * seconds of the app opening instead of after the whole library was chewed
 * through. A read is stored even when it found nothing: the difference between
 * "not read" and "nothing inside" is a row, and that is what stops the app from
 * spending an OCR minute on the same image every launch.
 */
class DocTextIndexer(
    private val root: Path,
    private val dao: DocTextDao,
    private val harvester: TextHarvester,
    private val batchSize: Int = DEFAULT_BATCH
) {

    suspend fun indexPending(limit: Int = batchSize): TextOutcome = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val pending = dao.pending(limit)
        var withText = 0
        var failed = 0
        var empty = 0
        var read = 0

        for (doc in pending) {
            val file = root.resolve(doc.relPath)
            val harvest: Harvest = harvester.harvest(file, doc.kind) ?: continue
            read += 1
            when {
                harvest.source == TextSource.FAILED -> failed += 1
                harvest.hasText -> withText += 1
                else -> empty += 1
            }
            dao.put(
                DocText(
                    docId = doc.id,
                    text = harvest.text,
                    source = harvest.source.name.lowercase(),
                    charCount = harvest.text.length,
                    extractedTime = System.currentTimeMillis()
                )
            )
        }

        TextOutcome(
            read = read,
            withText = withText,
            failed = failed,
            empty = empty,
            durationMillis = System.currentTimeMillis() - startedAt
        )
    }

    /**
     * Reads one document now, instead of waiting for the next batch.
     *
     * The reason this exists: the batch runs in the background and covers the
     * newest files first, so a document that was never reached was sent to the
     * model as "no text" — the app looked like it never opened the file, while
     * the truth was a queue. When the user asks for a suggestion, this reads
     * that one file and stores the result like any other read, so the answer is
     * about the file and not about its name.
     *
     * Returns null when there is nothing to store (document gone from the disk);
     * an empty string means the file was read and has no words — a photo, an
     * audio, a broken PDF — and the caller must not loop on it.
     */
    suspend fun readOne(doc: Doc): String? = withContext(Dispatchers.IO) {
        val file = root.resolve(doc.relPath)
        val harvest = harvester.harvest(file, doc.kind) ?: return@withContext null
        dao.put(
            DocText(
                docId = doc.id,
                text = harvest.text,
                source = harvest.source.name.lowercase(),
                charCount = harvest.text.length,
                extractedTime = System.currentTimeMillis()
            )
        )
        harvest.text
    }

    companion object {
        /** One pass per launch: enough to feel alive, short enough to not hog the disk. */
        const val DEFAULT_BATCH = 25
    }
}
