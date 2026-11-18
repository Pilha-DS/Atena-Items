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

import com.openappslabs.agenda.data.notify.QuickTimerPlan
import com.openappslabs.agenda.data.repository.QuickTimerRepository
import com.openappslabs.agenda.data.source.AgendaDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuickTimerServiceTest {

    private lateinit var file: Path
    private lateinit var repository: QuickTimerRepository
    private var now = 1_000L
    private val notifications = ArrayList<String>()

    @BeforeTest
    fun setUp() {
        file = Files.createTempFile("agenda-timers-service-test", ".db")
        val database = AgendaDatabase(file).also { it.init() }
        repository = QuickTimerRepository(database) { now }
    }

    @AfterTest
    fun tearDown() {
        Files.deleteIfExists(file)
        Files.deleteIfExists(file.resolveSibling("${file.fileName}-wal"))
        Files.deleteIfExists(file.resolveSibling("${file.fileName}-shm"))
    }

    private fun service(
        delivered: Boolean = true,
        onRing: () -> Unit = {}
    ): QuickTimerService = QuickTimerService(
        repository = repository,
        notifier = { title, body ->
            if (delivered) notifications.add("$title: $body")
            delivered
        },
        onRing = onRing,
        clock = { now }
    )

    @Test
    fun rings_once_and_remembers_it() {
        val timer = repository.save("Ir ao mercado", now + 60_000L)
        now = timer.endTime

        service().tick(now)

        assertEquals(listOf("Tempo esgotado: Ir ao mercado"), notifications)
        assertTrue(repository.find(timer.uuid)?.notified == true)

        // A second tick must not ring the same timer again.
        service().tick(now)
        assertEquals(1, notifications.size)
    }

    @Test
    fun a_failed_notification_is_retried_on_the_next_tick() {
        val timer = repository.save("Ir ao mercado", now + 60_000L)
        now = timer.endTime

        service(delivered = false).tick(now)
        assertTrue(repository.find(timer.uuid)?.notified == false)

        service().tick(now)
        assertEquals(1, notifications.size)
        assertTrue(repository.find(timer.uuid)?.notified == true)
    }

    @Test
    fun an_old_timer_is_silenced_without_a_notification() {
        val timer = repository.save("Ir ao mercado", now + 60_000L)
        now = timer.endTime + QuickTimerPlan.RING_GRACE_MS + 1

        service().tick(now)

        assertTrue(notifications.isEmpty())
        assertTrue(repository.find(timer.uuid)?.notified == true)
    }

    @Test
    fun the_window_is_told_to_refresh_when_a_timer_rings() {
        var refreshed = 0
        val timer = repository.save("Ir ao mercado", now + 60_000L)
        now = timer.endTime

        service(onRing = { refreshed++ }).tick(now)

        assertEquals(1, refreshed)
    }
}
