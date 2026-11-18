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

import com.openappslabs.docs.data.model.Doc
import com.openappslabs.docs.data.model.DocStatus
import com.openappslabs.docs.data.source.DocDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Why a move did not happen; the screen turns it into a sentence. */
enum class MoveIssue {
    /** The folder is not a usable path inside the library. */
    INVALID_FOLDER,

    /** The document is already in that folder: nothing to do. */
    ALREADY_THERE,

    /** The file is no longer on disk where the index saw it. */
    SOURCE_MISSING,

    /** Something with that name is already in the destination. */
    TARGET_TAKEN,

    /** The filesystem refused; the message says nothing about the cause. */
    FAILED
}

sealed interface MoveOutcome {
    data class Moved(val from: String, val to: String) : MoveOutcome
    data class Rejected(val issue: MoveIssue) : MoveOutcome
}

/**
 * Moves one document into a folder of the library, creating the folder.
 *
 * This is the only code in the app that writes to the user's tree, so it is
 * deliberately timid:
 *
 *  - every check runs *before* the first write (path usable, file present,
 *    nothing at the destination), so a refused move leaves the disk untouched;
 *  - nothing is ever overwritten — a name collision is a refusal, never a
 *    `REPLACE_EXISTING`;
 *  - the folder is created only after the checks, and removed again if the move
 *    itself fails, so a failed attempt does not leave an empty folder behind;
 *  - `Files.move` within one filesystem is a rename: same inode, same content,
 *    tags and favorite stay on the same row.
 *
 * The index is updated in place, which is cheaper and safer than waiting for a
 * scan. If that write were to fail, the file has still moved: the next scan
 * pairs the old row with the new path by size and date and keeps the tags, so
 * the truth on disk wins either way.
 */
class DocMover(
    private val root: Path,
    private val dao: DocDao
) {

    suspend fun moveTo(doc: Doc, folder: String): MoveOutcome = withContext(Dispatchers.IO) {
        val target = FolderPath.normalize(folder)
            ?: return@withContext MoveOutcome.Rejected(MoveIssue.INVALID_FOLDER)
        if (target == doc.folder) return@withContext MoveOutcome.Rejected(MoveIssue.ALREADY_THERE)

        val directory = FolderPath.resolve(root, target)
            ?: return@withContext MoveOutcome.Rejected(MoveIssue.INVALID_FOLDER)
        // The source is a path the index gave us, not a folder the user typed:
        // it must not be filtered by the folder rules (`nota~final.pdf` is a
        // legal file name even though `~` is not allowed in a folder).
        val base = root.toAbsolutePath().normalize()
        val source = base.resolve(doc.relPath).normalize()
        if (!source.startsWith(base) || !Files.isRegularFile(source)) {
            return@withContext MoveOutcome.Rejected(MoveIssue.SOURCE_MISSING)
        }

        val relPath = FolderPath.join(target, doc.name)
        // A row already pointing at the destination would break the unique
        // index after the move, and would be a second identity for one file.
        if (dao.findByPath(relPath) != null) {
            return@withContext MoveOutcome.Rejected(MoveIssue.TARGET_TAKEN)
        }
        val destination = directory.resolve(doc.name)
        if (Files.exists(destination)) {
            return@withContext MoveOutcome.Rejected(MoveIssue.TARGET_TAKEN)
        }

        val createdDirectory = !Files.isDirectory(directory)
        val prepared = runCatching {
            if (createdDirectory) Files.createDirectories(directory)
        }
        if (prepared.isFailure) return@withContext MoveOutcome.Rejected(MoveIssue.FAILED)

        val moved = runCatching { Files.move(source, destination) }
        if (moved.isFailure) {
            if (createdDirectory) pruneEmpty(directory)
            return@withContext MoveOutcome.Rejected(MoveIssue.FAILED)
        }

        // Across filesystems `Files.move` is a copy: the size and the date are
        // read again instead of assumed.
        val attributes = runCatching {
            Files.readAttributes(destination, BasicFileAttributes::class.java)
        }.getOrNull()
        runCatching {
            dao.update(
                doc.copy(
                    relPath = relPath,
                    size = attributes?.size() ?: doc.size,
                    modifiedTime = attributes?.lastModifiedTime()?.toMillis() ?: doc.modifiedTime,
                    indexedTime = System.currentTimeMillis(),
                    status = DocStatus.PRESENT
                )
            )
        }
        MoveOutcome.Moved(from = doc.relPath, to = relPath)
    }

    /**
     * Deletes [directory] and its empty parents up to the root.
     *
     * Only used after a failed move and only while the directory is empty, so
     * it cannot remove anything the user put there.
     */
    private fun pruneEmpty(directory: Path) {
        val base = root.toAbsolutePath().normalize()
        var current: Path? = directory
        while (current != null && current.startsWith(base) && current != base) {
            val empty = runCatching {
                Files.newDirectoryStream(current).use { !it.iterator().hasNext() }
            }.getOrDefault(false)
            if (!empty) return
            if (runCatching { Files.deleteIfExists(current) }.isFailure) return
            current = current.parent
        }
    }
}
