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

package com.openappslabs.docs.data.move

import java.nio.file.Path

/**
 * Rules for a folder *inside* the library: `""` (the root), `academico`,
 * `academico/2026`.
 *
 * Different from [com.openappslabs.docs.data.tags.TagPaths] on purpose. A tag
 * is an identifier the app owns, so it is lowercased and limited. A folder is a
 * real directory on disk, created with the name the user will see in Dolphin —
 * case is kept, and only what could escape the library or produce a path that
 * `Files` refuses is rejected.
 *
 * The root is a legitimate target: moving a document back to the top of the
 * library is an ordinary choice, so it is spelled `""` and not `null`.
 */
object FolderPath {

    const val SEPARATOR = '/'

    /** The library root, as a target. */
    const val ROOT = ""

    /** Deep enough for `faculdade/2026/semestre-1`, short enough to read. */
    const val MAX_DEPTH = 5
    const val MAX_SEGMENT = 64
    const val MAX_LENGTH = 200

    private val FORBIDDEN = setOf(".", "..")

    /**
     * `" Academico / 2026/ "` -> `"Academico/2026"`; `"/"` and `"raiz"` -> `""`.
     *
     * Returns null when the path could not be used safely: control characters,
     * a Windows separator, `~`, a `.`/`..` segment, a hidden segment (leading
     * dot) or a path too deep/long. A leading or trailing `/` is forgiven,
     * because a model answering `/academico` means the folder inside the
     * library.
     */
    fun normalize(raw: String): String? {
        if (raw.any { it.isISOControl() }) return null
        if (raw.contains('\\') || raw.contains('~')) return null
        if (raw.trim().equals("raiz", ignoreCase = true)) return ROOT
        if (raw.trim().equals("root", ignoreCase = true)) return ROOT

        val segments = raw.split(SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (segments.isEmpty()) return ROOT
        if (segments.size > MAX_DEPTH) return null
        if (segments.any { it in FORBIDDEN }) return null
        // A hidden folder is not part of the library the scan walks.
        if (segments.any { it.startsWith('.') }) return null
        if (segments.any { it.length > MAX_SEGMENT }) return null
        if (segments.any { it.endsWith(' ') }) return null
        val path = segments.joinToString(SEPARATOR.toString())
        return if (path.length > MAX_LENGTH) null else path
    }

    /**
     * The absolute directory for [folder], guaranteed to be inside [root].
     *
     * [normalize] already refuses `..`, but this does not trust it: the check
     * after `normalize()` is what stops a future rule (or a symlinked parent)
     * from writing one directory above the library.
     */
    fun resolve(root: Path, folder: String): Path? {
        val normalized = normalize(folder) ?: return null
        val base = root.toAbsolutePath().normalize()
        val target = if (normalized.isEmpty()) base else base.resolve(normalized).normalize()
        return if (target.startsWith(base)) target else null
    }

    /** Folder of a relative path; "" when the document is at the root. */
    fun of(relPath: String): String =
        if (relPath.contains(SEPARATOR)) relPath.substringBeforeLast(SEPARATOR) else ROOT

    /** `join("a/b", "x.pdf")` -> `"a/b/x.pdf"`; `join("", "x.pdf")` -> `"x.pdf"`. */
    fun join(folder: String, name: String): String =
        if (folder.isEmpty()) name else folder + SEPARATOR + name

    /**
     * Keeps the on-disk spelling of every segment that already exists.
     *
     * The filesystem is case-sensitive: a model answering `academico` next to
     * an existing `Academico` would create a second folder that looks like the
     * first in the sidebar. The proposal is only re-cased, never re-worded.
     */
    fun reuseCase(target: String, existing: Collection<String>): String {
        if (target.isEmpty()) return target
        val known = existing
            .flatMap { it.split(SEPARATOR) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (known.isEmpty()) return target
        return target.split(SEPARATOR).joinToString(SEPARATOR.toString()) { segment ->
            known.firstOrNull { it.equals(segment, ignoreCase = true) } ?: segment
        }
    }
}
