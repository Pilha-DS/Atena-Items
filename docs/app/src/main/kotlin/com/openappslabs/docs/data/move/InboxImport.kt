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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** What one import session did, counted instead of listed. */
data class ImportReport(
    /** Files that now live in the inbox. */
    val imported: Int = 0,
    /** Of those, how many had to be renamed because the name was taken. */
    val renamed: Int = 0,
    /** Sources that were not a regular file, or were already in the inbox. */
    val skipped: Int = 0,
    val failed: Int = 0
) {
    val total: Int get() = imported + skipped + failed
    val isEmpty: Boolean get() = total == 0
}

/**
 * Brings files from anywhere on the computer into the inbox.
 *
 * The file chooser is a desktop dialog, so by the time this runs the user has
 * already picked real paths — often in `~/Downloads`, sometimes on another
 * mount. Two decisions make the operation safe to repeat:
 *
 *  - **moving, not copying**: the file leaves the place it was dumped and lands
 *    in `init/`; keeping a copy would leave the same document in two places and
 *    two indexes;
 *  - **never overwriting**: a name already used in the inbox becomes
 *    `nome (2).pdf`, so importing the same file twice does not destroy the
 *    first one.
 *
 * Nothing is created besides the inbox itself ([Inbox.ensure]), and a source
 * that is not a regular file — a folder, a broken symlink, something already in
 * the inbox — is skipped instead of aborting the batch.
 */
class InboxImport(private val root: Path) {

    suspend fun import(sources: List<Path>): ImportReport = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) return@withContext ImportReport()

        val directory = Inbox.ensure(root).toAbsolutePath().normalize()
        if (!Files.isDirectory(directory)) {
            return@withContext ImportReport(failed = sources.size)
        }

        var imported = 0
        var renamed = 0
        var skipped = 0
        var failed = 0

        for (raw in sources) {
            val source = raw.toAbsolutePath().normalize()
            if (!Files.isRegularFile(source) || source.startsWith(directory)) {
                skipped += 1
                continue
            }
            val name = source.fileName?.toString()
            if (name.isNullOrBlank()) {
                skipped += 1
                continue
            }
            val target = uniqueName(directory, name)
            if (target != directory.resolve(name)) renamed += 1

            if (move(source, target)) imported += 1 else failed += 1
        }

        ImportReport(imported = imported, renamed = renamed, skipped = skipped, failed = failed)
    }

    /**
     * `relatorio.pdf` -> `relatorio (2).pdf` when the name is taken.
     *
     * The extension stays at the end so the file keeps opening in the right
     * application; a dotfile or a name without extension just gets the suffix.
     */
    private fun uniqueName(directory: Path, name: String): Path {
        val first = directory.resolve(name)
        if (!Files.exists(first)) return first

        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var copy = 2
        while (true) {
            val candidate = directory.resolve("$base ($copy)$extension")
            if (!Files.exists(candidate)) return candidate
            copy += 1
        }
    }

    /**
     * Renames the file into the inbox.
     *
     * Within one filesystem this is a rename. Across a mount point the JDK
     * copies and removes on its own, but an explicit copy is the fallback for
     * the cases where it does not: the source is only deleted after the copy
     * is complete, and a half-written destination is removed.
     */
    private fun move(source: Path, target: Path): Boolean {
        val renamed = runCatching { Files.move(source, target) }
        if (renamed.isSuccess) return true

        val copied = runCatching {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
        }
        if (copied.isFailure) {
            runCatching { Files.deleteIfExists(target) }
            return false
        }
        // The copy is complete; a failed delete leaves a duplicate instead of
        // losing the file, which is reported as a failure but costs nothing.
        val removed = runCatching { Files.delete(source) }
        return removed.isSuccess
    }
}
