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

@Immutable
@Serializable
@Entity(
    tableName = "notes",
    indices = [
        Index(value = ["isArchived", "isTrashed"]),
        Index(value = ["category"]),
        Index(value = ["isPinned", "updatedTime"]),
        // Shared with the server and the other devices: two rows with the same
        // uuid are the same note, so this must never happen locally.
        Index(value = ["uuid"], unique = true)
    ]
)
data class Note(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val title: String = "",
    val content: String = "",
    val category: String = "",
    val createdTime: Long = System.currentTimeMillis(),
    val updatedTime: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val isLocked: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
    /**
     * Per-note override of the global "default open mode" setting.
     * `null` means "follow the global preference".
     */
    val openInEditMode: Boolean? = null,
    /**
     * Per-note encryption. When `true`, [title] and [content] are blank in the
     * database and only [encryptionSalt] + [encryptedContent] hold data, locked
     * by this note's own password (independent from the app/global lock).
     */
    val isEncrypted: Boolean = false,
    /** Base64 PBKDF2 salt used to derive this note's AES key. */
    val encryptionSalt: String? = null,
    /** Base64 AES-GCM payload (iv + ciphertext) of the title and content. */
    val encryptedContent: String? = null,
    /**
     * Renderer used when this note is read (plain text, Markdown, HTML, JSON,
     * XML). Only affects display: [content] always holds plain text.
     */
    val format: String = NoteFormat.TEXT.key,
    /**
     * The single "general note" (scratchpad). It is a normal note row, but it
     * lives outside the list: it is edited from its own screen and synced under
     * the fixed [Scratchpad.UUID]. Only one row ever has this set.
     */
    val isScratchpad: Boolean = false,
    /**
     * Stable identity of this note across devices and the sync server. It is
     * generated when the note is created and never changes; `id` stays local
     * to this database.
     */
    val uuid: String = "",
    /**
     * Local-only bookkeeping: this row changed and the server has not confirmed
     * it yet. It is not sent on the wire and is cleared after a successful push.
     */
    val needsSync: Boolean = false
)