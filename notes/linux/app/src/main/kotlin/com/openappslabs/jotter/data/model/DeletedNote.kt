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

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A note that was permanently deleted on this device but still has to be
 * announced to the server.
 *
 * Deleting the row alone would not work: the other device (or the server)
 * would simply push the note back on the next sync. The tombstone is what
 * makes the deletion travel, and it is removed only after the server confirms.
 */
@Entity(tableName = "deleted_notes")
data class DeletedNote(
    @PrimaryKey
    val uuid: String,
    /** Client clock of the deletion, compared with `updatedTime` on the server. */
    val deletedAt: Long = System.currentTimeMillis()
)
