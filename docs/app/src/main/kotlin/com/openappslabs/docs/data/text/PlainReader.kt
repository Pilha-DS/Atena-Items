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

/**
 * Reads the files whose content is already text.
 *
 * The cap is on bytes read, not on the resulting string: a 2 GB log cannot
 * stall the scan, and the first 4 MB are more than enough to search and to
 * describe the document.
 */
object PlainReader {

    fun read(file: Path, limitBytes: Long = MAX_TEXT_FILE_BYTES): String? = runCatching {
        if (!Files.isRegularFile(file)) return null
        Files.newInputStream(file).use { stream ->
            val bytes = stream.readNBytes(limitBytes.toInt())
            if (bytes.isEmpty()) return ""
            // A NUL byte means binary (a renamed archive, a broken export):
            // indexing that would poison the search with noise.
            if (bytes.any { it == 0.toByte() }) return null
            compact(String(bytes, Charsets.UTF_8))
        }
    }.getOrNull()

    /**
     * Makes the text cheaper to store and to search: one line per source
     * paragraph, no control characters, no indentation, no blank lines, cut at
     * [MAX_TEXT_CHARS]. The layout is already lost in OCR and in XML, and the
     * reader of this column is a search query, not a document view.
     */
    fun compact(raw: String): String {
        if (raw.isEmpty()) return ""
        val normalized = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\t', ' ')
        val lines = normalized.split('\n')
            .map { line -> line.filter { it >= ' ' && it != '\u007f' }.trim() }
            .filter { it.isNotEmpty() }
        val joined = lines.joinToString("\n")
        return if (joined.length > MAX_TEXT_CHARS) joined.substring(0, MAX_TEXT_CHARS) else joined
    }
}
