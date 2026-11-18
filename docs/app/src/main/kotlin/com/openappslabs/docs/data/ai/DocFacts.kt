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

package com.openappslabs.docs.data.ai

import com.openappslabs.docs.data.model.Doc
import com.openappslabs.docs.data.model.DocKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The basics of a file, written into the prompt so the model classifies from
 * facts and not from the name alone.
 *
 * A name such as `35489062268253340000168000000000000226113297522058.xml` says
 * nothing; knowing it is a 5 KB XML from October changes the answer, and a
 * `PDF` of 67 KB is a different animal from a 4 MB scan.
 *
 * The dates are the dates of the **file on the computer**, and the prompt says
 * so. A file copied into the library last Tuesday has Tuesday as its birth
 * time no matter when the document inside it was written — labelling that as
 * "the document's date" is how a certificate of the second semester ends up
 * tagged with the month it was copied in.
 *
 * [createdTime] comes from the disk and is nullable on purpose — not every
 * filesystem keeps a birth time, and a made-up date would be worse than none.
 */
data class DocFacts(
    val kind: String,
    val size: Long,
    val createdTime: Long? = null,
    val modifiedTime: Long? = null
) {
    /**
     * Lines for the prompt, in a fixed order.
     *
     * The kind and the size share a line because they are one fact — "what
     * this is" — and the dates get their own, since "quando" is what often
     * decides between two folders of the same subject.
     */
    fun lines(zone: ZoneId = ZoneId.systemDefault()): List<String> {
        // `size` read into a local on purpose: inside `buildList` the receiver
        // is a `MutableList`, whose own `size` is an Int and would shadow this
        // one.
        val bytes = size
        val kindLine = "Tipo: $kind" + if (bytes > 0) " · ${sizeLabel(bytes)}" else ""
        return buildList {
            add(kindLine)
            val created = createdTime?.takeIf { it > 0 }
            if (created != null) add("Criado no disco em: ${day(created, zone)}")
            // The modification date is the one the list already shows; it is the
            // freshest signal and the only one every filesystem has.
            val modified = modifiedTime?.takeIf { it > 0 }
            if (modified != null) add("Modificado no disco em: ${day(modified, zone)}")
        }
    }

    companion object {
        /** The facts the index already knows, without touching the disk. */
        fun of(doc: Doc, createdTime: Long? = null): DocFacts = DocFacts(
            kind = kindLabel(doc.kind),
            size = doc.size,
            createdTime = createdTime,
            modifiedTime = doc.modifiedTime
        )

        /**
         * Portuguese label for the kind.
         *
         * A copy of what the sidebar says, and not a call into it: the prompt
         * lives in `data` and must not depend on the UI. The wording has to
         * match, though — a model told "PDF" and a user reading "PDF" agree.
         */
        fun kindLabel(kind: DocKind): String = when (kind) {
            DocKind.PDF -> "PDF"
            DocKind.WORD -> "documento de texto"
            DocKind.SHEET -> "planilha"
            DocKind.SLIDES -> "apresentação"
            DocKind.TEXT -> "texto simples"
            DocKind.MARKDOWN -> "texto em Markdown"
            DocKind.IMAGE -> "imagem"
            DocKind.AUDIO -> "áudio"
            DocKind.VIDEO -> "vídeo"
            DocKind.ARCHIVE -> "arquivo compactado"
            DocKind.CODE -> "código ou dado estruturado"
            DocKind.OTHER -> "arquivo"
        }

        /**
         * Size with decimal units, like Dolphin shows them (1000, not 1024).
         *
         * Deliberately the same rule as `formatSize` in the UI: two different
         * sizes for the same file, one in the list and one in the prompt, is
         * the kind of detail that makes a person distrust the whole feature.
         */
        fun sizeLabel(bytes: Long): String {
            if (bytes < 1000) return "$bytes B"
            val units = listOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var unit = -1
            while (value >= 1000 && unit < units.lastIndex) {
                value /= 1000
                unit += 1
            }
            val pattern = if (value >= 100) "%.0f" else "%.1f"
            return String.format(Locale.ROOT, pattern, value).replace('.', ',') + " " + units[unit]
        }

        private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

        private fun day(millis: Long, zone: ZoneId): String =
            dayFormat.format(Instant.ofEpochMilli(millis).atZone(zone))
    }
}
