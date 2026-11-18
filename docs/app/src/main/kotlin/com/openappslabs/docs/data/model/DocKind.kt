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

package com.openappslabs.docs.data.model

/**
 * What a file is, derived from its extension.
 *
 * The kind only drives the icon and the label: the app never opens or parses
 * the content at this stage, so a wrong guess here is cosmetic and never stops
 * a document from being listed or opened in the default app.
 */
enum class DocKind {
    PDF,
    WORD,
    SHEET,
    SLIDES,
    TEXT,
    MARKDOWN,
    IMAGE,
    AUDIO,
    VIDEO,
    ARCHIVE,
    CODE,
    OTHER;

    companion object {
        private val byExtension: Map<String, DocKind> = buildMap {
            fun put(kind: DocKind, vararg extensions: String) {
                extensions.forEach { put(it, kind) }
            }
            put(PDF, "pdf")
            put(WORD, "doc", "docx", "odt", "rtf", "ott", "pages")
            put(SHEET, "xls", "xlsx", "ods", "csv", "ots")
            put(SLIDES, "ppt", "pptx", "odp", "otp")
            put(TEXT, "txt", "log", "text", "diff", "patch")
            put(MARKDOWN, "md", "markdown", "rst", "adoc")
            put(IMAGE, "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "tif", "tiff", "heic", "avif")
            put(AUDIO, "mp3", "ogg", "oga", "wav", "flac", "m4a", "opus", "aac", "wma")
            put(VIDEO, "mp4", "mkv", "webm", "avi", "mov", "m4v", "wmv")
            put(ARCHIVE, "zip", "tar", "gz", "tgz", "bz2", "xz", "zst", "7z", "rar")
            put(
                CODE,
                "kt", "kts", "java", "py", "js", "ts", "tsx", "json", "xml", "yaml", "yml",
                "sh", "bash", "c", "h", "cpp", "hpp", "rs", "go", "sql", "html", "css", "qml", "toml"
            )
        }

        /** Lower-cased extension after the last dot, or "" when there is none. */
        fun extensionOf(fileName: String): String {
            val dot = fileName.lastIndexOf('.')
            if (dot <= 0 || dot == fileName.lastIndex) return ""
            return fileName.substring(dot + 1).lowercase()
        }

        fun fromFileName(fileName: String): DocKind =
            byExtension[extensionOf(fileName)] ?: OTHER
    }
}
