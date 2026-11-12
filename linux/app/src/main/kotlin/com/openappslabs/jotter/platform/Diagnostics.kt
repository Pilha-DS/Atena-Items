/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Jotter is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Jotter.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.jotter.platform

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Tiny append-only log for the failures that would otherwise be invisible.
 *
 * The widget is started by the panel helper with stdout/stderr sent to
 * `/dev/null`, so a failed SQLite write leaves no trace anywhere. Keeping the
 * last few lines in the cache directory makes those failures diagnosable.
 */
object Diagnostics {

    private val file: File by lazy { Xdg.cacheDir.resolve("jotter.log").toFile() }

    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private const val MAX_BYTES = 256L * 1024L

    @Synchronized
    fun log(message: String) {
        runCatching {
            if (file.exists() && file.length() > MAX_BYTES) {
                val kept = file.readLines().takeLast(400)
                file.writeText(kept.joinToString("\n", postfix = "\n"))
            }
            file.parentFile?.mkdirs()
            file.appendText("${LocalDateTime.now().format(formatter)}  $message\n")
        }
    }
}
