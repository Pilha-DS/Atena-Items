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

/**
 * Tags whose whole name is a date: `2026`, `mes-11`, `2025/mes-11`.
 *
 * A date tag is only suggested when the *document's* text backs it — never the
 * copy date on the file. When the library already has separate year and month
 * tags (`2018`…`2027`, `mes-1`…`mes-12`), those are preferred over inventing
 * a compound `2025/mes-11`.
 *
 * Only numeric shapes are recognised as "date-only". A month name used as a
 * subject (`abril` the project) is left alone.
 */
internal object DateTag {

    /** A year or a day on its own: `2026`, `25`, `1998`. */
    private val plainNumber = Regex("""\d{1,4}""")

    /** `mes-11`, `mes 11`, `mes11`, `m11`, `m/11`. */
    private val monthName = Regex("""m(?:es)?[-_ .:/]?\d{1,2}""")

    /** `11/2026`, `2026-11`, `13-11-2026`, `2026_11`. */
    private val dateShape = Regex("""\d{1,4}[-_/.]\d{1,4}(?:[-_/.]\d{1,4})?""")

    private val monthLevel = Regex("""m(?:es)?[-_. ]?(\d{1,2})""")

    private val semesterLevel = Regex("""semestre[-_. ]?(\d)""")

    /**
     * True when the whole path is nothing but dates.
     *
     * `2026`, `mes-11` and `11/2026` are date tags. `unip/2026` is not: it says
     * `unip` first, and that is a subject the model may legitimately reuse.
     */
    fun isDateOnly(path: String): Boolean {
        val levels = path.split('/').filter { it.isNotBlank() }
        return levels.isNotEmpty() && levels.all { isDate(it.trim().lowercase()) }
    }

    /**
     * Whether a date tag may be suggested for a document with this text.
     *
     * [stamp] is the date read from the document (when already known): a bare
     * `mes-11` is allowed only when that stamp's month is 11, and a bare `2025`
     * only when the stamp's year is 2025. Without a stamp, compound tags still
     * go through [DocDate.supports].
     */
    fun allowedIn(path: String, text: String, stamp: DocDate.Stamp? = null): Boolean {
        if (!isDateOnly(path)) return true
        val levels = path.split('/').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val resolved = stamp ?: DocDate.of(text)
        // A semester document must not pick up `mes-11` from "Data e hora".
        if (resolved != null && resolved.month == null && resolved.semester != null) {
            if (levels.any { monthLevel.containsMatchIn(it) }) return false
        }
        if (levels.size == 1) {
            val level = levels[0]
            if (level.matches(yearLevel)) {
                return resolved?.year == level.toInt() || DocDate.supports(text, path)
            }
            monthLevel.find(level)?.groupValues?.get(1)?.toIntOrNull()?.let { month ->
                return resolved?.month == month
            }
            semesterLevel.find(level)?.groupValues?.get(1)?.toIntOrNull()?.let { semester ->
                return resolved?.semester == semester
            }
            return false
        }
        return DocDate.supports(text, path)
    }

    /**
     * A compound date tag (`2025/mes-4`) worth matching by word heuristics.
     *
     * Bare years and months are listed for the model and resolved explicitly
     * from the document date; putting `mes-11` through the word scorer would
     * match the letters `mes` inside unrelated words.
     */
    fun isReusable(path: String): Boolean {
        val levels = path.split('/').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (levels.size < 2) return false
        val hasYear = levels.any { it.matches(yearLevel) }
        val hasMonth = levels.any {
            it.matches(monthName) || it.matches(dateShape) || it.matches(monthWord)
        }
        return hasYear && hasMonth
    }

    /**
     * Turns the shapes a model invents into the library's date tag.
     *
     * `2025/11` and `11/2025` become `2025/mes-11`. Paths that are not a bare
     * year/month pair are left alone.
     */
    fun canonicalize(path: String): String {
        val levels = path.split('/').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (levels.size != 2) return path
        val left = levels[0]
        val right = levels[1]
        yearAndMonth(left, right)?.let { (year, month) -> return "$year/mes-$month" }
        yearAndMonth(right, left)?.let { (year, month) -> return "$year/mes-$month" }
        return path
    }

    /**
     * How [stamp] should be tagged given the tags that already exist.
     *
     * Order of preference:
     * 1. the compound tag itself (`2025/mes-11`) when it is already in the library;
     * 2. the separate year and month (or semester) tags the user already has;
     * 3. invent the compound tag.
     */
    fun resolveExisting(stamp: DocDate.Stamp, library: Collection<String>): List<String> {
        val lib = library.mapTo(HashSet()) { it.lowercase() }
        if (stamp.tag in lib) return listOf(stamp.tag)
        val parts = ArrayList<String>(2)
        val year = stamp.year.toString()
        if (year in lib) parts += year
        stamp.month?.let { month ->
            val tag = "mes-$month"
            if (tag in lib) parts += tag
        }
        stamp.semester?.let { semester ->
            val tag = "semestre-$semester"
            if (tag in lib) parts += tag
        }
        if (parts.isNotEmpty()) return parts
        return listOf(stamp.tag)
    }

    /**
     * Maps one suggested path onto the library's date vocabulary.
     *
     * A subject tag is unchanged. A date becomes the year/month tags that
     * already exist, when the library is organized that way.
     */
    fun remap(path: String, library: Collection<String>, stamp: DocDate.Stamp?): List<String> {
        val canonical = canonicalize(path)
        if (!isDateOnly(canonical)) return listOf(path)
        val fromTag = stampFrom(canonical)
        val effective = fromTag ?: stamp ?: return listOf(canonical)
        return resolveExisting(effective, library)
    }

    /** `(year, month)` when [yearPart] is a year and [monthPart] is a bare 1–12. */
    private fun yearAndMonth(yearPart: String, monthPart: String): Pair<Int, Int>? {
        if (!yearPart.matches(yearLevel)) return null
        if (monthPart.startsWith("mes") || monthPart.startsWith("semestre")) return null
        val month = monthPart.toIntOrNull() ?: return null
        if (month !in 1..12) return null
        return yearPart.toInt() to month
    }

    private fun stampFrom(path: String): DocDate.Stamp? {
        val levels = path.split('/').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val year = levels.firstNotNullOfOrNull { level ->
            yearLevel.find(level)?.value?.toInt()
        } ?: return null
        val month = levels.firstNotNullOfOrNull { level ->
            monthLevel.find(level)?.groupValues?.get(1)?.toIntOrNull()
        }
        val semester = levels.firstNotNullOfOrNull { level ->
            semesterLevel.find(level)?.groupValues?.get(1)?.toIntOrNull()
        }
        return DocDate.Stamp(year = year, month = month, semester = semester)
    }

    private val yearLevel = Regex("""(?:19|20)\d{2}""")

    private val monthWord = Regex(
        "janeiro|fevereiro|marco|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro"
    )

    private fun isDate(level: String): Boolean =
        level.matches(plainNumber) || level.matches(monthName) || level.matches(dateShape)
}
