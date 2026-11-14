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
    val version: String = BuildInfo.VERSION,
    /** Capability flag: this hub hosts the universal store (records + files). */
    val universalStore: Boolean = true
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
// Admin: the people allowed in, and what each device has been doing.
// ---------------------------------------------------------------------------

/**
 * A token handed to one client. The secret is only present in the response
 * that creates it; everywhere else only the prefix is shown, so a stolen
 * database dump does not hand out working tokens by itself.
 */
@Serializable
data class TokenDto(
    val id: Long = 0L,
    val label: String = "",
    val prefix: String = "",
    val createdAt: Long = 0L,
    val revoked: Boolean = false,
    /** Empty = every namespace; otherwise a comma-separated allowlist. */
    val scope: String = "",
    val secret: String? = null
)

/** A sync round, as the admin screen lists it (newest first). */
@Serializable
data class ActivityDto(
    val at: Long = 0L,
    val tokenLabel: String = "",
    val deviceId: String = "",
    val deviceName: String = "",
    val ip: String = "",
    val stream: String = "",
    val pushed: Int = 0,
    val pulled: Int = 0,
    val bytesIn: Long = 0L,
    val bytesOut: Long = 0L
)

/** Totals per device, joined with its last sync. */
@Serializable
data class DeviceSummaryDto(
    val id: String = "",
    val name: String = "",
    val ip: String = "",
    val lastStream: String = "",
    val lastSync: Long = 0L,
    val tokenLabel: String = "",
    val pushed: Int = 0,
    val pulled: Int = 0,
    val bytesIn: Long = 0L,
    val bytesOut: Long = 0L,
    val rounds: Int = 0
)

/** What one sync round did; written by the sync services, shown in the admin. */
data class SyncActivity(
    val stream: String,
    val tokenLabel: String,
    val deviceId: String,
    val deviceName: String,
    val ip: String,
    val pushed: Int = 0,
    val pulled: Int = 0,
    val bytesIn: Long = 0L,
    val bytesOut: Long = 0L
)

// ---------------------------------------------------------------------------
// Universal store: any app, any shape of data, no server release required.
// ---------------------------------------------------------------------------

/**
 * One record of the universal store.
 *
 * The hub never looks inside [payload]: it is the app's own JSON (or plain
 * text), stored and returned byte for byte. That is what lets a new app use
 * the hub without a new endpoint here — the server only understands the
 * envelope (key, clocks, cursor), not the content.
 *
 * [updatedTime] is the client clock and decides last-write-wins, exactly like
 * on a note.
 */
@Serializable
data class StoreRecordDto(
    val key: String = "",
    val payload: String = "",
    val createdTime: Long = 0L,
    val updatedTime: Long = 0L
)

@Serializable
data class StoreDeletionDto(
    val key: String = "",
    val deletedAt: Long = 0L
)

@Serializable
data class StoreSyncRequest(
    /** Cursor of this collection only; `0` means "send me everything". */
    val cursor: Long = 0L,
    val deviceId: String = "",
    val deviceName: String = "",
    val records: List<StoreRecordDto> = emptyList(),
    val deletions: List<StoreDeletionDto> = emptyList()
)

@Serializable
data class StoreSyncResponse(
    val cursor: Long = 0L,
    val serverTime: Long = 0L,
    val records: List<StoreRecordDto> = emptyList(),
    val deletions: List<StoreDeletionDto> = emptyList()
)

/** A namespace/collection pair that already holds data. */
@Serializable
data class StoreStreamDto(
    val namespace: String = "",
    val collection: String = "",
    val records: Int = 0,
    val deleted: Int = 0,
    val bytes: Long = 0L,
    val cursor: Long = 0L,
    val lastWrite: Long = 0L
)

@Serializable
data class StoreStreamsResponse(
    val streams: List<StoreStreamDto> = emptyList()
)

/**
 * Metadata of an uploaded file. The bytes never travel in JSON: they are
 * content-addressed on disk and fetched by the same [name].
 *
 * [hash] is the SHA-256 of the content, used both as the ETag and as the
 * deduplication key: the same file uploaded by two apps is stored once.
 */
@Serializable
data class BlobDto(
    val namespace: String = "",
    val name: String = "",
    val hash: String = "",
    val size: Long = 0L,
    val contentType: String = "application/octet-stream",
    val createdTime: Long = 0L,
    val updatedTime: Long = 0L,
    val deleted: Boolean = false
)

@Serializable
data class BlobListResponse(
    val namespace: String = "",
    val blobs: List<BlobDto> = emptyList()
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

/**
 * The JSON the wire uses. The admin ledger measures a response with it, so both
 * sides of the server size a payload the same way the client will decode it.
 */
internal val WIRE_JSON = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}
