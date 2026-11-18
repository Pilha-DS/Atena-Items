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

package com.openappslabs.docs.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index

/**
 * A tag a folder passes to every document inside it (and in its subfolders).
 *
 * Kept apart from [DocTag] on purpose: inheritance is a property of the folder,
 * not a copy onto each file. Moving a document out of the folder drops the
 * inherited tags without rewriting rows; applying the same tag by hand still
 * uses [DocTag].
 *
 * [folderPath] is the relative path the sidebar uses (`profissional/tebas/
 * notas-fiscais`). The root is never stored — there is nothing useful to inherit
 * from "the whole library".
 */
@Immutable
@Entity(
    tableName = "folder_tags",
    primaryKeys = ["folderPath", "tagId"],
    indices = [Index(value = ["tagId"])]
)
data class FolderTag(
    val folderPath: String,
    val tagId: Long
)

/** One inherited tag as the UI and the resolver see it. */
data class FolderTagRow(
    val folderPath: String,
    val tagPath: String,
    val tagId: Long
)
