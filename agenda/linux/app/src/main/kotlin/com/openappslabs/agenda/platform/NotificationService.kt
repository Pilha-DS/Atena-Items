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

import com.openappslabs.agenda.data.model.Event
import com.openappslabs.agenda.data.model.EventTimes
import com.openappslabs.agenda.data.notify.NotifyConfig
import com.openappslabs.agenda.data.notify.NotifyPlan
import com.openappslabs.agenda.data.notify.NotifyPreferences
import com.openappslabs.agenda.data.repository.EventRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Notifications for the day, from the warm background process.
 *
 * A plain thread, not a coroutine: Compose pauses its dispatcher while the
 * window is hidden, and the whole point of this is to speak up when nobody is
 * looking at the window. The same reason the IPC loop has its own thread.
 *
 * [sync] runs before the morning digest so an event created on the phone is in
 * the summary, not in tomorrow's.
 */
class NotificationService(
    private val repository: EventRepository,
    private val preferences: NotifyPreferences,
    private val notifier: (title: String, body: String) -> Boolean = DesktopNotifier::send,
    private val sync: () -> Unit = {},
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
    private val tickMillis: Long = TICK_MS
) {

    @Volatile
    private var running = false

    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "agenda-notifications").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        while (running) {
            runCatching { tick() }
                .onFailure { System.err.println("agenda: notification tick failed: $it") }
            try {
                Thread.sleep(tickMillis)
            } catch (interrupted: InterruptedException) {
                break
            }
        }
    }

    /** One round. Exposed so a test (or the launcher) can drive it directly. */
    fun tick(now: LocalDateTime = clock()) {
        val config = preferences.read()
        if (!config.enabled) return

        val day = now.toLocalDate()
        val sendDigest = NotifyPlan.shouldSendDigest(config, now)
        if (sendDigest) {
            // The other device's events have to be here before quoting the day.
            runCatching(sync)
        }

        val events = runCatching { repository.eventsForDate(day) }.getOrDefault(emptyList())
        if (sendDigest) {
            sendDigestFor(day, events)
        }
        sendUpcoming(events, now, preferences.read())
    }

    private fun sendDigestFor(day: LocalDate, events: List<Event>) {
        if (events.isNotEmpty()) {
            val sent = notifier(NotifyPlan.digestTitle(events.size), NotifyPlan.digestBody(events))
            if (!sent) return
        }

        // Only the day is remembered: the events themselves still get their
        // heads-up later, the summary is not a substitute for the reminder.
        preferences.update { it.copy(lastDigestDay = day.toString()) }
    }

    private fun sendUpcoming(events: List<Event>, now: LocalDateTime, config: NotifyConfig) {
        val nowMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val due = NotifyPlan.dueReminders(events, nowMillis, config)
        val day = now.toLocalDate()
        for (event in due) {
            if (!notifier(reminderTitle(event, config), reminderBody(event))) {
                // Without a notification there is nothing to remember: the next
                // tick tries again instead of silently skipping the event.
                continue
            }
            preferences.update { current ->
                current.copy(
                    sent = NotifyPlan.pruneSent(current.sent + NotifyPlan.key(event), day).distinct()
                )
            }
        }
    }

    private fun reminderTitle(event: Event, config: NotifyConfig): String =
        "Em ${config.leadMinutes} min: ${event.title}"

    private fun reminderBody(event: Event): String = buildString {
        append(EventTimes.timeLabel(event))
        if (event.location.isNotBlank()) {
            append('\n').append(event.location)
        }
        if (event.description.isNotBlank()) {
            append('\n').append(event.description.lineSequence().first())
        }
    }

    private companion object {
        const val TICK_MS = 30_000L
    }
}
