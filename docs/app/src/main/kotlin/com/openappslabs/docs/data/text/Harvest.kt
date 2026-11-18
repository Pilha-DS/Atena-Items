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

import com.openappslabs.docs.data.model.DocKind
import java.nio.file.Path

/**
 * Where the text of a document came from.
 *
 * Stored as a string in `doc_text.source`, so it survives a schema change
 * without a converter. `FAILED` exists to stop the app from retrying a file
 * that broke the extractor on every launch; `NONE` means the type simply has
 * no readable text (video, archive, a scanned page with no OCR available).
 */
enum class TextSource {
    TEXT,
    OFFICE,
    PDF,
    OCR,
    FAILED,
    NONE
}

/** What came out of one file: the text (may be empty) and how it was read. */
data class Harvest(val text: String, val source: TextSource) {
    val hasText: Boolean get() = text.isNotBlank()

    companion object {
        val none = Harvest("", TextSource.NONE)
        val failed = Harvest("", TextSource.FAILED)
    }
}

/**
 * Reads the text of one file.
 *
 * Returns `null` only when the file is gone by the time the read starts; every
 * other outcome is a [Harvest] with the reason, so the caller can stop asking.
 */
fun interface TextHarvester {
    fun harvest(file: Path, kind: DocKind): Harvest?
}