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

package com.openappslabs.jotter.data.sync

import com.openappslabs.jotter.data.model.Note
import com.openappslabs.jotter.data.model.NoteFormat
import kotlinx.serialization.Serializable

/**
 * Wire format shared with the server and the Android app.
 *
 * `isScratchpad` and `needsSync` are intentionally absent: the general note is
 * device-local and the flag is local bookkeeping.
 */
@Serializable
data class SyncNoteDto(
    val uuid: String = "",
    val title: String = "",
    val content: String = "",
    val category: String = "",
    val createdTime: Long = 0L,
    val updatedTime: Long = 0L,
    val isPinned: Boolean = false,
    val isLocked: Boolean = false,
    val isTrashed: Boolean = false,
    val openInEditMode: Boolean? = null,
    val isEncrypted: Boolean = false,
    val encryptionSalt: String? = null,
    val encryptedContent: String? = null,
    val format: String = NoteFormat.TEXT.key
)

@Serializable
data class SyncDeletionDto(
    val uuid: String = "",
    val deletedAt: Long = 0L
)

@Serializable
data class SyncRequestDto(
    val cursor: Long = 0L,
    /** Tells the server which device is talking; shown on its page. */
    val deviceId: String = "",
    val deviceName: String = "",
    val notes: List<SyncNoteDto> = emptyList(),
    val deletions: List<SyncDeletionDto> = emptyList()
)

@Serializable
data class SyncResponseDto(
    val cursor: Long = 0L,
    val serverTime: Long = 0L,
    val notes: List<SyncNoteDto> = emptyList(),
    val deletions: List<SyncDeletionDto> = emptyList(),
    val categories: List<String> = emptyList()
)

@Serializable
data class SyncErrorDto(
    val error: String = ""
)

/**
 * The raw database row becomes the wire shape.
 *
 * An encrypted note is already stored with a blank title and content, and this
 * guard keeps it that way even if a future screen forgets: what travels is the
 * salt and the AES-GCM payload only. The server (and the network) never see the
 * note's password or its plain text.
 */
fun Note.toSyncDto(): SyncNoteDto = SyncNoteDto(
    uuid = uuid,
    title = if (isEncrypted) "" else title,
    content = if (isEncrypted) "" else content,
    category = category,
    createdTime = createdTime,
    updatedTime = updatedTime,
    isPinned = isPinned,
    isLocked = isLocked,
    isTrashed = isTrashed,
    openInEditMode = openInEditMode,
    isEncrypted = isEncrypted,
    encryptionSalt = encryptionSalt,
    encryptedContent = encryptedContent,
    format = format
)

/**
 * The other direction. [id] is the local primary key when updating a row that
 * already exists; [needsSync] is always false because this came from the server.
 */
fun SyncNoteDto.toNote(
    id: Int = 0,
    isArchived: Boolean = false,
    needsSync: Boolean = false
): Note = Note(
    id = id,
    title = title,
    content = content,
    category = category,
    createdTime = createdTime,
    updatedTime = updatedTime,
    isPinned = isPinned,
    isLocked = isLocked,
    isArchived = isArchived,
    isTrashed = isTrashed,
    openInEditMode = openInEditMode,
    isEncrypted = isEncrypted,
    encryptionSalt = encryptionSalt,
    encryptedContent = encryptedContent,
    format = format,
    isScratchpad = false,
    uuid = uuid,
    needsSync = needsSync
)
