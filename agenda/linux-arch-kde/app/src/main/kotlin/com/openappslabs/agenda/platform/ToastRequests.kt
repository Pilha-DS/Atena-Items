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
 * A toast asked for by another process through the IPC spool.
 *
 * The format is plain text on purpose: the writer is usually a shell script or
 * a C library helper, and shell is bad at escaping JSON. The file name carries
 * the urgency, the first line is the title and everything after it is the
 * body, so a multi-line body needs no quoting at all.
 */
data class ToastRequest(val title: String, val body: String, val urgent: Boolean)

object ToastRequests {

    /** Only files ending with this are requests; the rest of the spool is ours. */
    const val SUFFIX = ".req"

    /** `u-` for the urgent ones (the end of a timer), `n-` for the calm ones. */
    const val URGENT_PREFIX = "u-"

    /**
     * Requests older than this are dropped instead of shown. A toast written
     * while the app was closed would otherwise pop up hours later, quoting
     * something that already happened.
     */
    const val MAX_AGE_MS = 5L * 60L * 1000L

    fun parse(fileName: String, content: String): ToastRequest? {
        if (!fileName.endsWith(SUFFIX)) return null
        val lines = content.lines()
        val title = lines.firstOrNull()?.trim().orEmpty()
        if (title.isEmpty()) return null
        val body = lines.drop(1).joinToString("\n").trim()
        return ToastRequest(
            title = title,
            body = body,
            urgent = fileName.startsWith(URGENT_PREFIX)
        )
    }

    /**
     * A request whose file is fresh enough to still be worth showing. Files
     * with a future timestamp count as fresh: the clock may have been set back
     * since the file was written, and the user did ask for that toast.
     */
    fun isFresh(ageMillis: Long, maxAgeMs: Long = MAX_AGE_MS): Boolean =
        ageMillis <= maxAgeMs
}
