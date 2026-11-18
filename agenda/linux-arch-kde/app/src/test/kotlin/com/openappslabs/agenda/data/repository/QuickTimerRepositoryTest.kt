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

package com.openappslabs.agenda.data.repository

import com.openappslabs.agenda.data.source.AgendaDatabase
import com.openappslabs.agenda.data.sync.AgendaTimerDeletionDto
import com.openappslabs.agenda.data.sync.AgendaTimerDto
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuickTimerRepositoryTest {

    private lateinit var file: Path
    private lateinit var repository: QuickTimerRepository
    private var now = 1_000L

    @BeforeTest
    fun setUp() {
        file = Files.createTempFile("agenda-timers-test", ".db")
        val database = AgendaDatabase(file).also { it.init() }
        repository = QuickTimerRepository(database) { now }
    }

    @AfterTest
    fun tearDown() {
        Files.deleteIfExists(file)
        Files.deleteIfExists(file.resolveSibling("${file.fileName}-wal"))
        Files.deleteIfExists(file.resolveSibling("${file.fileName}-shm"))
    }

    @Test
    fun save_lists_the_timer_and_keeps_it_pending() {
        val saved = repository.save("Ir ao mercado", now + 20 * 60_000L)

        assertTrue(saved.uuid.isNotBlank())
        assertEquals(listOf(saved.uuid), repository.all().map { it.uuid })
        assertEquals(listOf(saved.uuid), repository.pendingTimers().map { it.uuid })
        assertFalse(saved.notified)
    }

    @Test
    fun lists_by_deadline_with_the_urgent_first() {
        now = 10
        val later = repository.save("Depois", now + 60 * 60_000L)
        now = 20
        val sooner = repository.save("Agora", now + 5 * 60_000L)

        assertEquals(listOf(sooner.uuid, later.uuid), repository.all().map { it.uuid })
    }

    @Test
    fun delete_removes_the_row_and_leaves_a_tombstone() {
        val saved = repository.save("Ir ao mercado", now + 20 * 60_000L)
        now = saved.updatedTime

        repository.delete(saved)

        assertTrue(repository.all().isEmpty())
        assertEquals(listOf(saved.uuid), repository.pendingDeletions().map { it.uuid })
    }

    @Test
    fun mark_notified_survives_a_reload() {
        val saved = repository.save("Ir ao mercado", now + 60_000L)

        repository.markNotified(saved.uuid)

        assertTrue(repository.find(saved.uuid)?.notified == true)
    }

    @Test
    fun a_pulled_timer_is_inserted_without_pending_sync() {
        repository.applyPulled(
            listOf(dto(uuid = "remoto", title = "Farmácia", endTime = now + 30 * 60_000L)),
            emptyList()
        )

        val pulled = repository.find("remoto")
        assertEquals("Farmácia", pulled?.title)
        assertFalse(pulled?.needsSync ?: true)
        assertTrue(repository.pendingTimers().isEmpty())
    }

    @Test
    fun a_newer_pulled_deadline_rearms_the_alarm() {
        val local = repository.save("Ir ao mercado", now + 60_000L)
        repository.markNotified(local.uuid)

        repository.applyPulled(
            listOf(
                dto(
                    uuid = local.uuid,
                    title = local.title,
                    endTime = local.endTime + 10 * 60_000L,
                    updatedTime = local.updatedTime + 1
                )
            ),
            emptyList()
        )

        val updated = repository.find(local.uuid)
        assertEquals(local.endTime + 10 * 60_000L, updated?.endTime)
        assertFalse(updated?.notified ?: true)
    }

    @Test
    fun a_newer_title_keeps_the_timer_as_already_rung() {
        val local = repository.save("Ir ao mercado", now + 60_000L)
        repository.markNotified(local.uuid)

        repository.applyPulled(
            listOf(
                dto(
                    uuid = local.uuid,
                    title = "Ir ao mercado central",
                    endTime = local.endTime,
                    updatedTime = local.updatedTime + 1
                )
            ),
            emptyList()
        )

        assertEquals("Ir ao mercado central", repository.find(local.uuid)?.title)
        assertTrue(repository.find(local.uuid)?.notified == true)
    }

    @Test
    fun an_older_pulled_version_does_not_override_the_local_one() {
        val local = repository.save("Meu título", now + 60_000L)

        repository.applyPulled(
            listOf(
                dto(
                    uuid = local.uuid,
                    title = "Servidor",
                    endTime = local.endTime,
                    updatedTime = local.updatedTime - 1
                )
            ),
            emptyList()
        )

        assertEquals("Meu título", repository.find(local.uuid)?.title)
    }

    @Test
    fun a_pulled_deletion_removes_an_older_local_timer() {
        val local = repository.save("Ir ao mercado", now + 60_000L)

        repository.applyPulled(
            emptyList(),
            listOf(AgendaTimerDeletionDto(uuid = local.uuid, deletedAt = local.updatedTime + 1))
        )

        assertTrue(repository.all().isEmpty())
    }

    @Test
    fun a_local_edit_newer_than_the_deletion_keeps_the_timer() {
        val local = repository.save("Ir ao mercado", now + 60_000L)

        repository.applyPulled(
            emptyList(),
            listOf(AgendaTimerDeletionDto(uuid = local.uuid, deletedAt = local.updatedTime - 1))
        )

        assertEquals(listOf(local.uuid), repository.all().map { it.uuid })
    }

    @Test
    fun mark_synced_clears_the_pending_flag() {
        val saved = repository.save("Ir ao mercado", now + 60_000L)

        repository.markSynced(listOf(saved.uuid))

        assertTrue(repository.pendingTimers().isEmpty())
    }

    private fun dto(
        uuid: String,
        title: String,
        endTime: Long,
        updatedTime: Long = now
    ) = AgendaTimerDto(
        uuid = uuid,
        title = title,
        endTime = endTime,
        createdTime = now,
        updatedTime = maxOf(updatedTime, 1L)
    )
}
