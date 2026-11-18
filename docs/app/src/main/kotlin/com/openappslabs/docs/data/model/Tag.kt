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
import androidx.room.PrimaryKey

/**
 * One label, stored as a full path (`academico/faculdade`).
 *
 * The hierarchy is the path itself, not a parent id: a tag can never be lost
 * because its parent was deleted, and filtering by a parent is a prefix match.
 * [name] repeats the last segment so the UI does not have to split on every
 * frame.
 *
 * [description] is the sentence the user wrote about what the tag means, and it
 * lives with the tag on purpose: deleting the tag takes it along, and there is
 * no row left pointing at something that no longer exists. Empty means "no
 * description".
 */
@Immutable
@Entity(
    tableName = "tags",
    indices = [Index(value = ["path"], unique = true)]
)
data class Tag(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val path: String,
    val name: String = path.substringAfterLast('/'),
    val createdTime: Long = System.currentTimeMillis(),
    val description: String = ""
)

/**
 * A tag applied to a document.
 *
 * No foreign keys on purpose: documents are never deleted in this stage (a
 * vanished file becomes `MISSING`), so there is nothing to cascade, and the
 * table stays a plain join. If deletion ever happens, the cleanup belongs in
 * the code that deletes.
 */
@Immutable
@Entity(
    tableName = "doc_tags",
    primaryKeys = ["docId", "tagId"],
    indices = [Index(value = ["tagId"])]
)
data class DocTag(
    val docId: Long,
    val tagId: Long
)

/** Row of the "which documents have which tags" query. */
data class DocTagRow(
    val docId: Long,
    val path: String
)

/** How many documents — still present — carry one tag. */
data class TagCount(
    val tagId: Long,
    val count: Int
)

/**
 * One tag and a document that carries it.
 *
 * Only used as an example in the prompt: a bare list of tag paths is a
 * vocabulary without meaning, and the model cannot tell what `unip` covers
 * without seeing the documents it was applied to.
 */
data class TagDocRow(
    val path: String,
    val name: String
)
