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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * The birth time of a file, when the filesystem has one.
 *
 * Read from the disk instead of stored in the index on purpose: the scan
 * already stores size and modification time because it needs them as a change
 * detector, and a third field would only be one more column to migrate. This is
 * one `stat` at the moment a suggestion is asked for — the only moment anyone
 * looks at it.
 *
 * Returns null when the value is unknown. On Linux a filesystem without birth
 * time support (some `ext4` mounts, most `tmpfs`) reports zero, and reporting a
 * 1970 date to the model would be worse than omitting the line.
 */
object FileTimes {

    fun created(file: Path): Long? = runCatching {
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
        attributes.creationTime().toMillis().takeIf { it > 0 }
    }.getOrNull()
}
