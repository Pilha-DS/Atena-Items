/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Jotter is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Jotter.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.jotter.data.model

/**
 * How a note's content is displayed when it is read.
 *
 * The note itself is always stored as plain text: this only tells the reader
 * which renderer to use for that text. Switching formats never rewrites the
 * content, so it is always reversible.
 */
enum class NoteFormat(
    /** Value stored in the database; keep it stable. */
    val key: String,
    /** Name shown in settings and sheets. */
    val label: String,
    /** Very short badge for the note card. */
    val shortLabel: String
) {
    TEXT("text", "Plain text", "TXT"),
    MARKDOWN("markdown", "Markdown", "MD"),
    HTML("html", "HTML", "HTML"),
    JSON("json", "JSON", "JSON"),
    XML("xml", "XML", "XML");

    /** Whether reading the note produces something other than the raw text. */
    val isRich: Boolean get() = this != TEXT

    companion object {
        fun fromKey(key: String?): NoteFormat =
            entries.firstOrNull { it.key == key } ?: TEXT
    }
}
