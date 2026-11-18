/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Agenda
 *
 * Agenda is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.agenda.platform

import com.openappslabs.agenda.data.model.EventDraft
import com.openappslabs.agenda.data.model.EventTimes
import com.openappslabs.agenda.data.notify.NotifyPreferences
import com.openappslabs.agenda.data.repository.EventRepository
import com.openappslabs.agenda.data.source.AgendaDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationServiceTest {

    private lateinit var databaseFile: Path
    private lateinit var notifyFile: Path
    private lateinit var repository: EventRepository
    private lateinit var preferences: NotifyPreferences
    private val shown = mutableListOf<Pair<String, String>>()

    private var now = 1_000L
    private val day: LocalDate = LocalDate.of(2026, 11, 13)

    @BeforeTest
    fun setUp() {
        databaseFile = Files.createTempFile("agenda-notify-test", ".db")
        repository = EventRepository(AgendaDatabase(databaseFile).also { it.init() }) { now }
        notifyFile = Files.createTempFile("agenda-notify", ".json")
        Files.deleteIfExists(notifyFile)
        preferences = NotifyPreferences(notifyFile)
        shown.clear()
    }

    @AfterTest
    fun tearDown() {
        Files.deleteIfExists(databaseFile)
        Files.deleteIfExists(databaseFile.resolveSibling("${databaseFile.fileName}-wal"))
        Files.deleteIfExists(databaseFile.resolveSibling("${databaseFile.fileName}-shm"))
        Files.deleteIfExists(notifyFile)
    }

    private fun addEvent(title: String, start: LocalTime) {
        repository.save(
            EventDraft(
                title = title,
                startTime = EventTimes.toMillis(day, start),
                endTime = EventTimes.toMillis(day, start.plusHours(1))
            )
        )
    }

    private fun service(sync: () -> Unit = {}) = NotificationService(
        repository = repository,
        preferences = preferences,
        notifier = { title, body ->
            shown.add(title to body)
            true
        },
        sync = sync
    )

    private fun instant(hour: Int, minute: Int): LocalDateTime =
        LocalDateTime.of(day, LocalTime.of(hour, minute))

    @Test
    fun the_morning_digest_syncs_first_and_shows_up_only_once() {
        addEvent("Dentista", LocalTime.of(9, 30))
        // The phone created this one: it only exists after the sync runs.
        val service = service(sync = { addEvent("Reunião", LocalTime.of(14, 0)) })

        service.tick(instant(7, 0))

        assertEquals(1, shown.size)
        assertEquals("2 eventos hoje", shown.single().first)
        assertTrue(shown.single().second.contains("09:30  Dentista"))
        assertTrue(shown.single().second.contains("14:00  Reunião"))

        service.tick(instant(7, 30))
        assertEquals(1, shown.size)
    }

    @Test
    fun a_quiet_day_still_marks_the_digest_as_done() {
        val service = service()
        service.tick(instant(8, 0))
        assertTrue(shown.isEmpty())

        addEvent("Tarde", LocalTime.of(15, 0))
        service.tick(instant(8, 1))
        assertTrue(shown.isEmpty())
    }

    @Test
    fun the_digest_does_not_swallow_the_reminder_of_the_same_event() {
        addEvent("Dentista", LocalTime.of(9, 30))
        val service = service()

        service.tick(instant(7, 0))
        service.tick(instant(9, 25))

        assertEquals(2, shown.size)
        assertEquals("Em 10 min: Dentista", shown.last().first)
    }

    @Test
    fun each_event_is_announced_once_before_it_starts() {
        preferences.update { it.copy(digestEnabled = false, leadMinutes = 10) }
        addEvent("Dentista", LocalTime.of(9, 30))
        val service = service()

        service.tick(instant(9, 25))
        assertEquals("Em 10 min: Dentista", shown.single().first)
        assertTrue(shown.single().second.contains("09:30"))

        service.tick(instant(9, 26))
        assertEquals(1, shown.size)
    }

    @Test
    fun nothing_happens_while_notifications_are_off() {
        preferences.update { it.copy(enabled = false) }
        addEvent("Dentista", LocalTime.of(9, 30))

        service().tick(instant(9, 25))
        service().tick(instant(9, 29))
        assertTrue(shown.isEmpty())
    }

    @Test
    fun a_failed_notification_is_retried_on_the_next_tick() {
        preferences.update { it.copy(digestEnabled = false) }
        addEvent("Dentista", LocalTime.of(9, 30))
        var attempts = 0
        val service = NotificationService(
            repository = repository,
            preferences = preferences,
            notifier = { title, body ->
                attempts++
                if (attempts > 1) shown.add(title to body)
                attempts > 1
            }
        )

        service.tick(instant(9, 25))
        service.tick(instant(9, 26))

        assertEquals(2, attempts)
        assertEquals(1, shown.size)
    }
}
