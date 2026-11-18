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

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlainReaderTest {

    private fun file(name: String, content: String): Path =
        createTempDirectory("docs-plain").resolve(name).also { Files.writeString(it, content) }

    @Test
    fun `reads a text file as it is`() {
        val path = file("nota.txt", "primeira linha\nsegunda linha")
        assertEquals("primeira linha\nsegunda linha", PlainReader.read(path))
    }

    @Test
    fun `binary content is not text`() {
        val path = createTempDirectory("docs-plain").resolve("renomeado.txt")
        Files.write(path, byteArrayOf(0x25, 0x50, 0x00, 0x44, 0x46))
        assertNull(PlainReader.read(path))
    }

    @Test
    fun `a missing file is null, not an exception`() {
        assertNull(PlainReader.read(Path.of("/nao/existe/arquivo.txt")))
    }

    @Test
    fun `compact drops blank lines, indentation and control characters`() {
        val raw = "linha\r\ncom\ttab\u0007 e controle\r\n\r\n\r\n   fim"
        assertEquals("linha\ncom tab e controle\nfim", PlainReader.compact(raw))
    }

    @Test
    fun `compact keeps the text under the cap`() {
        val raw = "a".repeat(MAX_TEXT_CHARS + 5_000)
        assertEquals(MAX_TEXT_CHARS, PlainReader.compact(raw).length)
    }

    @Test
    fun `a huge file is read only up to the limit`() {
        val path = createTempDirectory("docs-plain").resolve("grande.log")
        Files.writeString(path, "x".repeat((MAX_TEXT_FILE_BYTES + 1024).toInt()))
        val text = PlainReader.read(path)
        assertTrue(text != null && text.length == MAX_TEXT_CHARS)
    }
}
