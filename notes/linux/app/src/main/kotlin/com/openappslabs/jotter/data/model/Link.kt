/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Jotter is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Jotter.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.jotter.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A link saved for later: the "link inbox".
 *
 * It is not a note on purpose. A note is a document with a title, a body and a
 * category; a link is one URL you saw and want back in two taps. Keeping them
 * apart is what makes the capture instant and the list cheap to read, and it is
 * why the links travel on their own sync stream.
 */
@Immutable
@Serializable
@Entity(
    tableName = "links",
    indices = [
        Index(value = ["uuid"], unique = true),
        // Ranking reads `MAX(lastOpenedTime, createdTime)`; this keeps the
        // "recently opened" half of it indexed.
        Index(value = ["lastOpenedTime"])
    ]
)
data class Link(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    /** Normalized absolute URL: scheme and host lowercase, no default port. */
    val url: String = "",
    /** Optional label. Blank means "show the URL" in the list. */
    val title: String = "",
    val createdTime: Long = System.currentTimeMillis(),
    val updatedTime: Long = System.currentTimeMillis(),
    /**
     * When this link was last opened. It ranks the list, so what you actually
     * use floats to the top even if it was saved months ago.
     */
    val lastOpenedTime: Long = 0L,
    val openCount: Int = 0,
    /** Stable identity across devices and the hub. `id` stays local. */
    val uuid: String = "",
    /** Local-only bookkeeping: changed here, not yet confirmed by the server. */
    val needsSync: Boolean = false
)

/** A hard deletion of a link, announced to the server on the next sync. */
@Entity(tableName = "deleted_links")
data class DeletedLink(
    @PrimaryKey
    val uuid: String,
    val deletedAt: Long
)
