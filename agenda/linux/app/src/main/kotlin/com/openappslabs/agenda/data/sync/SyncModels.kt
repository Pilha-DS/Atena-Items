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

import com.openappslabs.agenda.data.model.Event
import kotlinx.serialization.Serializable

/**
 * Wire format shared with `POST /api/v1/tools/agenda/sync`.
 *
 * Every field has a default so a client one version behind still syncs and the
 * server stays tolerant of an older one.
 */
@Serializable
data class AgendaEventDto(
    val uuid: String = "",
    val title: String = "",
    val description: String = "",
    val location: String = "",
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val allDay: Boolean = false,
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
data class AgendaSyncRequestDto(
    val cursor: Long = 0L,
    val deviceId: String = "",
    val deviceName: String = "",
    val events: List<AgendaEventDto> = emptyList(),
    val deletions: List<AgendaDeletionDto> = emptyList()
)

@Serializable
data class AgendaSyncResponseDto(
    val cursor: Long = 0L,
    val serverTime: Long = 0L,
    val events: List<AgendaEventDto> = emptyList(),
    val deletions: List<AgendaDeletionDto> = emptyList()
)

@Serializable
data class SyncErrorDto(
    val error: String = ""
)

fun Event.toSyncDto(): AgendaEventDto = AgendaEventDto(
    uuid = uuid,
    title = title,
    description = description,
    location = location,
    startTime = startTime,
    endTime = endTime,
    allDay = allDay,
    color = color,
    createdTime = createdTime,
    updatedTime = updatedTime
)
