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
import com.openappslabs.docs.data.model.DocStatus

/** A row that already exists and whose file changed on disk. */
data class DocUpdate(val existing: Doc, val file: ScannedFile)

/** A row whose file is not at that path anymore, now living at [file]. */
data class DocMove(val existing: Doc, val file: ScannedFile)

/**
 * What a scan changes, computed before anything is written.
 *
 * Kept as a pure function so the diffing rules have tests without a database:
 * that is where the bugs live (a row marked missing by accident hides a
 * document, and a needless update turns every start into a full table write).
 *
 * Rules:
 *  - path not in the index -> insert;
 *  - path in the index, same size and mtime, still present -> nothing;
 *  - path in the index with a different size or mtime -> update;
 *  - path in the index that came back after being missing -> update (restores
 *    it, even when nothing else changed);
 *  - path that was present and is not on disk anymore -> missing, unless it
 *    matches an insert (see below), then it is a move.
 *
 * A move keeps the row identity, so the tags, the favorite mark and the uuid
 * of the document survive a rename or a change of folder. The evidence is the
 * pair `size` + `mtime`: `mv` keeps both, a copy usually changes `mtime`, and
 * editing the file changes at least one of them — which is why "renamed and
 * edited at the same time" is not recognised (it degrades to missing + new,
 * the behaviour of before). No file content is read, on purpose: the scan
 * stays a `stat` per file.
 */
data class IndexPlan(
    val inserts: List<ScannedFile>,
    val updates: List<DocUpdate>,
    val moves: List<DocMove>,
    val missing: List<Long>,
    val unchanged: Int
) {
    val isEmpty: Boolean
        get() = inserts.isEmpty() && updates.isEmpty() && moves.isEmpty() && missing.isEmpty()

    companion object {
        fun plan(existing: List<Doc>, scanned: List<ScannedFile>): IndexPlan {
            val byPath = HashMap<String, Doc>(existing.size * 2)
            existing.forEach { byPath[it.relPath] = it }

            val inserts = ArrayList<ScannedFile>()
            val updates = ArrayList<DocUpdate>()
            var unchanged = 0

            for (file in scanned) {
                val known = byPath.remove(file.relPath)
                when {
                    known == null -> inserts.add(file)
                    known.size != file.size || known.modifiedTime != file.modifiedTime ->
                        updates.add(DocUpdate(known, file))
                    known.status != DocStatus.PRESENT -> updates.add(DocUpdate(known, file))
                    else -> unchanged += 1
                }
            }

            // Whatever is left in the map was not seen on disk this time.
            val gone = byPath.values
                .filter { it.status == DocStatus.PRESENT }
                .sortedBy { it.relPath }

            val (moves, orphans) = pairMoves(gone, inserts)

            return IndexPlan(
                inserts = orphans,
                updates = updates,
                moves = moves,
                missing = gone.filter { doc -> moves.none { it.existing.id == doc.id } }.map { it.id },
                unchanged = unchanged
            )
        }

        /**
         * Pairs a vanished row with a new file when they are the same file
         * under another name.
         *
         * Only an exact `size` + `mtime` match counts. When several files share
         * that pair (two identical copies, a folder copied and pasted), the
         * file that stayed in the same folder wins, then the closest name; the
         * rest stay as they were — a wrong guess would move tags to another
         * document.
         */
        private fun pairMoves(
            gone: List<Doc>,
            arrivals: List<ScannedFile>
        ): Pair<List<DocMove>, List<ScannedFile>> {
            if (gone.isEmpty() || arrivals.isEmpty()) return emptyList<DocMove>() to arrivals

            val pool = arrivals.toMutableList()
            val moves = ArrayList<DocMove>()

            for (doc in gone) {
                val folder = folderOf(doc.relPath)
                val candidates = pool.filter {
                    it.size == doc.size && it.modifiedTime == doc.modifiedTime
                }
                if (candidates.isEmpty()) continue

                val best = candidates.sortedWith(
                    compareByDescending<ScannedFile> { folderOf(it.relPath) == folder }
                        .thenByDescending { nameSimilarity(doc.name, it.name) }
                        .thenBy { it.relPath }
                ).first()

                moves.add(DocMove(doc, best))
                pool.remove(best)
            }

            return moves to pool
        }

        internal fun folderOf(relPath: String): String = relPath.substringBeforeLast('/', "")

        /**
         * Cheap stand-in for edit distance: how much of the name stayed in the
         * same place. `relatorio.pdf` -> `relatorio final.pdf` scores high,
         * `a.pdf` -> `z.pdf` scores low. No allocation, no library.
         */
        internal fun nameSimilarity(a: String, b: String): Int =
            a.commonPrefixWith(b).length + a.commonSuffixWith(b).length
    }
}
