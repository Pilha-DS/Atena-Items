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

package com.openappslabs.docs.data.source

/**
 * Builds the `LIKE` pattern for the name search.
 *
 * `%` and `_` are wildcards in SQL and `\` is the escape character here, so a
 * file literally named `50%_off.txt` must not turn the search into a full
 * match. Everything is escaped once and the query is wrapped in `%…%`.
 */
fun sqlLikePattern(query: String): String = "%${likeEscaped(query)}%"

/**
 * The pattern for everything **below** [folder]: `academico/2026/prova.pdf` for
 * `academico/2026`, and not the folder itself.
 *
 * Same escaping as [sqlLikePattern], so a folder named `50%_off` matches only
 * its own contents.
 */
fun sqlLikeUnder(folder: String): String = "${likeEscaped(folder)}/%"

private fun likeEscaped(value: String): String = value
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")
