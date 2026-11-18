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

import java.nio.file.Files
import java.nio.file.Path

/**
 * The toast spool: one `.req` file per toast inside `$IPC/toasts`, written by
 * `agenda-toast` (and by the `notify-send` shim the installer can put in
 * `~/.local/bin`).
 *
 * A spool instead of a second IPC file because a notification is not a state:
 * two scripts notifying at the same time must produce two toasts, and a toast
 * that arrives while the app is booting must not be lost. Each request is a
 * file, so nothing is overwritten and the app drains them in order.
 *
 * Writers rename the file into place, so a request is either visible complete
 * or not visible at all; a half-written file is dropped instead of shown.
 */
object ToastSpool {

    /** Creates the spool directory early, so the first writer never has to. */
    fun prepare() {
        runCatching { Files.createDirectories(IpcBridge.toastDir) }
    }

    /** Changes whenever a request lands (or one is consumed), like the other IPC stamps. */
    fun stamp(): Long = runCatching {
        Files.getLastModifiedTime(IpcBridge.toastDir).toMillis()
    }.getOrDefault(0L)

    /**
     * Takes every pending request out of the spool. Expired ones are removed
     * without being returned: the caller has nothing to do with them.
     */
    fun drain(now: Long = System.currentTimeMillis()): List<ToastRequest> =
        drain(IpcBridge.toastDir, now)

    /** Same, on a directory chosen by the caller (the tests use a temp one). */
    internal fun drain(dir: Path, now: Long): List<ToastRequest> {
        val files = pendingFiles(dir)
        if (files.isEmpty()) return emptyList()

        val requests = ArrayList<ToastRequest>(files.size)
        for (file in files) {
            val age = runCatching {
                now - Files.getLastModifiedTime(file).toMillis()
            }.getOrNull()
            val content = runCatching { Files.readString(file) }.getOrNull()
            runCatching { Files.deleteIfExists(file) }
            if (age == null || content == null) continue
            if (!ToastRequests.isFresh(ageMillis = age)) continue
            ToastRequests.parse(file.fileName.toString(), content)?.let(requests::add)
        }
        return requests
    }

    private fun pendingFiles(dir: Path): List<Path> = runCatching {
        if (!Files.isDirectory(dir)) return emptyList()
        Files.list(dir).use { stream ->
            stream
                .filter { it.fileName.toString().endsWith(ToastRequests.SUFFIX) }
                // Oldest first: the file name starts with the urgency, then the
                // timestamp, so a burst keeps the order it was written in.
                .sorted()
                .toList()
        }
    }.getOrDefault(emptyList())
}
