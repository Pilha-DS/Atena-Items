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

import java.text.Normalizer

/**
 * The date a document is *about*, read from its own text.
 *
 * The date of the file on disk is the day it was copied into the library, and
 * tagging every certificate with the month of the copy is noise. Inside a
 * comprovante de envio the trap is subtler: `Data e hora: 13/11/2026` is when
 * the file was uploaded to the portal, while `Data da atividade: 2026 - 2º
 * Semestre` is what the document is about. The period wins; the submission
 * timestamp does not become `mes-11`.
 *
 * The shape is a two-level tag, `ano/mes-N` or `ano/semestre-N`, so one filter
 * narrows by year and another by month or semester. When the document only
 * tells the year, the tag is just the year — the app does not invent a month
 * it cannot see.
 */
object DocDate {

    /** A year with, optionally, the month or the semester the document covers. */
    data class Stamp(val year: Int, val month: Int? = null, val semester: Int? = null) {

        /** `2026/mes-11`, `2026/semestre-2` or `2026`. */
        val tag: String
            get() = when {
                month != null -> "$year/mes-$month"
                semester != null -> "$year/semestre-$semester"
                else -> "$year"
            }

        /** The same thing in words, for the sentence the dialog shows. */
        val label: String
            get() = when {
                month != null -> "${monthName(month)} de $year"
                semester != null -> "${semester}º semestre de $year"
                else -> "$year"
            }
    }

    private val fullDate =
        Regex("""(?<!\d)(\d{1,2})\s*[/.-]\s*(\d{1,2})\s*[/.-]\s*(\d{4})(?!\d)""")
    private val isoDate =
        Regex("""(?<!\d)(\d{4})\s*[/.-]\s*(\d{1,2})\s*[/.-]\s*(\d{1,2})(?!\d)""")
    private val monthYear =
        Regex("""(?<!\d)(\d{1,2})\s*[/.-]\s*(\d{4})(?!\d)""")
    private val semester = Regex("""(\d)\s*(?:o|º)?\s*semestre""")
    private val year = Regex("""(?<!\d)((?:19|20)\d{2})(?!\d)""")

    /**
     * OCR often splits `Data da atividade: 2025 - 2º` from the word `Semestre`
     * on the next lines. The year and the ordinal still name the period.
     */
    private val activitySemester = Regex(
        """data\s+da\s+atividade\s*:?\s*((?:19|20)\d{2})\s*[-–]?\s*(\d)\s*(?:o|º)?"""
    )

    private val monthNames: Map<String, Int> = mapOf(
        "janeiro" to 1, "fevereiro" to 2, "marco" to 3, "abril" to 4,
        "maio" to 5, "junho" to 6, "julho" to 7, "agosto" to 8,
        "setembro" to 9, "outubro" to 10, "novembro" to 11, "dezembro" to 12
    )

    /**
     * Lines that announce a date of the document itself (generic).
     *
     * Weaker than [periodLabels]: a bare "Data:" next to a number is a hint,
     * not proof that the number is what the document is about.
     */
    private val dateLabels = listOf(
        "data", "emitid", "emissao", "realiz", "conclu", "inicio", "termino",
        "assinatura", "expedicao", "publicacao", "validade"
    )

    /**
     * Labels that name the period the document covers — activity, competence,
     * referent month. These beat a calendar day printed as "data e hora".
     */
    private val periodLabels = listOf(
        "atividade", "periodo", "competencia", "referente", "vigencia", "semestre"
    )

    /**
     * Submission / upload timestamps on academic receipts.
     *
     * `Data e hora: 13/11/2026` is when the PDF hit the portal, not when the
     * activity happened. Treating it as the document date tags every late
     * submission with `mes-11`.
     */
    private val submissionLabels = listOf(
        "data e hora", "data/hora", "enviado em", "recebido em", "protocolado"
    )

    /** The best date the text offers, or null when it offers none. */
    fun of(text: String): Stamp? {
        if (text.isBlank()) return null
        val folded = fold(text)
        val found = ArrayList<Candidate>()
        var position = 0
        val lines = folded.lines()
        for ((index, line) in lines.withIndex()) {
            if (isSubmissionLine(line)) {
                position += line.length + 1
                continue
            }
            val bonus = lineBonus(line)
            fullDateIn(line)?.let { found += Candidate(bonus + 4, position, it) }
            monthYearIn(line)?.let { found += Candidate(bonus + 3, position, it) }
            semesterIn(line)?.let { found += Candidate(bonus + 5, position, it) }
            monthNameIn(line)?.let { found += Candidate(bonus + 2, position, it) }
            // Join this line with the next two: OCR parks "Semestre" alone.
            if (index < lines.lastIndex) {
                val window = lines.subList(index, minOf(lines.size, index + 3)).joinToString(" ")
                activitySemesterIn(window)?.let { found += Candidate(PERIOD_BONUS + 6, position, it) }
                semesterIn(window)?.let { found += Candidate(PERIOD_BONUS + 5, position, it) }
            }
            position += line.length + 1
        }
        activitySemesterIn(folded)?.let { found += Candidate(PERIOD_BONUS + 6, 0, it) }
        // The period the document covers beats a calendar day; on a tie, the
        // earliest labelled line wins.
        found.sortedWith(
            compareByDescending<Candidate> { it.score }.thenBy { it.position }
        ).firstOrNull()?.let { return it.stamp }
        return yearIn(folded)
    }

