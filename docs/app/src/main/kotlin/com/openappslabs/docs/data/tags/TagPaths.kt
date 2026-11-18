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

package com.openappslabs.docs.data.tags

/**
 * Rules for tag paths: `pessoal`, `academico/faculdade`, `academico/faculdade/2026`.
 *
 * Everything is lowercased, because the tag is an identifier used for filtering
 * and prefix matching — "Acadêmico" and "academico" being two tags would be a
 * bug the user cannot see. Accents are kept: `academico` and `académico` are
 * different words, not a typing mistake to auto-fix.
 *
 * Invalid input returns `null` instead of throwing: the caller is a text field
 * and has to show a message, not crash.
 */
object TagPaths {

    const val SEPARATOR = '/'

    /** Deep enough for `pessoal/financas/impostos/2026`, short enough to read. */
    const val MAX_LENGTH = 96
    const val MAX_SEGMENT = 32
    const val MAX_DEPTH = 5

    private val forbidden = setOf(".", "..")

    /**
     * `"  Acadêmico / Faculdade  "` -> `"academico/faculdade"`.
     * Returns null when the result would be empty, too long, too deep or
     * contains control characters.
     */
    fun normalize(raw: String): String? {
        if (raw.any { it.isISOControl() }) return null
        val segments = raw.split(SEPARATOR)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.size > MAX_DEPTH) return null
        if (segments.any { it in forbidden || it.length > MAX_SEGMENT }) return null
        val path = segments.joinToString(SEPARATOR.toString())
        return if (path.length > MAX_LENGTH) null else path
    }

    /**
     * Every prefix of the path, including itself: `a/b/c` ->
     * `[a, a/b, a/b/c]`. This is what makes the parent tags exist for free.
     */
    fun chain(path: String): List<String> {
        if (path.isEmpty()) return emptyList()
        val parts = path.split(SEPARATOR)
        return parts.indices.map { index -> parts.subList(0, index + 1).joinToString(SEPARATOR.toString()) }
    }

    /** `"academico/faculdade"` -> `"faculdade"`. */
    fun name(path: String): String = path.substringAfterLast(SEPARATOR)

    /** Depth of the path in the tree; a root tag is 0. */
    fun depth(path: String): Int = path.count { it == SEPARATOR }

    /** `LIKE` pattern matching the tag itself and everything under it. */
    fun descendantPattern(path: String): String = escapeLike(path) + SEPARATOR + "%"

    /**
     * True when [docTags] includes [filter] or a tag under it.
     *
     * Same rule the SQL filter uses for one tag: selecting `academico` keeps a
     * document tagged `academico/faculdade`. Used when several filters are
     * active and the AND has to run in memory.
     */
    fun covers(docTags: Iterable<String>, filter: String): Boolean =
        docTags.any { it == filter || it.startsWith("$filter$SEPARATOR") }

    /** Escapes the `LIKE` wildcards so a tag with `%` stays literal. */
    fun escapeLike(value: String): String = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
}
