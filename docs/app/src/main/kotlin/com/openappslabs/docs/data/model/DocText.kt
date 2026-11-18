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

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The text read out of one document, kept apart from `documents`.
 *
 * Separate table on purpose: the list query needs a few columns, not a few
 * megabytes, and a row here means "this file was already read". No row means
 * "not read yet"; an empty text means "read, nothing inside" (an image with no
 * text, a scanned page Tesseract could not see), so the app does not retry the
 * same file on every launch.
 *
 * It is disposable: losing it costs a re-read, never a document.
 */
@Entity(tableName = "doc_text")
data class DocText(
    @PrimaryKey val docId: Long,
    val text: String,
    /** How the text was obtained: `text`, `office`, `pdf`, `ocr` or `failed`. */
    val source: String,
    val charCount: Int,
    val extractedTime: Long
) {
    val hasText: Boolean get() = charCount > 0
}
