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

package com.openappslabs.docs.data.tags

import com.openappslabs.docs.data.tree.PathTree

/**
 * Resolves which tags a document inherits from the folder it lives in.
 *
 * Inheritance walks the folder chain (`a` + `a/b` + `a/b/c`): a tag declared on
 * `notas-fiscais` reaches every file under it, including nested subfolders, and
 * a tag on a parent folder reaches the same files too. The root (`""`) never
 * contributes — there is no "inherit for the whole library" row.
 *
 * Pure functions on purpose: the controller and the tests share the same rules
 * without opening the database.
 */
object FolderTagInherit {

    /**
     * The folder itself and every ancestor, outermost first.
     * Empty folder (library root) yields an empty list.
     */
    fun folderChain(folder: String): List<String> {
        if (folder.isEmpty()) return emptyList()
        return PathTree.ancestors(folder) + folder
    }

    /**
     * Tag paths inherited by a document whose [folder] is the relative folder
     * of its file. [byFolder] maps each folder path to the tags declared on it.
     */
    fun resolve(folder: String, byFolder: Map<String, List<String>>): List<String> {
        if (folder.isEmpty() || byFolder.isEmpty()) return emptyList()
        return folderChain(folder)
            .flatMap { byFolder[it].orEmpty() }
            .distinct()
            .sorted()
    }

    /**
     * How many PRESENT documents inherit each tag path (exact leaf), without
     * counting a document twice when several ancestor folders declare the same
     * tag.
     *
     * [docFolders] is the folder of each present document (one entry per doc).
     * [byFolder] is the same map [resolve] uses. The result is merged with the
     * explicit `doc_tags` counts by the caller (union of document sets).
     */
    fun inheritedDocFoldersByTag(
        docFolders: Collection<String>,
        byFolder: Map<String, List<String>>
    ): Map<String, Int> {
        if (docFolders.isEmpty() || byFolder.isEmpty()) return emptyMap()
        val counts = HashMap<String, Int>()
        for (folder in docFolders) {
            for (tag in resolve(folder, byFolder)) {
                counts[tag] = (counts[tag] ?: 0) + 1
            }
        }
        return counts
    }

    /** True when [docFolder] is [declaredFolder] or a subfolder of it. */
    fun isUnder(docFolder: String, declaredFolder: String): Boolean {
        if (declaredFolder.isEmpty()) return false
        return docFolder == declaredFolder ||
            docFolder.startsWith("$declaredFolder/")
    }
}
