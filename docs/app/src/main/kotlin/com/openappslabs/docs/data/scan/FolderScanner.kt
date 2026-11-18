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

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Lists every directory of the library, empty ones included.
 *
 * The document index cannot answer this: it has one row per file, so a folder
 * the user just created — or one waiting to be filled — simply does not exist
 * there. The sidebar needs the disk, and this is the cheapest way to ask.
 *
 * Same rules as [DocScanner], because both describe the same tree: hidden
 * entries and [DocScanner.DEFAULT_EXCLUDES] are skipped, and a symlink is not
 * followed, so the walk cannot loop or leave the library.
 *
 * Paths are relative to the root, use `/`, and the root itself is not in the
 * list — it is the "Todos os documentos" row, not a folder.
 */
object FolderScanner {

    fun scan(root: Path, excludes: Set<String> = DocScanner.DEFAULT_EXCLUDES): List<String> {
        if (!Files.isDirectory(root)) return emptyList()

        val folders = ArrayList<String>()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir == root) return FileVisitResult.CONTINUE
                    val name = dir.fileName?.toString() ?: return FileVisitResult.CONTINUE
                    if (name.startsWith(".") || name in excludes) return FileVisitResult.SKIP_SUBTREE
                    folders.add(root.relativize(dir).toString())
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            }
        )

        // Deterministic order: the tree sorts by name anyway, and the tests
        // compare lists.
        folders.sort()
        return folders
    }
}
