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

package com.openappslabs.hub

import kotlinx.serialization.Serializable

/**
 * Wire format shared with the clients.
 *
 * The same JSON is produced by the Android and the Linux app, so every field is
 * optional on the way in: a client that is one version behind still syncs, and
 * unknown fields are ignored by the server.
 */

/** A note as it travels. `updatedTime` is the client clock used for LWW. */
@Serializable
data class NoteDto(
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
    /** Ciphertext only: the server never sees the password or the plain text. */
    val encryptedContent: String? = null,
    val format: String = "text"
)

/** A hard deletion. Soft deletes travel as a normal note with `isTrashed`. */
@Serializable
data class DeletionDto(
    val uuid: String = "",
    val deletedAt: Long = 0L
)

@Serializable
data class SyncRequest(
    /** Server sequence the client already has; `0` means "send me everything". */
    val cursor: Long = 0L,
    /** Stable per-install id, so the server recognizes a returning device. */
    val deviceId: String = "",
    /** Human name the app asks for on first connect ("Pixel 8", "notebook"). */
    val deviceName: String = "",
    val notes: List<NoteDto> = emptyList(),
    val deletions: List<DeletionDto> = emptyList(),
    val categories: List<String> = emptyList()
)

/** A device that has synced at least once. Server-side only, never synced. */
@Serializable
data class DeviceDto(
    val id: String = "",
    val name: String = "",
    val lastSeen: Long = 0L,
    val lastIp: String = ""
)

@Serializable
data class SyncResponse(
    /** Store this and send it as [SyncRequest.cursor] next time. */
    val cursor: Long = 0L,
    /** Server clock, so the client can warn about clock skew hurting LWW. */
    val serverTime: Long = 0L,
    val notes: List<NoteDto> = emptyList(),
    val deletions: List<DeletionDto> = emptyList(),
    val categories: List<String> = emptyList()
)

@Serializable
data class PingResponse(
    val status: String = "ok",
    val serverTime: Long = 0L,
    val version: String = BuildInfo.VERSION
)

@Serializable
data class StatsResponse(
    val notes: Int = 0,
    val trashed: Int = 0,
    val tombstones: Int = 0,
    val categories: Int = 0,
    val links: Int = 0,
    val cursor: Long = 0L,
    val serverTime: Long = 0L,
    /** Which devices have connected, newest first. */
    val devices: List<DeviceDto> = emptyList()
)

@Serializable
data class ErrorResponse(
    val error: String
)

// ---------------------------------------------------------------------------
// Agenda: events, independent of the notes contract.
// ---------------------------------------------------------------------------

/**
 * An event as it travels between the Agenda clients.
 *
 * `updatedTime` is the client clock and decides last-write-wins, exactly like
 * on a note. A recurring series is not expanded: the client stores the events
 * it created, one row each.
 */
@Serializable
data class AgendaEventDto(
    val uuid: String = "",
    val title: String = "",
    val description: String = "",
    val location: String = "",
    /** Epoch millis. An all-day event spans local midnight to local midnight. */
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val allDay: Boolean = false,
    /** Optional accent color, as `#RRGGBB`. */
    val color: String = "",
    val createdTime: Long = 0L,
    val updatedTime: Long = 0L
)

@Serializable
data class AgendaDeletionDto(
    val uuid: String = "",
    val deletedAt: Long = 0L
)

@Serializable
data class AgendaSyncRequest(
    /** Cursor of the agenda stream, not the notes one. `0` means everything. */
    val cursor: Long = 0L,
    val deviceId: String = "",
    val deviceName: String = "",
    val events: List<AgendaEventDto> = emptyList(),
    val deletions: List<AgendaDeletionDto> = emptyList()
)

@Serializable
data class AgendaSyncResponse(
    val cursor: Long = 0L,
    val serverTime: Long = 0L,
    val events: List<AgendaEventDto> = emptyList(),
    val deletions: List<AgendaDeletionDto> = emptyList()
)

// ---------------------------------------------------------------------------
// Links: the Jotter "link inbox", on its own stream.
// ---------------------------------------------------------------------------

/**
 * A saved link as it travels.
 *
 * `url` is what the user pasted, already normalized by the client (scheme and
 * host); [title] is optional. [lastOpenedTime] and [openCount] are usage hints
 * used to rank the list, and they travel so every device ranks the same way.
 */
@Serializable
data class LinkDto(
    val uuid: String = "",
    val url: String = "",
    val title: String = "",
    val createdTime: Long = 0L,
    val updatedTime: Long = 0L,
    val lastOpenedTime: Long = 0L,
    val openCount: Int = 0
)

@Serializable
data class LinkDeletionDto(
    val uuid: String = "",
    val deletedAt: Long = 0L
)

@Serializable
data class LinksSyncRequest(
    /** Cursor of the links stream, not the notes one. `0` means everything. */
    val cursor: Long = 0L,
    val deviceId: String = "",
    val deviceName: String = "",
    val links: List<LinkDto> = emptyList(),
    val deletions: List<LinkDeletionDto> = emptyList()
)

@Serializable
data class LinksSyncResponse(
    val cursor: Long = 0L,
    val serverTime: Long = 0L,
    val links: List<LinkDto> = emptyList(),
    val deletions: List<LinkDeletionDto> = emptyList()
)


// ---------------------------------------------------------------------------
// Hub: the server is a host for several tools, not only the notes sync.
// ---------------------------------------------------------------------------

/** What a tool is, as listed by `GET /api/v1/tools`. */
@Serializable
data class ToolDto(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val version: String = BuildInfo.VERSION
)

@Serializable
data class ToolsResponse(
    val hub: String = BuildInfo.VERSION,
    val tools: List<ToolDto> = emptyList()
)

/**
 * How a tool's data is exposed to another app. The body of an encrypted note is
 * never sent: only the metadata, so a client can say "this note is locked"
 * without being able to read it.
 */
@Serializable
data class ToolNoteDto(
    val uuid: String = "",
    val title: String = "",
    val content: String = "",
    val category: String = "",
    val createdTime: Long = 0L,
    val updatedTime: Long = 0L,
    val format: String = "text",
    val isPinned: Boolean = false,
    val isTrashed: Boolean = false,
    val isEncrypted: Boolean = false
)

@Serializable
data class ToolNotesResponse(
    val tool: String = "jotter",
    val total: Int = 0,
    /** When the client asked only for what changed. */
    val cursor: Long = 0L,
    val notes: List<ToolNoteDto> = emptyList()
)

@Serializable
data class HealthResponse(
    val status: String = "ok",
    val version: String = BuildInfo.VERSION,
    val notes: Int = 0,
    val tools: Int = 0
)
