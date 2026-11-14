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

import androidx.room.withTransaction
import com.openappslabs.jotter.data.model.Link
import com.openappslabs.jotter.data.source.JotterDatabase
import com.openappslabs.jotter.data.source.LinksDao
import com.openappslabs.jotter.utils.LinkUrl
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject

class LinksRepositoryImpl @Inject constructor(
    private val linksDao: LinksDao,
    private val database: JotterDatabase
) : LinksRepository {

    override fun observeLinks(): Flow<List<Link>> = linksDao.getAllLinks()

    override suspend fun saveLink(rawUrl: String, title: String): Link? {
        val url = LinkUrl.normalize(rawUrl) ?: return null
        val now = System.currentTimeMillis()
        val cleanTitle = title.trim()

        val existing = linksDao.getByUrl(url)
        if (existing != null) {
            // Saving the same page again is a "bring it back up", not a
            // duplicate: it moves to the top and takes the new label if any.
            val updated = existing.copy(
                title = cleanTitle.ifBlank { existing.title },
                updatedTime = now,
                needsSync = true
            )
            linksDao.update(updated)
            return updated
        }

        val draft = Link(
            url = url,
            title = cleanTitle,
            createdTime = now,
            updatedTime = now,
            uuid = UUID.randomUUID().toString(),
            needsSync = true
        )
        val id = linksDao.insert(draft).toInt()
        return linksDao.getById(id) ?: draft.copy(id = id)
    }

    override suspend fun rename(linkId: Int, title: String) {
        val link = linksDao.getById(linkId) ?: return
        linksDao.update(
            link.copy(
                title = title.trim(),
                updatedTime = System.currentTimeMillis(),
                needsSync = true
            )
        )
    }

    override suspend fun markOpened(linkId: Int) {
        linksDao.markOpened(linkId, System.currentTimeMillis())
    }

    override suspend fun delete(link: Link) {
        database.withTransaction {
            if (link.uuid.isNotBlank()) {
                linksDao.addTombstone(link.uuid, System.currentTimeMillis())
            }
            linksDao.deleteById(link.id)
        }
    }
}
