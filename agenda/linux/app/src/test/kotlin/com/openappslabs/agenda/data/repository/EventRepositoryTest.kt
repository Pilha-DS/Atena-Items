/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Agenda
 *
 * Agenda is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.agenda.data.repository

import com.openappslabs.agenda.data.model.EventDraft
import com.openappslabs.agenda.data.model.EventTimes
import com.openappslabs.agenda.data.source.AgendaDatabase
import com.openappslabs.agenda.data.sync.AgendaDeletionDto
import com.openappslabs.agenda.data.sync.AgendaEventDto
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventRepositoryTest {

    private lateinit var file: Path
    private lateinit var repository: EventRepository
    private var now = 1_000L

    private val day: LocalDate = LocalDate.of(2026, 11, 13)

    @BeforeTest
    fun setUp() {
        file = Files.createTempFile("agenda-test", ".db")
        val database = AgendaDatabase(file).also { it.init() }
        repository = EventRepository(database) { now }
    }

    @AfterTest
    fun tearDown() {
        Files.deleteIfExists(file)
        Files.deleteIfExists(file.resolveSibling("${file.fileName}-wal"))
        Files.deleteIfExists(file.resolveSibling("${file.fileName}-shm"))
    }

    private fun draft(
        title: String = "Consulta",
        start: LocalTime = LocalTime.of(9, 0),
        end: LocalTime = LocalTime.of(10, 0)
    ) = EventDraft(
        title = title,
        startTime = EventTimes.toMillis(day, start),
        endTime = EventTimes.toMillis(day, end)
    )

    @Test
    fun save_then_list_the_day() {
        val saved = repository.save(draft())

        assertTrue(saved.uuid.isNotBlank())
        assertTrue(saved.needsSync)
        assertEquals(listOf(saved.uuid), repository.eventsForDate(day).map { it.uuid })
        assertTrue(repository.pendingEvents().isNotEmpty())
    }

    @Test
    fun editing_keeps_the_uuid_and_moves_updated_time_forward() {
        val saved = repository.save(draft())
        now = saved.updatedTime

        val edited = repository.save(
            EventDraft(uuid = saved.uuid, title = "Consulta médica", startTime = saved.startTime, endTime = saved.endTime)
        )

        assertEquals(saved.uuid, edited.uuid)
        assertEquals("Consulta médica", edited.title)
        assertTrue(edited.updatedTime > saved.updatedTime)
        assertEquals(1, repository.eventsForDate(day).size)
    }

    @Test
    fun delete_removes_the_row_and_keeps_a_tombstone() {
        val saved = repository.save(draft())
        repository.delete(saved)

        assertTrue(repository.eventsForDate(day).isEmpty())
        assertEquals(listOf(saved.uuid), repository.pendingDeletions().map { it.uuid })
    }

    @Test
    fun pulled_older_version_does_not_overwrite_a_pending_edit() {
        val saved = repository.save(draft(title = "local"))

        repository.applyPulled(
            events = listOf(dto(saved.uuid, title = "server old", updatedTime = saved.updatedTime - 1)),
            deletions = emptyList()
        )

        assertEquals("local", repository.find(saved.uuid)?.title)
    }

    @Test
    fun pulled_newer_version_updates_and_clears_pending_flag() {
        val saved = repository.save(draft(title = "local"))

        repository.applyPulled(
            events = listOf(dto(saved.uuid, title = "server new", updatedTime = saved.updatedTime + 10)),
            deletions = emptyList()
        )

        val merged = repository.find(saved.uuid)
        assertNotNull(merged)
        assertEquals("server new", merged.title)
        assertTrue(!merged.needsSync)
    }

    @Test
    fun pulled_event_is_inserted_once() {
        repository.applyPulled(
            events = listOf(dto("remote", title = "da outra máquina", updatedTime = 50)),
            deletions = emptyList()
        )
        repository.applyPulled(
            events = listOf(dto("remote", title = "da outra máquina", updatedTime = 50)),
            deletions = emptyList()
        )

        assertEquals(1, repository.eventsForDate(day).size)
    }

    @Test
    fun pulled_deletion_removes_the_event() {
        val saved = repository.save(draft())

        repository.applyPulled(
            events = emptyList(),
            deletions = listOf(AgendaDeletionDto(uuid = saved.uuid, deletedAt = saved.updatedTime))
        )

        assertNull(repository.find(saved.uuid))
    }

    @Test
    fun pulled_deletion_is_ignored_when_the_local_edit_is_newer() {
        val saved = repository.save(draft())

        repository.applyPulled(
            events = emptyList(),
            deletions = listOf(AgendaDeletionDto(uuid = saved.uuid, deletedAt = saved.updatedTime - 1))
        )

        assertNotNull(repository.find(saved.uuid))
    }

    @Test
    fun mark_synced_clears_the_pending_list() {
        val first = repository.save(draft())
        val second = repository.save(draft(title = "Outro", start = LocalTime.of(14, 0), end = LocalTime.of(15, 0)))

        repository.markSynced(listOf(first.uuid, second.uuid))

        assertTrue(repository.pendingEvents().isEmpty())
    }

    private fun dto(uuid: String, title: String, updatedTime: Long) = AgendaEventDto(
        uuid = uuid,
        title = title,
        startTime = EventTimes.toMillis(day, LocalTime.of(9, 0)),
        endTime = EventTimes.toMillis(day, LocalTime.of(10, 0)),
        createdTime = 100,
        updatedTime = updatedTime
    )
}
