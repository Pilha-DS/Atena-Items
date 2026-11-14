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

import java.util.concurrent.TimeUnit

/**
 * Desktop notifications through the session bus.
 *
 * `notify-send` is the short path and exists on every Plasma and GNOME
 * install; `gdbus` is the fallback, because it only needs GLib, which ships
 * with the desktop itself. Neither is a hard dependency: with both missing the
 * call simply reports "not sent" and the app keeps working.
 */
object DesktopNotifier {

    /** Plasma keeps it on screen for this long; the user can dismiss it. */
    private const val TIMEOUT_MS = "10000"

    fun send(title: String, body: String): Boolean =
        notifySend(title, body) || gdbus(title, body)

    private fun notifySend(title: String, body: String): Boolean = run(
        "notify-send", "-a", "Agenda", "-t", TIMEOUT_MS, title, body
    )

    private fun gdbus(title: String, body: String): Boolean = run(
        "gdbus", "call", "--session",
        "--dest", "org.freedesktop.Notifications",
        "--object-path", "/org/freedesktop/Notifications",
        "--method", "org.freedesktop.Notifications.Notify",
        "Agenda", "0", "agenda", title, body, "[]", "{}", TIMEOUT_MS
    )

    /**
     * Runs the command and answers whether it got as far as the daemon. The
     * output is drained on purpose: `gdbus` writes the notification id, and a
     * full pipe would hold the process open until the timeout.
     */
    private fun run(vararg command: String): Boolean = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.outputStream.close()
        process.inputStream.use { it.readBytes() }
        process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    private const val WAIT_SECONDS = 5L
}
