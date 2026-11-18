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

import com.openappslabs.docs.data.model.DocKind
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** One regular file found on disk, with only what a `stat` gives. */
data class ScannedFile(
    val relPath: String,
    val name: String,
    val kind: DocKind,
    val size: Long,
    val modifiedTime: Long
)

data class ScanResult(
    val files: List<ScannedFile>,
    /** Folders/files that existed but could not be read (permissions). */
    val unreadable: Int
)

/**
 * Walks the library root and describes every regular file.
 *
 * Deliberately cheap: one visit per file, `BasicFileAttributes` already
 * carries size and mtime, and nothing here reads a byte of content or follows
 * a symlink. A link is not a document we own, and following one can loop or
 * escape the tree.
 *
 * Hidden entries are skipped: the library is a user folder, and `.git`,
 * `.cache` or a `.thumbnails` directory has no business in a document list.
 * [excludes] adds names that are not hidden but are never documents.
 */
object DocScanner {

    val DEFAULT_EXCLUDES: Set<String> = setOf("node_modules")

    fun scan(root: Path, excludes: Set<String> = DEFAULT_EXCLUDES): ScanResult {
        if (!Files.isDirectory(root)) return ScanResult(emptyList(), 0)

        val files = ArrayList<ScannedFile>()
        var unreadable = 0

        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir == root) return FileVisitResult.CONTINUE
                    val name = dir.fileName?.toString() ?: return FileVisitResult.CONTINUE
                    if (name.startsWith(".") || name in excludes) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                    val name = file.fileName?.toString() ?: return FileVisitResult.CONTINUE
                    if (name.startsWith(".")) return FileVisitResult.CONTINUE
                    files.add(
                        ScannedFile(
                            relPath = root.relativize(file).toString(),
                            name = name,
                            kind = DocKind.fromFileName(name),
                            size = attrs.size(),
                            modifiedTime = attrs.lastModifiedTime().toMillis()
                        )
                    )
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    unreadable += 1
                    return FileVisitResult.CONTINUE
                }
            }
        )

        // Deterministic order: the indexer and its tests both rely on it.
        files.sortBy { it.relPath }
        return ScanResult(files, unreadable)
    }
}
