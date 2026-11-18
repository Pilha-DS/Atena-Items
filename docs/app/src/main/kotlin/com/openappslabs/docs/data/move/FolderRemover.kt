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

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Why a folder was not removed; the dialog turns it into a sentence. */
enum class RemoveIssue {
    /** `..`, `~`, a hidden segment, too deep, or not inside the library. */
    INVALID,

    /** Nothing is there any more: it was deleted by hand or by another app. */
    NOT_FOUND,

    /** The top of the library is the library; it cannot be deleted. */
    ROOT,

    /** `init` is recreated on every scan, so deleting it would be a lie. */
    INBOX,

    /** The filesystem refused part of the walk; [RemoveOutcome.Rejected.files] says what went. */
    FAILED
}

sealed interface RemoveOutcome {
    data class Removed(val path: String, val files: Int) : RemoveOutcome

    /** [files] is how many entries were already deleted when it gave up. */
    data class Rejected(val issue: RemoveIssue, val files: Int = 0) : RemoveOutcome
}

/**
 * Deletes a folder of the library — and everything inside it — from the disk.
 *
 * This is the only code in the app that *removes* from the user's tree, so it
 * is more careful than [FolderCreator]:
 *
 *  - the root and the inbox are refused here, in the data layer, not only by
 *    the dialog that hides the button;
 *  - `FolderPath` rules apply, and the resolved directory must still be inside
 *    the library after normalization;
 *  - symlinks are not followed: a link inside the folder is deleted as a link,
 *    never as its target, so a folder cannot drag another part of the disk with
 *    it;
 *  - nothing is deleted before [count] answers, because the confirmation dialog
 *    has to say how much is at stake.
 *
 * The index is not touched here. A deleted file becomes a `MISSING` row on the
 * next scan, which keeps its tags if the person restores a backup — the same
 * rule a file moved outside the library already follows.
 */
class FolderRemover(private val root: Path) {

    /** Entries below [folder] (files, at any depth). 0 when there is nothing. */
    fun count(raw: String): Int {
        val directory = resolve(raw) ?: return 0
        if (!isRealDirectory(directory)) return 0
        var files = 0
        runCatching {
            Files.walkFileTree(
                directory,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        files += 1
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE
                }
            )
        }
        return files
    }

    fun remove(raw: String): RemoveOutcome {
        val path = FolderPath.normalize(raw)
            ?: return RemoveOutcome.Rejected(RemoveIssue.INVALID)
        if (path.isEmpty()) return RemoveOutcome.Rejected(RemoveIssue.ROOT)
        if (path.equals(Inbox.FOLDER, ignoreCase = true)) {
            return RemoveOutcome.Rejected(RemoveIssue.INBOX)
        }
        val directory = FolderPath.resolve(root, path)
            ?: return RemoveOutcome.Rejected(RemoveIssue.INVALID)
        if (!isRealDirectory(directory)) return RemoveOutcome.Rejected(RemoveIssue.NOT_FOUND)

        var files = 0
        runCatching {
            Files.walkFileTree(
                directory,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (runCatching { Files.deleteIfExists(file) }.isSuccess) files += 1
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        if (exc == null) runCatching { Files.deleteIfExists(dir) }
                        return FileVisitResult.CONTINUE
                    }
                }
            )
        }

        // The disk is the answer, not the counters: a folder that is still
        // there was not removed, whatever the walk reported. `files` says how
        // much went before it gave up.
        return if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            RemoveOutcome.Rejected(RemoveIssue.FAILED, files)
        } else {
            RemoveOutcome.Removed(path, files)
        }
    }

    /** `null` when [raw] is not a usable path; the root itself included. */
    private fun resolve(raw: String): Path? {
        val path = FolderPath.normalize(raw) ?: return null
        if (path.isEmpty()) return null
        return FolderPath.resolve(root, path)
    }

    /**
     * A directory on disk — not a symlink and not a file that took its name.
     *
     * `NOFOLLOW_LINKS` is the whole point: deleting through a link would empty
     * a folder outside the library that the walk was never allowed to see.
     */
    private fun isRealDirectory(directory: Path): Boolean =
        Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
}
