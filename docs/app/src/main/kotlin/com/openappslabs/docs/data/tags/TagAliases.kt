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
 * Rules for tag search aliases: short nicknames, never a path.
 *
 * Lowercased like [TagPaths] so `NFe` and `nfe` are the same alias. Accents
 * stay: they change the word. A slash is refused because an alias is not a
 * hierarchy — that already lives in the tag path.
 */
object TagAliases {

    const val MAX_LENGTH = 32

    /**
     * `"  NFe  "` -> `"nfe"`. Returns null when empty, too long, a path, or
     * control characters.
     */
    fun normalize(raw: String): String? {
        if (raw.any { it.isISOControl() }) return null
        if (raw.contains(TagPaths.SEPARATOR)) return null
        val alias = raw.trim().lowercase()
        if (alias.isEmpty() || alias.length > MAX_LENGTH) return null
        return alias
    }

    /**
     * True when [alias] only repeats the tag's own name or full path — those
     * already match as a tag hit, so storing them would be noise.
     */
    fun redundant(alias: String, tagPath: String): Boolean {
        val name = TagPaths.name(tagPath)
        return alias == tagPath || alias == name
    }
}
