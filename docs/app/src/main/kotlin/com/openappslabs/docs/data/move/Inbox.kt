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

package com.openappslabs.docs.data.move

import java.nio.file.Files
import java.nio.file.Path

/**
 * The inbox at the top of the library: `<biblioteca>/init`.
 *
 * It is the folder to dump everything that still has to be organized. Being a
 * real directory, it costs nothing to create and works the same from Dolphin,
 * from a `mv` in the terminal or from a download folder.
 *
 * **Inside it nothing new is created.** A document waiting in the inbox may be
 * tagged and moved onto structure that already exists, and the AI may suggest
 * whatever it wants — but a folder or a tag never comes into being from there.
 * The reason is practical: dumping thirty loose files and accepting the model's
 * answers would otherwise spawn one folder and one vocabulary per document,
 * which is the opposite of organizing. Creating is the user's deliberate act,
 * done at the destination (the root of the library, or any folder that exists);
 * the inbox only maps files onto choices already made.
 *
 * The rule lives here, in the data layer, so it holds for every entry point —
 * the dialog, a future batch action, a test — and not only for the button that
 * happened to be on screen.
 */
object Inbox {

    /** Name of the folder, as the user sees it in Dolphin. */
    const val FOLDER = "init"

    /** Absolute directory of the inbox for [root]. */
    fun path(root: Path): Path = root.resolve(FOLDER)

    /**
     * True when [folder] is the inbox or lives below it.
     *
     * Any depth counts (`init/2026` is still the waiting room), and the first
     * segment is compared without case because the filesystem may not be
     * case-sensitive and a renamed `Init` must not escape the rule.
     */
    fun holds(folder: String): Boolean =
        folder.substringBefore(FolderPath.SEPARATOR).equals(FOLDER, ignoreCase = true)

    /**
     * Creates the inbox when it is missing.
     *
     * Best effort on purpose: the library keeps working without it (the user
     * may prefer to create folders by hand), so a read-only tree must not stop
     * the window from opening.
     */
    fun ensure(root: Path): Path {
        val directory = path(root)
        runCatching { Files.createDirectories(directory) }
        return directory
    }
}
