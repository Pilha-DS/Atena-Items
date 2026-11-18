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

package com.openappslabs.docs.data.move

import com.openappslabs.docs.data.model.FolderDescription
import com.openappslabs.docs.data.model.FolderTag
import com.openappslabs.docs.data.source.DocDao
import com.openappslabs.docs.data.source.FolderDescriptionDao
import com.openappslabs.docs.data.source.FolderTagDao
import com.openappslabs.docs.data.source.sqlLikeUnder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** Why a folder was not moved; the screen turns it into a sentence. */
enum class FolderMoveIssue {
    /** `..`, `~`, hidden segment, or not inside the library. */
    INVALID,

    /** Nothing is there any more. */
    NOT_FOUND,

    /** The top of the library is not a folder that can be dragged. */
    ROOT,

    /** `init` is recreated on every scan; it cannot move or receive a drop. */
    INBOX,

    /** Dropping a folder on itself or on a descendant would loop the tree. */
    INTO_SELF,

    /** Something with that name is already in the destination. */
    TARGET_TAKEN,

    /** The move would push a path past [FolderPath.MAX_DEPTH]. */
    TOO_DEEP,

    /** The filesystem refused. */
    FAILED
}

sealed interface FolderMoveOutcome {
    data class Moved(val from: String, val to: String) : FolderMoveOutcome
    data class Rejected(val issue: FolderMoveIssue) : FolderMoveOutcome
}

/**
 * Moves one folder of the library into another (or to the root), with
 * everything inside it.
 *
 * Timid on purpose, like [DocMover]:
 *
 *  - every refusal is decided before the first write;
 *  - `init` and the library root cannot be the source; `init` also cannot be
 *    the parent that receives the drop;
 *  - a folder cannot land on itself or under itself;
 *  - nothing is overwritten — a name collision is a refusal;
 *  - the index and the folder metadata (descriptions, inherited tags) follow
 *    the path change in the same call, so the sidebar does not keep a ghost
 *    description on the old name.
 */
