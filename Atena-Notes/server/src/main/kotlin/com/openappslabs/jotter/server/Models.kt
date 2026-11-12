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

package com.openappslabs.jotter.server

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
data class HealthResponse(
    val status: String = "ok",
    val version: String = BuildInfo.VERSION,
    val notes: Int = 0
)

@Serializable
data class StatsResponse(
    val notes: Int = 0,
    val trashed: Int = 0,
    val tombstones: Int = 0,
    val categories: Int = 0,
    val cursor: Long = 0L,
    val serverTime: Long = 0L,
    /** Which devices have connected, newest first. */
    val devices: List<DeviceDto> = emptyList()
)

@Serializable
data class ErrorResponse(
    val error: String
)

object BuildInfo {
    const val NAME = "jotter-server"
    const val VERSION = "1.0.0"
}
