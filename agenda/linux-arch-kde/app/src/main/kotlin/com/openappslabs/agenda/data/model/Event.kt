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

package com.openappslabs.agenda.data.model

/**
 * One event, as stored locally and as it travels over the wire.
 *
 * `uuid` is the identity shared by every device; `id` is only the local row
 * number. `updatedTime` is the client clock used for last-write-wins, and
 * `needsSync` keeps a row in the next push until the server confirms it.
 */
data class Event(
    val id: Int = 0,
    val uuid: String,
    val title: String,
    val description: String = "",
    val location: String = "",
    val startTime: Long,
    val endTime: Long,
    val allDay: Boolean = false,
    val color: String = "",
    val createdTime: Long = 0L,
    val updatedTime: Long = 0L,
    val needsSync: Boolean = true
)

/** What an event editor hands to the repository. Null [uuid] means "new". */
data class EventDraft(
    val uuid: String? = null,
    val title: String,
    val description: String = "",
    val location: String = "",
    val startTime: Long,
    val endTime: Long,
    val allDay: Boolean = false,
    val color: String = ""
)

/** A deletion waiting to be pushed, kept even after the row is gone. */
data class Deletion(
    val uuid: String,
    val deletedAt: Long
)