class FolderMover(
    private val root: Path,
    private val dao: DocDao,
    private val descriptions: FolderDescriptionDao? = null,
    private val folderTags: FolderTagDao? = null
) {

    /**
     * Moves [rawSource] so it becomes a child of [rawParent].
     *
     * [rawParent] empty means the library root. The leaf name of the source is
     * kept: `a/b` into `c` becomes `c/b`.
     */
    suspend fun moveTo(rawSource: String, rawParent: String): FolderMoveOutcome =
        withContext(Dispatchers.IO) {
            val source = FolderPath.normalize(rawSource)
                ?: return@withContext FolderMoveOutcome.Rejected(FolderMoveIssue.INVALID)
            if (source.isEmpty()) {
                return@withContext FolderMoveOutcome.Rejected(FolderMoveIssue.ROOT)
            }
            if (source.equals(Inbox.FOLDER, ignoreCase = true)) {
                return@withContext FolderMoveOutcome.Rejected(FolderMoveIssue.INBOX)
            }

            val parent = FolderPath.normalize(rawParent)
                ?: return@withContext FolderMoveOutcome.Rejected(FolderMoveIssue.INVALID)
            if (parent.equals(Inbox.FOLDER, ignoreCase = true)) {
                return@withContext FolderMoveOutcome.Rejected(FolderMoveIssue.INBOX)
            }

            val name = source.substringAfterLast(FolderPath.SEPARATOR)
            val destination = FolderPath.join(parent, name)
            if (destination == source) {
                return@withContext FolderMoveOutcome.Rejected(FolderMoveIssue.INTO_SELF)
            }
            if (isInside(destination, source)) {
                return@withContext FolderMoveOutcome.Rejected(FolderMoveIssue.INTO_SELF)
            }
            if (FolderPath.normalize(destination) == null || depth(destination) > FolderPath.MAX_DEPTH) {
                return@withContext FolderMoveOutcome.Rejected(FolderMoveIssue.TOO_DEEP)
            }

            val sourceDir = FolderPath.resolve(root, source)
                ?: return@withContext FolderMoveOutcome.Rejected(FolderMoveIssue.INVALID)
            if (!Files.isDirectory(sourceDir)) {
                return@withContext FolderMoveOutcome.Rejected(FolderMoveIssue.NOT_FOUND)
            }

            val parentDir = FolderPath.resolve(root, parent)
                ?: return@withContext FolderMoveOutcome.Rejected(FolderMoveIssue.INVALID)
            val destDir = parentDir.resolve(name).normalize()
            if (!destDir.startsWith(root.toAbsolutePath().normalize())) {
                return@withContext FolderMoveOutcome.Rejected(FolderMoveIssue.INVALID)
            }
            if (Files.exists(destDir)) {
                return@withContext FolderMoveOutcome.Rejected(FolderMoveIssue.TARGET_TAKEN)
            }

            // Deepest remapped path under the source must still fit the limit.
            val extra = deepestRelativeDepth(sourceDir)
            if (depth(destination) + extra > FolderPath.MAX_DEPTH) {
                return@withContext FolderMoveOutcome.Rejected(FolderMoveIssue.TOO_DEEP)
            }

            if (parent.isNotEmpty() && !Files.isDirectory(parentDir)) {
                val created = runCatching { Files.createDirectories(parentDir) }
                if (created.isFailure) {
                    return@withContext FolderMoveOutcome.Rejected(FolderMoveIssue.FAILED)
                }
            }

            val moved = runCatching { Files.move(sourceDir, destDir) }
            if (moved.isFailure) {
                return@withContext FolderMoveOutcome.Rejected(FolderMoveIssue.FAILED)
            }

            remapDocuments(source, destination)
            remapDescriptions(source, destination)
            remapFolderTags(source, destination)

            FolderMoveOutcome.Moved(from = source, to = destination)
        }

    private suspend fun remapDocuments(from: String, to: String) {
        val prefix = sqlLikeUnder(from)
        val rows = runCatching { dao.underPrefix(prefix) }.getOrDefault(emptyList())
        for (doc in rows) {
            val newPath = remapPath(from, to, doc.relPath)
            if (newPath == doc.relPath) continue
            runCatching { dao.update(doc.copy(relPath = newPath)) }
        }
    }

    private suspend fun remapDescriptions(from: String, to: String) {
        val descriptions = descriptions ?: return
        val prefix = sqlLikeUnder(from)
        val rows = runCatching { descriptions.tree(from, prefix) }.getOrDefault(emptyList())
        if (rows.isEmpty()) return
        runCatching { descriptions.deleteTree(from, prefix) }
        for (row in rows) {
            val newPath = remapPath(from, to, row.path)
            runCatching {
                descriptions.put(
                    FolderDescription(
                        path = newPath,
                        description = row.description,
                        updatedTime = row.updatedTime
                    )
                )
            }
        }
    }

    private suspend fun remapFolderTags(from: String, to: String) {
        val folderTags = folderTags ?: return
        val prefix = sqlLikeUnder(from)
        val rows = runCatching { folderTags.tree(from, prefix) }.getOrDefault(emptyList())
        if (rows.isEmpty()) return
        runCatching { folderTags.deleteTree(from, prefix) }
        for (row in rows) {
            val newPath = remapPath(from, to, row.folderPath)
            runCatching {
                folderTags.insert(FolderTag(folderPath = newPath, tagId = row.tagId))
            }
        }
    }

    companion object {
        /** `a/b` under move `a`→`c` becomes `c/b`; the source itself becomes [to]. */
        fun remapPath(from: String, to: String, path: String): String = when {
            path == from -> to
            path.startsWith("$from/") -> to + path.removePrefix(from)
            else -> path
        }

        fun depth(path: String): Int =
            if (path.isEmpty()) 0 else path.count { it == FolderPath.SEPARATOR } + 1

        fun isInside(path: String, folder: String): Boolean =
            path == folder || path.startsWith("$folder${FolderPath.SEPARATOR}")
    }

    /**
     * How many path segments sit below [directory] itself (0 when it has no
     * subfolders). Used so moving a deep tree into a deep parent still hits
     * [FolderPath.MAX_DEPTH].
     */
    private fun deepestRelativeDepth(directory: Path): Int {
        var deepest = 0
        val base = directory.toAbsolutePath().normalize()
        runCatching {
            Files.walk(directory).use { stream ->
                stream.filter { Files.isDirectory(it) }.forEach { dir ->
                    val absolute = dir.toAbsolutePath().normalize()
                    if (absolute == base) return@forEach
                    if (!absolute.startsWith(base)) return@forEach
                    val relative = base.relativize(absolute).toString().replace('\\', '/')
                    if (relative.isEmpty()) return@forEach
                    deepest = maxOf(deepest, depth(relative))
                }
            }
        }
        return deepest
    }
}
