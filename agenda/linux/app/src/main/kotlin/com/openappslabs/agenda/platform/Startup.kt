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

/**
 * Startup milestones, printed to stderr only when `AGENDA_TIMING=1`.
 *
 * Times are measured from JVM start (`RuntimeMXBean.uptime`), so the first
 * line already shows how much of the wait was the JVM itself and how much was
 * the app. Kept in the app instead of a profiler: the interesting number is
 * the cold start of the packaged image, where attaching a profiler would
 * change what is being measured.
 */
object Startup {

    private val enabled: Boolean = System.getenv("AGENDA_TIMING") != null

    private val uptime: java.lang.management.RuntimeMXBean? by lazy {
        runCatching { java.lang.management.ManagementFactory.getRuntimeMXBean() }.getOrNull()
    }

    fun mark(label: String) {
        if (!enabled) return
        val ms = uptime?.uptime ?: 0L
        System.err.println("[agenda-startup] $label ${ms}ms")
    }
}
