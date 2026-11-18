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

package com.openappslabs.docs.platform

import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The desktop's "choose files" dialog, with multiple selection.
 *
 * KDE first: `kdialog --getopenfilename` is the dialog Dolphin uses, so this
 * looks and behaves like the rest of the session. When kdialog is not there (a
 * non-Plasma session, a minimal box) AWT's [FileDialog] takes over — it is not
 * pretty, but it is always available and also returns several files.
 *
 * A cancelled dialog and a missing tool are different things: cancelling
 * returns an empty list and stops there, because falling through to a second
 * dialog after the user said "no" would be a second popup for the same click.
 * Returns an empty list on any failure as well — importing is an explicit
 * action, and a broken dialog must not take the window down.
 */
object FilePicker {

    fun chooseFiles(title: String, startDirectory: Path? = null): List<Path> {
        kdialog(title, startDirectory)?.let { return it }
        return awt(title, startDirectory)
    }

    /** Null when kdialog could not be started at all. */
    private fun kdialog(title: String, startDirectory: Path?): List<Path>? {
        val start = startDirectory?.takeIf { Files.isDirectory(it) } ?: Paths.get("/")
        val command = listOf(
            "kdialog",
            "--title", title,
            "--getopenfilename", start.toString(),
            "--multiple",
            "--separate-output"
        )
        return runCatching {
            val process = ProcessBuilder(command).start()
            // Read before waiting: a slow reader would block a long list.
            val lines = process.inputStream.bufferedReader().use { it.readLines() }
            val code = process.waitFor()
            if (code != 0) {
                emptyList()
            } else {
                lines.mapNotNull { line ->
                    line.trim().takeIf { it.isNotEmpty() }?.let { runCatching { Paths.get(it) }.getOrNull() }
                }
            }
        }.getOrNull()
    }

    private fun awt(title: String, startDirectory: Path?): List<Path> = runCatching {
        if (GraphicsEnvironment.isHeadless()) return emptyList()
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        dialog.isMultipleMode = true
        startDirectory?.takeIf { Files.isDirectory(it) }?.let { dialog.directory = it.toString() }
        dialog.isVisible = true
        val chosen = dialog.files.orEmpty().map { it.toPath() }
        dialog.dispose()
        chosen
    }.getOrDefault(emptyList())
}
