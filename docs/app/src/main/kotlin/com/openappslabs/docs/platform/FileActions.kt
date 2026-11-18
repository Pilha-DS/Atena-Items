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

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

/**
 * Hands a folder or a file to whatever the desktop registered for it.
 *
 * The app never renders or edits the documents itself in this stage: the tree
 * belongs to Dolphin, LibreOffice and the user's default viewer. Opening is the
 * whole integration, which is why it must fail quietly and report back to the
 * UI instead of throwing.
 */
object FileActions {

    fun open(path: Path): Boolean = runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(path.toFile())
            return@runCatching true
        }
        // Sessions without the AWT desktop bridge (some minimal WMs) still have
        // xdg-open, which is what every other KDE app uses anyway.
        val process = ProcessBuilder("xdg-open", path.toString())
            .redirectErrorStream(true)
            .start()
        // xdg-open on Plasma just hands the path to the running file manager and
        // returns at once; a long wait here would freeze nothing but the click.
        process.waitFor() == 0
    }.getOrDefault(false)

    /**
     * Shows the file selected in the file manager.
     *
     * Dolphin's `--select` is the KDE way; when it is not there (or not
     * Dolphin), falling back to the containing folder is still useful, and
     * better than failing the action outright.
     */
    fun reveal(path: Path): Boolean {
        val absolute = path.toAbsolutePath()
        val selected = runCatching {
            ProcessBuilder("dolphin", "--select", absolute.toString())
                .redirectErrorStream(true)
                .start()
            true
        }.getOrDefault(false)
        if (selected) return true
        return open(absolute.parent ?: absolute)
    }

    fun copyPath(path: Path): Boolean = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(path.toAbsolutePath().toString()), null)
        true
    }.getOrDefault(false)
}
