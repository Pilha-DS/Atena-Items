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
import androidx.room.PrimaryKey

/**
 * What the user says a folder is for: "certificados de cursos de extensão".
 *
 * A folder has no row in the index (it exists on the disk), so this is its own
 * table keyed by the relative path — the same spelling the sidebar uses, so no
 * translation is needed. The description belongs to the *path*: rename the
 * folder in Dolphin and the note stays behind, which is documented and honest,
 * since the app has no way to know the new name is the same folder.
 *
 * Kept apart from the disk on purpose: the folder is the user's, the sentence
 * about it is the index's. Nothing here is ever written into the library.
 *
 * [description] is never empty in a stored row — clearing it deletes the row,
 * so "has a description" is one question.
 */
@Immutable
@Entity(tableName = "folder_descriptions")
data class FolderDescription(
    @PrimaryKey
    val path: String,
    val description: String,
    val updatedTime: Long = System.currentTimeMillis()
)
