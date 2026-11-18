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
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** Whether the file is still on disk where the last scan saw it. */
enum class DocStatus {
    PRESENT,
    MISSING
}

/**
 * One file of the real tree, as last seen by the scan.
 *
 * [relPath] is relative to the library root, never absolute: moving the whole
 * folder (or mounting it elsewhere) keeps every row valid, and the absolute
 * path is recomputed from the current root when it is needed.
 *
 * [uuid] is the document identity that survives a move, a rename and, later,
 * the sync with the hub. [size] and [modifiedTime] are the change detector:
 * they are read with a single `stat` during the scan, and only a difference
 * makes the row be written again.
 */
@Immutable
@Entity(
    tableName = "documents",
    indices = [
        Index(value = ["relPath"], unique = true),
        Index(value = ["name"]),
        Index(value = ["kind"]),
        Index(value = ["modifiedTime"]),
        Index(value = ["status"])
    ]
)
data class Doc(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val relPath: String,
    val name: String,
    val kind: DocKind,
    val size: Long,
    val modifiedTime: Long,
    val indexedTime: Long = System.currentTimeMillis(),
    val status: DocStatus = DocStatus.PRESENT,
    /**
     * Marked by the user, not by the scan, so a rescan keeps it.
     *
     * The default is declared on the entity — and not only in the migration —
     * because Room validates the schema on open: the `ALTER TABLE` that added
     * this column had to write a default, so the entity must expect one.
     */
    @ColumnInfo(defaultValue = "0")
    val favorite: Boolean = false,
    /**
     * How often the person opened this file from the window.
     *
     * The scan never touches these: a rescan keeps the habit score that feeds
     * the fuzzy ranking (same idea as the launcher frecency).
     */
    @ColumnInfo(defaultValue = "0")
    val visitCount: Int = 0,
    /** Epoch millis of the last open from the window; 0 means never. */
    @ColumnInfo(defaultValue = "0")
    val lastVisitedAt: Long = 0
) {
    /** Folder of the document inside the library, "" when it is at the root. */
    val folder: String
        get() {
            val slash = relPath.lastIndexOf('/')
            return if (slash <= 0) "" else relPath.substring(0, slash)
        }
}

/** Id and relative path of a present document, for inheritance counts. */
data class DocIdPath(
    val id: Long,
    val relPath: String
) {
    val folder: String
        get() {
            val slash = relPath.lastIndexOf('/')
            return if (slash <= 0) "" else relPath.substring(0, slash)
        }
}
