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

package com.openappslabs.agenda.data.notify

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * What to notify and when.
 *
 * `lastDigestDay` and `sent` are runtime state rather than settings: they keep
 * a notification from showing twice. They live in the same file as the rest so
 * a round trip through the disk is the only thing that has to succeed before
 * the notification counts as delivered.
 */
@Serializable
data class NotifyConfig(
    val enabled: Boolean = true,
    val digestEnabled: Boolean = true,
    /** Local time, "HH:mm". */
    val digestAt: String = "07:00",
    val remindEnabled: Boolean = true,
    /** How long before the event start the reminder shows up. */
    val leadMinutes: Int = 10,
    val lastDigestDay: String = "",
    val sent: List<String> = emptyList()
)

/** Settings file (`~/.config/agenda/notify.json`), written atomically. */
class NotifyPreferences(private val file: Path) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun read(): NotifyConfig = runCatching {
        if (!Files.exists(file)) return@runCatching null
        json.decodeFromString(NotifyConfig.serializer(), Files.readString(file))
    }.getOrNull() ?: NotifyConfig()

    fun save(config: NotifyConfig) {
        val parent = file.toAbsolutePath().parent
        parent?.let { Files.createDirectories(it) }
        val temp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temp, json.encodeToString(NotifyConfig.serializer(), config))
        runCatching {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun update(transform: (NotifyConfig) -> NotifyConfig): NotifyConfig {
        val updated = transform(read())
        save(updated)
        return updated
    }
}
