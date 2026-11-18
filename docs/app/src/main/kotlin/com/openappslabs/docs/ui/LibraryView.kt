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

package com.openappslabs.docs.ui

/** How many days of modification "Recentes" means. */
const val RECENT_WINDOW_DAYS = 7

private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

/**
 * What the list is showing, before the text search and the tag are applied.
 *
 * The view and the tag filter are independent dimensions on purpose: looking
 * at "Favoritos" filtered by `academico` is a legitimate question, and the two
 * compose in the same query.
 */
sealed interface LibraryView {
    data object All : LibraryView
    data object Recent : LibraryView
    data object Favorites : LibraryView
    data class Folder(val path: String) : LibraryView

    /** Cut-off for [Recent]; none for the other views. */
    fun recentSince(now: Long): Long =
        if (this is Recent) now - (RECENT_WINDOW_DAYS * DAY_MILLIS) else 0L
}
