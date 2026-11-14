/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Atena Hub
 *
 * Atena Hub is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Atena Hub is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Atena Hub.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.hub

/**
 * The hub is a host for tools, not a single app's backend.
 *
 * Today there is one tool — notes (Jotter) — and two ways to talk to it:
 *
 *  - `/api/v1/sync` (legacy) — the bidirectional sync the Jotter apps use;
 *  - `/api/v1/tools/jotter/...` — a read API for other apps, which need to
 *    read notes without owning the database.
 *
 * Adding a tool means adding its [ToolInfo] here and its routes next to the
 * notes ones; clients discover what exists through `GET /api/v1/tools`.
 */
object Tools {

    val JOTTER = ToolInfo(
        id = "jotter",
        name = "Notas",
        description = "Notas do Jotter (Android e Linux): sincronização e leitura.",
        version = "2.5.0"
    )

    val AGENDA = ToolInfo(
        id = "agenda",
        name = "Agenda",
        description = "Eventos da Agenda (Linux): sincronização entre o hub e o desktop.",
        version = "0.1.0"
    )

    /** Everything this hub hosts. */
    val all: List<ToolInfo> = listOf(JOTTER, AGENDA)

    fun find(id: String): ToolInfo? = all.firstOrNull { it.id == id }
}

data class ToolInfo(
    val id: String,
    val name: String,
    val description: String,
    val version: String
) {
    fun toDto(): ToolDto = ToolDto(
        id = id,
        name = name,
        description = description,
        version = version
    )
}
