/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Agenda
 *
 * Agenda is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.agenda.data.sync

import com.openappslabs.agenda.data.model.Deletion
import com.openappslabs.agenda.data.model.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncMergeTest {

    private fun event(updatedTime: Long) = Event(
        uuid = "e1",
        title = "daily",
        startTime = 1000,
        endTime = 2000,
        createdTime = 1000,
        updatedTime = updatedTime,
        needsSync = true
    )

    @Test
    fun new_event_from_server_is_inserted() {
        assertEquals(
            SyncMerge.PullAction.INSERT,
            SyncMerge.pullAction(local = null, localDeletion = null, remoteUpdatedTime = 100)
        )
    }

    @Test
    fun newer_server_version_wins() {
        assertEquals(
            SyncMerge.PullAction.UPDATE,
            SyncMerge.pullAction(event(100), null, remoteUpdatedTime = 200)
        )
    }

    @Test
    fun older_server_version_is_ignored() {
        assertEquals(
            SyncMerge.PullAction.IGNORE,
            SyncMerge.pullAction(event(200), null, remoteUpdatedTime = 100)
        )
    }

    @Test
    fun pending_deletion_blocks_an_older_server_copy() {
        assertEquals(
            SyncMerge.PullAction.IGNORE,
            SyncMerge.pullAction(
                local = null,
                localDeletion = Deletion(uuid = "e1", deletedAt = 300),
                remoteUpdatedTime = 200
            )
        )
    }

    @Test
    fun pending_deletion_does_not_block_a_newer_edit() {
        assertEquals(
            SyncMerge.PullAction.INSERT,
            SyncMerge.pullAction(
                local = null,
                localDeletion = Deletion(uuid = "e1", deletedAt = 100),
                remoteUpdatedTime = 200
            )
        )
    }

    @Test
    fun deletion_only_removes_when_it_is_not_older_than_the_edit() {
        assertTrue(SyncMerge.deletionRemoves(event(100), remoteDeletedAt = 200))
        assertTrue(SyncMerge.deletionRemoves(event(100), remoteDeletedAt = 100))
        assertFalse(SyncMerge.deletionRemoves(event(200), remoteDeletedAt = 100))
        assertFalse(SyncMerge.deletionRemoves(local = null, remoteDeletedAt = 100))
    }

    @Test
    fun pushes_are_confirmed_unless_the_server_holds_a_newer_version() {
        val pushed = listOf("a" to 100L, "b" to 200L, "c" to 300L)
        val server = mapOf("a" to 150L, "b" to 200L)
        assertEquals(listOf("b", "c"), SyncMerge.confirmedPushes(pushed, server))
    }
}
