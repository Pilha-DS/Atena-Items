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

package com.openappslabs.agenda.platform

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * How the window behaves, kept apart from the sync settings so one can be
 * changed without rewriting the other.
 */
@Serializable
data class WindowConfig(
    /**
     * The window is a panel popup: click outside and it hides. Turning this off
     * makes it behave like a normal window, closed with Esc or the X button.
     */
    val closeOnClickOutside: Boolean = true
)

/** `~/.config/agenda/window.json`, written atomically like the sync config. */
class WindowPreferences(private val file: Path) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun read(): WindowConfig = runCatching {
        if (!Files.exists(file)) return@runCatching null
        json.decodeFromString(WindowConfig.serializer(), Files.readString(file))
    }.getOrNull() ?: WindowConfig()

    fun save(config: WindowConfig) {
        val parent = file.toAbsolutePath().parent
        parent?.let { Files.createDirectories(it) }
        val temp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temp, json.encodeToString(WindowConfig.serializer(), config))
        runCatching {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun update(transform: (WindowConfig) -> WindowConfig): WindowConfig {
        val updated = transform(read())
        save(updated)
        return updated
    }
}
