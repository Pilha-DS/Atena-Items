/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Agenda
 *
 * Agenda is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.agenda.data.notify

import com.openappslabs.agenda.data.model.Event
import com.openappslabs.agenda.data.model.EventTimes
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotifyPlanTest {

    private val today = LocalDate.of(2026, 11, 13)

    private fun event(
        title: String,
        at: LocalTime,
        allDay: Boolean = false,
        uuid: String = title
    ): Event {
        val start = EventTimes.toMillis(today, at)
        return Event(
            uuid = uuid,
            title = title,
            startTime = start,
            endTime = start + 60 * 60 * 1000L,
            allDay = allDay
        )
    }

    private fun at(hour: Int, minute: Int): LocalDateTime =
        LocalDateTime.of(today, LocalTime.of(hour, minute))

    @Test
    fun reads_the_time_the_way_people_type_it() {
        assertEquals(LocalTime.of(7, 0), NotifyPlan.parseTime("7:00"))
        assertEquals(LocalTime.of(7, 0), NotifyPlan.parseTime("07:00"))
        assertEquals(LocalTime.of(7, 30), NotifyPlan.parseTime(" 7h30 "))
        assertEquals(LocalTime.of(7, 0), NotifyPlan.parseTime("7h"))
        assertNull(NotifyPlan.parseTime("24:00"))
        assertNull(NotifyPlan.parseTime("7:70"))
        assertNull(NotifyPlan.parseTime("manhã"))
        assertNull(NotifyPlan.parseTime(""))
    }

    @Test
    fun digest_waits_for_its_time_and_shows_up_once_a_day() {
        val config = NotifyConfig(digestAt = "07:00")
        assertTrue(!NotifyPlan.shouldSendDigest(config, at(6, 59)))
        assertTrue(NotifyPlan.shouldSendDigest(config, at(7, 0)))
        assertTrue(NotifyPlan.shouldSendDigest(config, at(22, 0)))

        val alreadySent = config.copy(lastDigestDay = today.toString())
        assertTrue(!NotifyPlan.shouldSendDigest(alreadySent, at(9, 0)))
        assertTrue(!NotifyPlan.shouldSendDigest(config.copy(enabled = false), at(9, 0)))
    }

    @Test
    fun reminds_only_events_inside_the_lead_window_and_only_once() {
        val soon = event("Dentista", LocalTime.of(9, 30))
        val later = event("Almoço", LocalTime.of(12, 0))
        val allDay = event("Feriado", LocalTime.MIDNIGHT, allDay = true)
        val now = EventTimes.toMillis(today, LocalTime.of(9, 25))
        val config = NotifyConfig(leadMinutes = 10)

        val due = NotifyPlan.dueReminders(listOf(soon, later, allDay), now, config)
        assertEquals(listOf("Dentista"), due.map { it.title })

        val repeated = config.copy(sent = listOf(NotifyPlan.key(soon)))
        assertTrue(NotifyPlan.dueReminders(listOf(soon), now, repeated).isEmpty())
        assertTrue(NotifyPlan.dueReminders(listOf(soon), now, config.copy(remindEnabled = false)).isEmpty())
    }

    @Test
    fun an_event_that_already_started_is_not_announced() {
        val started = event("Reunião", LocalTime.of(9, 0))
        val now = EventTimes.toMillis(today, LocalTime.of(9, 5))
        assertTrue(NotifyPlan.dueReminders(listOf(started), now, NotifyConfig()).isEmpty())
    }

    @Test
    fun the_digest_lists_the_day_in_order() {
        val body = NotifyPlan.digestBody(
            listOf(
                event("Almoço", LocalTime.of(12, 0)),
                event("Dentista", LocalTime.of(9, 30))
            )
        )
        assertEquals("09:30  Dentista\n12:00  Almoço", body)
        assertEquals("1 evento hoje", NotifyPlan.digestTitle(1))
        assertEquals("2 eventos hoje", NotifyPlan.digestTitle(2))
    }

    @Test
    fun remembered_keys_from_other_days_are_dropped() {
        val keep = NotifyPlan.key(event("Hoje", LocalTime.of(9, 0)))
        val yesterday = NotifyPlan.key(
            event("Ontem", LocalTime.of(9, 0)).copy(
                startTime = EventTimes.toMillis(today.minusDays(1), LocalTime.of(9, 0))
            )
        )
        assertEquals(listOf(keep), NotifyPlan.pruneSent(listOf(keep, yesterday), today))
    }

    @Test
    fun settings_round_trip_through_the_file() {
        val file = java.nio.file.Files.createTempFile("agenda-notify", ".json")
        java.nio.file.Files.deleteIfExists(file)
        try {
            val preferences = NotifyPreferences(file)
            assertEquals(NotifyConfig(), preferences.read())

            preferences.update { it.copy(digestAt = "06:45", leadMinutes = 30, lastDigestDay = "x") }
            val stored = preferences.read()
            assertEquals("06:45", stored.digestAt)
            assertEquals(30, stored.leadMinutes)
            assertEquals("x", stored.lastDigestDay)
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }
}
