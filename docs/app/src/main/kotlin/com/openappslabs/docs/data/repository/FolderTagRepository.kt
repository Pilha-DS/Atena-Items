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

import com.openappslabs.docs.data.model.FolderTag
import com.openappslabs.docs.data.move.FolderPath
import com.openappslabs.docs.data.move.Inbox
import com.openappslabs.docs.data.source.FolderTagDao
import com.openappslabs.docs.data.source.sqlLikeUnder
import com.openappslabs.docs.data.tags.TagPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Tags a folder passes down to every document under it.
 *
 * The links live only in SQLite. Replacing the set for a folder is one save
 * from the dialog: clear, then insert what stayed checked. Creating a tag that
 * does not exist yet goes through [TagRepository.ensure], same as applying a
 * tag to a document — the machine never invents a path alone.
 */
class FolderTagRepository(
    private val dao: FolderTagDao,
    private val tags: TagRepository
) {

    /** Folder path → tag paths declared on that folder (not yet resolved). */
    fun observeByFolder(): Flow<Map<String, List<String>>> = dao.observeRows()
        .map { rows ->
            rows.groupBy({ it.folderPath }, { it.tagPath })
                .mapValues { (_, paths) -> paths.distinct().sorted() }
        }

    /**
     * Replaces the inherited tags of [rawPath] with [rawPaths].
     *
     * False when the folder cannot carry inheritance (root, inbox, invalid
     * path) or when a tag path cannot be normalized / created. An empty list
     * clears the folder.
     */
    suspend fun setInherited(rawPath: String, rawPaths: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            val path = FolderPath.normalize(rawPath) ?: return@withContext false
            if (path.isEmpty()) return@withContext false
            if (path.equals(Inbox.FOLDER, ignoreCase = true)) return@withContext false

            val normalized = LinkedHashSet<String>()
            for (raw in rawPaths) {
                val tag = TagPaths.normalize(raw) ?: return@withContext false
                normalized += tag
            }

            // Ensure every tag before clearing the folder: a failed ensure
            // mid-loop used to wipe the previous inheritance and leave the
            // new tag (e.g. "termo") nowhere — create looked like it vanished.
            val tagIds = ArrayList<Long>(normalized.size)
            for (tagPath in normalized) {
                val tagId = tags.ensure(tagPath) ?: return@withContext false
                tagIds += tagId
            }

            runCatching {
                dao.clearFolder(path)
                for (tagId in tagIds) {
                    dao.insert(FolderTag(folderPath = path, tagId = tagId))
                }
                true
            }.getOrDefault(false)
        }

    /**
     * Drops inheritance of a folder and everything below it when the folder is
     * deleted from the window.
     */
    suspend fun forgetFolder(rawPath: String) = withContext(Dispatchers.IO) {
        val path = FolderPath.normalize(rawPath) ?: return@withContext
        if (path.isEmpty()) return@withContext
        runCatching { dao.deleteTree(path, sqlLikeUnder(path)) }
        Unit
    }
}
