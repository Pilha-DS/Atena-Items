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

package com.openappslabs.jotter.data.source

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.openappslabs.jotter.data.model.DeletedLink
import com.openappslabs.jotter.data.model.Link
import kotlinx.coroutines.flow.Flow

@Dao
interface LinksDao {

    /**
     * The inbox, in the order that makes it useful: whatever was saved or
     * opened most recently first. `MAX(x, y)` is the two-argument SQLite
     * function, so a link you open today beats one you saved months ago and
     * a link you just saved is visible without an extra tap.
     */
    @Query("SELECT * FROM links ORDER BY MAX(lastOpenedTime, createdTime) DESC, id DESC")
    fun getAllLinks(): Flow<List<Link>>

    @Query("SELECT * FROM links WHERE id = :id")
    suspend fun getById(id: Int): Link?

    @Query("SELECT * FROM links WHERE url = :url LIMIT 1")
    suspend fun getByUrl(url: String): Link?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(link: Link): Long

    @Update
    suspend fun update(link: Link)

    /**
     * Opening ranks the link and travels to the other devices, so the order is
     * the same everywhere. It is one row update, debounced by the sync engine
     * like any other edit.
     */
    @Query(
        "UPDATE links SET lastOpenedTime = :openedTime, updatedTime = :openedTime, " +
            "openCount = openCount + 1, needsSync = 1 WHERE id = :id"
    )
    suspend fun markOpened(id: Int, openedTime: Long)

    @Query("DELETE FROM links WHERE id = :id")
    suspend fun deleteById(id: Int)

    /** Local wipe (data management) must not replicate as a deletion. */
    @Query("DELETE FROM links")
    suspend fun deleteAll()

    // ------------------------------------------------------------- sync side

    @Query("SELECT * FROM links WHERE needsSync = 1")
    suspend fun getPendingLinks(): List<Link>

    @Query("SELECT COUNT(*) FROM links WHERE needsSync = 1")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM deleted_links")
    fun observePendingDeletionCount(): Flow<Int>

    @Query("SELECT * FROM links WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): Link?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFromServer(link: Link): Long

    /** Row identified by its primary key, so the local `id` is preserved. */
    @Update
    suspend fun updateFromServer(link: Link)

    @Query("UPDATE links SET needsSync = 0 WHERE uuid IN (:uuids)")
    suspend fun markSynced(uuids: List<String>)

    @Query("DELETE FROM links WHERE uuid = :uuid")
    suspend fun deleteByUuid(uuid: String)

    @Query("INSERT OR REPLACE INTO deleted_links(uuid, deletedAt) VALUES(:uuid, :deletedAt)")
    suspend fun addTombstone(uuid: String, deletedAt: Long)

    @Query("SELECT * FROM deleted_links")
    suspend fun getPendingDeletions(): List<DeletedLink>

    @Query("DELETE FROM deleted_links WHERE uuid IN (:uuids)")
    suspend fun clearDeletions(uuids: List<String>)

    @Query("DELETE FROM deleted_links")
    suspend fun clearAllDeletions()
}
