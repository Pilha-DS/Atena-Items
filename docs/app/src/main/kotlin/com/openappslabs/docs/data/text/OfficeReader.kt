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

import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Text out of the XML inside an OOXML/ODF package, without a parser library.
 *
 * These formats are zipped XML and the app only needs the words: replacing the
 * closing paragraph tags with newlines and dropping the rest of the markup is
 * enough for search and for the tag suggestion, and it costs no dependency.
 * Tables, footnotes and headers are not read — the body text is what the user
 * searches for.
 */
object OfficeReader {

    fun read(file: Path, kind: String): String? = runCatching {
        ZipFile(file.toFile()).use { zip ->
            when (kind) {
                "docx" -> zip.xml("word/document.xml")
                "odt" -> zip.xml("content.xml")
                "xlsx" -> zip.xml("xl/sharedStrings.xml")
                "pptx" -> zip.slides()
                else -> null
            }
        }
    }.getOrNull()

    private fun ZipFile.xml(entry: String): String? {
        val file = getEntry(entry) ?: return null
        val bytes = getInputStream(file).use { it.readNBytes(MAX_TEXT_FILE_BYTES.toInt()) }
        return PlainReader.compact(toText(String(bytes, Charsets.UTF_8)))
    }

    private fun ZipFile.slides(): String? {
        val slides = entries().asSequence()
            .filter { it.name.startsWith("ppt/slides/slide") && it.name.endsWith(".xml") }
            .sortedBy { it.name }
            .take(200)
            .toList()
        if (slides.isEmpty()) return null
        val builder = StringBuilder()
        for (slide in slides) {
            val bytes = getInputStream(slide).use { it.readNBytes(MAX_TEXT_FILE_BYTES.toInt()) }
            val text = toText(String(bytes, Charsets.UTF_8))
            if (text.isNotBlank()) {
                builder.append(text).append('\n')
                if (builder.length >= MAX_TEXT_CHARS) break
            }
        }
        return PlainReader.compact(builder.toString())
    }

    /** Paragraphs become newlines; every other tag simply disappears. */
    internal fun toText(xml: String): String {
        val withBreaks = xml
            .replace("</w:p>", "\n")
            .replace("</text:p>", "\n")
            .replace("</text:h>", "\n")
            .replace("</si>", "\n")
            .replace("</a:p>", "\n")
            .replace("<w:tab/>", " ")
            .replace("<w:br/>", "\n")
            .replace("<text:tab/>", " ")
            .replace("<text:line-break/>", "\n")
        val stripped = TAG.replace(withBreaks, "")
        return unescape(stripped)
    }

    private val TAG = Regex("<[^>]{0,4000}>")

    /** The five predefined entities plus the numeric ones Word likes to emit. */
    internal fun unescape(raw: String): String {
        if ('&' !in raw) return raw
        val builder = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            if (char != '&') {
                builder.append(char)
                index += 1
                continue
            }
            val end = raw.indexOf(';', index + 1)
            if (end < 0 || end - index > 12) {
                builder.append(char)
                index += 1
                continue
            }
            val entity = raw.substring(index + 1, end)
            val decoded = when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "apos" -> "'"
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.substring(2).toIntOrNull(16)?.toChar()?.toString()
                entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.toChar()?.toString()
                else -> null
            }
            if (decoded == null) {
                builder.append(char)
                index += 1
            } else {
                builder.append(decoded)
                index = end + 1
            }
        }
        return builder.toString()
    }
}
