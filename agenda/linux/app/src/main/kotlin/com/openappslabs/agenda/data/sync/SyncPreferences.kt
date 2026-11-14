/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Agenda
 *
 * Agenda is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Agenda is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Agenda.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.agenda.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Where the hub lives and how far this device has read.
 *
 * Local-first: the app works with no configuration at all. Connecting is
 * optional and only adds the other devices' events.
 */
@Serializable
data class SyncConfig(
    val serverUrl: String = "",
    val token: String = "",
    /** Stable per-install id, so the hub recognizes a returning device. */
    val deviceId: String = "",
    val deviceName: String = "",
    /** Agenda cursor, not the notes one. */
    val cursor: Long = 0L,
    val lastSyncAt: Long = 0L
) {
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && token.isNotBlank()
}

/** Settings file (`~/.config/agenda/config.json`), written atomically. */
class SyncPreferences(
    private val file: Path,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun read(): SyncConfig {
        val stored = runCatching {
            if (!Files.exists(file)) return@runCatching null
            json.decodeFromString(SyncConfig.serializer(), Files.readString(file))
        }.getOrNull()

        if (stored == null) {
            return SyncConfig(deviceId = UUID.randomUUID().toString(), deviceName = defaultDeviceName())
        }
        return stored.copy(
            deviceId = stored.deviceId.ifBlank { UUID.randomUUID().toString() },
            deviceName = stored.deviceName.ifBlank { defaultDeviceName() }
        )
    }

    fun save(config: SyncConfig) {
        val parent = file.toAbsolutePath().parent
        parent?.let { Files.createDirectories(it) }
        val temp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temp, json.encodeToString(SyncConfig.serializer(), config))
        runCatching {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun update(transform: (SyncConfig) -> SyncConfig): SyncConfig {
        val updated = transform(read())
        save(updated)
        return updated
    }

    fun saveCursor(cursor: Long, syncedAt: Long): SyncConfig =
        update { it.copy(cursor = cursor, lastSyncAt = syncedAt) }

    fun resetCursor(): SyncConfig = update { it.copy(cursor = 0L, lastSyncAt = 0L) }

    private fun defaultDeviceName(): String = runCatching {
        InetAddress.getLocalHost().hostName
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }
        ?: "desktop"
}
