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
import com.openappslabs.agenda.ui.i18n.Strings

/**
 * The quick timers' alarm, alive while the process is (the app stays warm in
 * the background for the day notifications). One second of precision is the
 * whole point, so this is a dedicated thread rather than the 30 s day loop.
 *
 * The ring is persisted on the row (`notified`), so restarting the app after a
 * timer ended does not ring it again.
 */
class QuickTimerService(
    private val repository: QuickTimerRepository,
    private val notifier: (title: String, body: String) -> Boolean = { title, body ->
        val shown = AgendaToasts.show(title, body, urgent = true)
        if (shown) {
            AlarmSound.play()
        }
        shown
    },
    private val onRing: (() -> Unit) = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMillis: Long = TICK_MS
) {

    @Volatile
    private var running = false

    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "agenda-timers").apply {
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
                .onFailure { System.err.println("agenda: timer tick failed: $it") }
            try {
                Thread.sleep(tickMillis)
            } catch (interrupted: InterruptedException) {
                break
            }
        }
    }

    /** One round. Exposed so a test can drive it without a thread. */
    fun tick(now: Long = clock()) {
        val timers = runCatching { repository.all() }.getOrDefault(emptyList())
        val plan = QuickTimerPlan.plan(timers, now)
        if (plan.isEmpty) return

        for (timer in plan.ring) {
            if (notifier(Strings.timerFinished, timer.title)) {
                repository.markNotified(timer.uuid)
            }
            // Without a notification there is nothing to remember: the next
            // second tries again instead of silently swallowing it.
        }
        for (timer in plan.silent) {
            repository.markNotified(timer.uuid)
        }
        if (plan.ring.isNotEmpty()) {
            runCatching(onRing)
        }
    }

    private companion object {
        const val TICK_MS = 1_000L
    }
}
