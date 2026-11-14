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

import com.openappslabs.agenda.data.model.Deletion
import com.openappslabs.agenda.data.model.Event

/**
 * The conflict rules of the sync, kept pure so they can be tested without a
 * database or a network.
 *
 * The server and the app both use last-write-wins on `updatedTime`; a deletion
 * only wins when it is at least as new as the last edit of the event.
 */
object SyncMerge {

    enum class PullAction { INSERT, UPDATE, IGNORE }

    /**
     * What to do with an event that came from the server.
     *
     * `localDeletion` is the pending tombstone this device keeps for [uuid].
     * If this device deleted the event *after* the version the server sent,
     * the server copy is older and must not come back.
     */
    fun pullAction(local: Event?, localDeletion: Deletion?, remoteUpdatedTime: Long): PullAction = when {
        local == null -> when {
            localDeletion != null && localDeletion.deletedAt >= remoteUpdatedTime -> PullAction.IGNORE
            else -> PullAction.INSERT
        }
        remoteUpdatedTime > local.updatedTime -> PullAction.UPDATE
        else -> PullAction.IGNORE
    }

    /** A pulled deletion removes the event unless this device has a newer edit. */
    fun deletionRemoves(local: Event?, remoteDeletedAt: Long): Boolean =
        local != null && remoteDeletedAt >= local.updatedTime

    /**
     * Which pushed rows the server actually kept.
     *
     * When the server holds a newer version it was not confirmed here: that
     * version is already in the response and wins on the next round.
     */
    fun confirmedPushes(
        pushed: List<Pair<String, Long>>,
        serverVersions: Map<String, Long>
    ): List<String> = pushed
        .filterNot { (uuid, localUpdated) -> (serverVersions[uuid] ?: Long.MIN_VALUE) > localUpdated }
        .map { (uuid, _) -> uuid }
}
