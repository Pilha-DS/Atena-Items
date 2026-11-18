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
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileTimesTest {

    @Test
    fun `a fresh file answers with a date, or with nothing`() {
        // Both outcomes are correct: btrfs and ext4 report a birth time, a
        // filesystem without one reports zero and the prompt omits the line.
        // What must never happen is the app printing 1970 to the model.
        val dir = Path.of("build", "tmp", "filetimes").also { Files.createDirectories(it) }
        val file = createTempFile(dir, "amostra", ".pdf")
        try {
            val created = FileTimes.created(file)
            created?.let { assertTrue(it in 1..System.currentTimeMillis(), "data absurda: $it") }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `a path that does not exist is unknown, not epoch`() {
        assertNull(FileTimes.created(Path.of("build", "nao-existe-${System.nanoTime()}")))
    }
}
