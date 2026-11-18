/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Atena Sync
 *
 * Atena Sync is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Atena Sync is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Atena Sync.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.sync

import kotlinx.serialization.Serializable

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
    val encryptedContent: String? = null,
    val format: String = "text"
)

@Serializable
data class DeletionDto(
    val uuid: String = "",
    val deletedAt: Long = 0L
)

@Serializable
data class SyncRequest(
    val cursor: Long = 0L,
    val deviceId: String = "",
    val deviceName: String = "",
    val notes: List<NoteDto> = emptyList(),
    val deletions: List<DeletionDto> = emptyList(),
    val categories: List<String> = emptyList()
)

@Serializable
data class SyncResponse(
    val cursor: Long = 0L,
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
    val role: String = "atena-sync"
)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class HealthResponse(
    val status: String = "ok",
    val name: String = BuildInfo.NAME,
    val version: String = BuildInfo.VERSION,
    val loopbackPort: Int = DEFAULT_LOOPBACK_PORT,
    val lanPort: Int = DEFAULT_LAN_PORT,
    val deviceId: String = "",
    val deviceName: String = "",
    val peers: Int = 0,
    val hubConfigured: Boolean = false
)

@Serializable
data class SetupResponse(
    val token: String,
    val loopbackUrl: String,
    val deviceId: String,
    val deviceName: String
)

@Serializable
data class LinkDto(
    val id: String,
    val kind: String,
    val available: Boolean,
    val latencyMs: Long? = null,
    val stability: Double = 1.0,
    val cost: Int = 0,
    val batteryImpact: Int = 0,
    val label: String = "",
    val score: Double = 0.0
)

@Serializable
data class LinksResponse(
    val links: List<LinkDto> = emptyList(),
    val preferred: String? = null
)

@Serializable
data class PeerDto(
    val deviceId: String,
    val deviceName: String,
    val host: String,
    val port: Int,
    val lastSeenAt: Long = 0L,
    val latencyMs: Long? = null,
    val paired: Boolean = false
)

@Serializable
data class PeersResponse(val peers: List<PeerDto> = emptyList())

@Serializable
data class HubConfigRequest(
    val url: String = "",
    val token: String = ""
)

@Serializable
data class HubConfigResponse(
    val url: String = "",
    val configured: Boolean = false
)
