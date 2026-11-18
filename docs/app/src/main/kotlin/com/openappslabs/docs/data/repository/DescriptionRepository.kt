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

import com.openappslabs.docs.data.model.FolderDescription
import com.openappslabs.docs.data.move.FolderPath
import com.openappslabs.docs.data.source.FolderDescriptionDao
import com.openappslabs.docs.data.source.TagDao
import com.openappslabs.docs.data.source.sqlLikeUnder
import com.openappslabs.docs.data.tags.TagPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The sentences the user writes about a folder or a tag, so the model knows
 * what they are *for* instead of guessing from the name alone.
 *
 * Two folders can be called `Original` and `Certificados` and only the person
 * knows which one holds the signed certificate; one written line turns a guess
 * into an instruction. That is why the description goes into the prompt ahead of
 * the file names, and why the text heuristics match against it too — it is the
 * user's own vocabulary, not the model's.
 *
 * The rules are the same for both kinds:
 *
 *  - the text is trimmed and its line breaks become spaces (a description is a
 *    sentence, not a document);
 *  - a description lives only on something that exists: a normalized folder
 *    path, or a tag row that is already there;
 *  - emptying the field deletes the row, so "has a description" never means
 *    "has a row with an empty one".
 *
 * All text is stored in SQLite. Nothing is written into the library folder: a
 * note file next to the documents would show up in the scan and in Dolphin.
 */
class DescriptionRepository(
    private val folders: FolderDescriptionDao,
    private val tags: TagDao
) {

    /** Folder path to description, for the sidebar and for the prompts. */
    fun observeFolders(): Flow<Map<String, String>> = folders.observeAll()
        .map { rows -> rows.filter { it.description.isNotBlank() }.associate { it.path to it.description } }

    /** One-shot read, for the prompt of a suggestion being asked for now. */
    suspend fun folderNotes(): Map<String, String> = withContext(Dispatchers.IO) {
        runCatching { folders.all() }
            .getOrDefault(emptyList())
            .filter { it.description.isNotBlank() }
            .associate { it.path to it.description }
    }

    suspend fun tagNotes(): Map<String, String> = withContext(Dispatchers.IO) {
        runCatching { tags.all() }
            .getOrDefault(emptyList())
            .filter { it.description.isNotBlank() }
            .associate { it.path to it.description }
    }

    /**
     * Saves what the user wrote about the folder, or forgets it when [raw] is
     * blank. False when the folder could not be described (a path the library
     * would refuse, or the root).
     */
    suspend fun describeFolder(rawPath: String, raw: String): Boolean =
        withContext(Dispatchers.IO) {
            val path = FolderPath.normalize(rawPath) ?: return@withContext false
            if (path.isEmpty()) return@withContext false
            val text = clean(raw)
            if (text.isEmpty()) {
                runCatching { folders.delete(path) }.isSuccess
            } else {
                runCatching {
                    folders.put(FolderDescription(path = path, description = text))
                }.isSuccess
            }
        }

    /**
     * Saves or clears the description of an existing tag.
     *
     * An update on a row that is not there would silently do nothing and the
     * dialog would report success, so the tag is looked up first.
     */
    suspend fun describeTag(rawPath: String, raw: String): Boolean =
        withContext(Dispatchers.IO) {
            val path = TagPaths.normalize(rawPath) ?: return@withContext false
            if (runCatching { tags.findByPath(path) }.getOrNull() == null) return@withContext false
            runCatching { tags.setDescription(path, clean(raw)) }.isSuccess
        }

    /**
     * Drops the descriptions of a folder and everything below it.
     *
     * Called when a folder is deleted from the window: the paths are gone from
     * the disk, and a note about `faculdade/2026` reappearing on a brand new
     * folder of the same name months later would be a lie the user cannot
     * explain.
     */
    suspend fun forgetFolder(rawPath: String) = withContext(Dispatchers.IO) {
        val path = FolderPath.normalize(rawPath) ?: return@withContext
        if (path.isEmpty()) return@withContext
        runCatching { folders.deleteTree(path, sqlLikeUnder(path)) }
        Unit
    }

    companion object {
        /** Long enough for a real sentence, short enough to keep a prompt small. */
        const val MAX_LENGTH = 200

        /**
         * The stored form of what was typed: one line, no padding, at most
         * [MAX_LENGTH] characters. Empty means "no description".
         *
         * Line breaks become spaces first, so pasting two paragraphs keeps the
         * words apart instead of gluing them together.
         */
        fun clean(raw: String): String = raw
            .replace(WHITESPACE, " ")
            .filterNot { it.isISOControl() }
            .trim()
            .take(MAX_LENGTH)
            .trim()

        private val WHITESPACE = Regex("\\s+")
    }
}
