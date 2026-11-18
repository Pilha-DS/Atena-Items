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
 * A search alias of a tag (`nota-fiscal` → `nfe`).
 *
 * Not a hierarchy: the path of the tag stays the identifier, and the alias is
 * only another string the universal search matches. Unique per tag so typing
 * the same nickname twice does nothing.
 */
@Immutable
@Entity(
    tableName = "tag_aliases",
    indices = [
        Index(value = ["tagId", "alias"], unique = true),
        Index(value = ["alias"])
    ]
)
data class TagAlias(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val tagId: Long,
    val alias: String
)

/** One alias with the tag path, for the search index and the dialog. */
data class TagAliasRow(
    val tagPath: String,
    val alias: String
)