    /**
     * True when the text backs a date tag: the year has to be there, and a month
     * named by the tag has to appear in the content too.
     *
     * When [of] already decided the document is about a semester (no month), a
     * month tag is refused even if a submission day like `13/11/2026` sits in
     * the footer — that day is not what the document is about.
     */
    fun supports(text: String, tag: String): Boolean {
        val stamp = of(text)
        if (stamp != null && stamp.month == null && stamp.semester != null) {
            val hasMonth = tag.split('/').any {
                Regex("""m(?:es)?[-_. ]?\d{1,2}""").containsMatchIn(it.trim())
            }
            if (hasMonth) return false
        }
        val folded = fold(text)
        val levels = tag.split('/').map { it.trim() }
        val tagYear = levels.mapNotNull { level -> year.find(level)?.value?.toInt() }.firstOrNull()
            ?: return false
        if (!Regex("""(?<!\d)$tagYear(?!\d)""").containsMatchIn(folded)) return false
        val month = levels.mapNotNull { level ->
            Regex("""m(?:es)?[-_. ]?(\d{1,2})""").find(level)?.groupValues?.get(1)?.toIntOrNull()
        }.firstOrNull() ?: return true
        if (month !in 1..12) return false
        val named = monthName(month)?.let { folded.contains(it) } == true
        return named || Regex("""(?<!\d)0?$month(?!\d)""").containsMatchIn(folded)
    }

    private fun lineBonus(line: String): Int = when {
        periodLabels.any { line.contains(it) } -> PERIOD_BONUS
        dateLabels.any { line.contains(it) } -> LABEL_BONUS
        else -> 0
    }

    private fun isSubmissionLine(line: String): Boolean =
        submissionLabels.any { line.contains(it) }

    /** `2026/mes-11`; the month is the only level that matters here. */
    private fun fullDateIn(raw: String): Stamp? {
        fullDate.find(raw)?.let { match ->
            val day = match.groupValues[1].toInt()
            val month = match.groupValues[2].toInt()
            val year = match.groupValues[3].toInt()
            if (day in 1..31 && month in 1..12 && year in 1900..2100) return Stamp(year, month)
        }
        isoDate.find(raw)?.let { match ->
            val year = match.groupValues[1].toInt()
            val month = match.groupValues[2].toInt()
            val day = match.groupValues[3].toInt()
            if (day in 1..31 && month in 1..12 && year in 1900..2100) return Stamp(year, month)
        }
        return null
    }

    /** `11/2026` -> `2026/mes-11`. */
    private fun monthYearIn(raw: String): Stamp? {
        val match = monthYear.find(raw) ?: return null
        val month = match.groupValues[1].toInt()
        val year = match.groupValues[2].toInt()
        if (month !in 1..12 || year !in 1900..2100) return null
        return Stamp(year, month)
    }

    /** `2026 - 2º semestre` -> `2026/semestre-2`, only when a year is nearby. */
    private fun semesterIn(raw: String): Stamp? {
        val found = semester.find(raw) ?: return null
        val number = found.groupValues[1].toInt().takeIf { it in 1..2 } ?: return null
        val tagYear = year.find(raw)?.value?.toInt() ?: return null
        return Stamp(tagYear, semester = number)
    }

    /**
     * `Data da atividade: 2025 - 2º` (+ `Semestre` nearby) → `2025/semestre-2`.
     *
     * Requires the word "semestre" somewhere in [raw] so a bare `2º` in a
     * series number does not become a semester.
     */
    private fun activitySemesterIn(raw: String): Stamp? {
        if (!raw.contains("semestre")) return null
        val match = activitySemester.find(raw) ?: return null
        val tagYear = match.groupValues[1].toInt()
        val number = match.groupValues[2].toInt().takeIf { it in 1..2 } ?: return null
        return Stamp(tagYear, semester = number)
    }

    /** `novembro de 2026` -> `2026/mes-11`. */
    private fun monthNameIn(raw: String): Stamp? {
        for ((name, number) in monthNames) {
            val index = raw.indexOf(name)
            if (index < 0) continue
            val tail = raw.substring(index + name.length, minOf(raw.length, index + name.length + 24))
            val tagYear = year.find(tail)?.value?.toInt() ?: continue
            return Stamp(tagYear, number)
        }
        return null
    }

    private fun yearIn(raw: String): Stamp? =
        year.find(raw)?.value?.toInt()?.let { Stamp(it) }

    /** One date candidate and how much it deserves to be the answer. */
    private data class Candidate(val score: Int, val position: Int, val stamp: Stamp)

    /** Extra weight for a line that announces the date in words. */
    private const val LABEL_BONUS = 10

    /** Stronger than a calendar day: the period the document covers. */
    private const val PERIOD_BONUS = 20

    private fun monthName(month: Int?): String? =
        monthNames.entries.firstOrNull { it.value == month }?.key

    /** Lower case without accents, so `Semestre` and `semestre` are the same word. */
    internal fun fold(raw: String): String {
        if (raw.isEmpty()) return ""
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
        val builder = StringBuilder(decomposed.length)
        for (char in decomposed) {
            if (Character.getType(char) == Character.NON_SPACING_MARK.toInt()) continue
            builder.append(char.lowercaseChar())
        }
        return builder.toString()
    }
}
