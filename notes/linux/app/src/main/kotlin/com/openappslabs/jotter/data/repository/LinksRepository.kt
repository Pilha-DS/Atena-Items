/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Jotter is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Jotter.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.jotter.data.repository

import com.openappslabs.jotter.data.model.Link
import kotlinx.coroutines.flow.Flow

/**
 * The link inbox: save now, find later.
 *
 * Saving is one call and returns the stored row, so the screen can show it
 * immediately. The same URL saved twice is one row (see [saveLink]).
 */
interface LinksRepository {

    fun observeLinks(): Flow<List<Link>>

    /**
     * Stores a link, normalizing [rawUrl] first. Returns `null` when the text
     * is not an http(s) link. When the URL is already in the inbox the existing
     * row is refreshed (title if one was given, timestamp) instead of a
     * duplicate being created.
     */
    suspend fun saveLink(rawUrl: String, title: String = ""): Link?

    /** Optional label, edited from the list. */
    suspend fun rename(linkId: Int, title: String)

    /** Counts an open, so the list ranks by what is actually used. */
    suspend fun markOpened(linkId: Int)

    /** Hard delete, announced to the other devices on the next sync. */
    suspend fun delete(link: Link)
}
