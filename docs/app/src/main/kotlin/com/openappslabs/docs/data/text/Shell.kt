/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Atena Docs
 *
 * Atena Docs is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Atena Docs is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Atena Docs.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.docs.data.text

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The few external programs this app may use, isolated behind an interface.
 *
 * OCR and PDF text come from `tesseract` and `pdftotext`/`pdftoppm` instead of
 * a bundled library: they are already on a KDE desktop, they are fast, and the
 * app keeps its small dependency list. Nothing here is required — when the
 * program is missing the harvest says so instead of failing the scan.
 */
interface Shell {
    /** True when the program is on `PATH` and executable. */
    fun exists(command: String): Boolean

    /**
     * Runs a program and returns stdout, or `null` when it failed, timed out or
     * printed more than [maxChars].
     */
    fun run(args: List<String>, timeoutSeconds: Long, maxChars: Int = MAX_TEXT_CHARS): String?
}

/** Practical limits shared by the harvester and the tests. */
const val MAX_TEXT_CHARS = 200_000
const val MAX_TEXT_FILE_BYTES = 4L * 1024 * 1024

class SystemShell : Shell {

    override fun exists(command: String): Boolean {
        val path = System.getenv("PATH") ?: return false
        return path.split(File.pathSeparatorChar).any { dir ->
            if (dir.isEmpty()) return@any false
            val file = File(dir, command)
            file.isFile && file.canExecute()
        }
    }

    override fun run(args: List<String>, timeoutSeconds: Long, maxChars: Int): String? {
        if (args.isEmpty()) return null
        val process = try {
            ProcessBuilder(args)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (_: Exception) {
            return null
        }

        val collected = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { stream ->
                    val chunk = CharArray(8192)
                    while (true) {
                        val read = stream.read(chunk)
                        if (read < 0) break
                        synchronized(collected) {
                            val room = maxChars - collected.length
                            if (room > 0) collected.appendRange(chunk, 0, minOf(read, room))
                        }
                        // Far past the limit: stop reading, the caller only
                        // wants a searchable excerpt.
                        if (collected.length >= maxChars) break
                    }
                }
            } catch (_: Exception) {
                // A killed process closes the pipe under us; the caller only
                // cares about the exit status.
            }
        }
        reader.isDaemon = true
        reader.start()

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(500)
            return null
        }
        reader.join(2_000)
        if (process.exitValue() != 0) return null
        return synchronized(collected) { collected.toString() }
    }
}
