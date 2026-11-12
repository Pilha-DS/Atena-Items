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
import com.openappslabs.jotter.data.model.DeletedNote
import com.openappslabs.jotter.data.model.Note
import kotlinx.coroutines.flow.Flow

/**
 * Everything the sync engine needs to read or write, kept apart from the
 * per-screen queries so the sync path is easy to audit.
 */
@Dao
interface SyncDao {

    /**
     * Notes changed locally that still have to be pushed. A blank uuid means
     * the row predates the sync migration and has nothing to match on.
     */
    @Query("SELECT * FROM notes WHERE needsSync = 1 AND uuid != ''")
    suspend fun getPendingNotes(): List<Note>

    /** Used to count "there is something to push" without reading a body. */
    @Query("SELECT COUNT(*) FROM notes WHERE needsSync = 1 AND uuid != ''")
    fun observePendingCount(): Flow<Int>

    /** Deletions alone must push too, without waiting for the periodic tick. */
    @Query("SELECT COUNT(*) FROM deleted_notes")
    fun observePendingDeletionCount(): Flow<Int>

    @Query("SELECT * FROM notes WHERE uuid = :uuid")
    suspend fun getNoteByUuid(uuid: String): Note?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFromServer(note: Note): Long

    /** Row identified by its primary key, so the local `id` is preserved. */
    @Update
    suspend fun updateFromServer(note: Note)

    @Query("UPDATE notes SET needsSync = 0 WHERE uuid IN (:uuids)")
    suspend fun markSynced(uuids: List<String>)

    @Query("DELETE FROM notes WHERE uuid = :uuid")
    suspend fun deleteByUuid(uuid: String)

    @Query("SELECT * FROM deleted_notes")
    suspend fun getPendingDeletions(): List<DeletedNote>

    @Query("DELETE FROM deleted_notes WHERE uuid IN (:uuids)")
    suspend fun clearDeletions(uuids: List<String>)

    @Query("DELETE FROM deleted_notes")
    suspend fun clearAllDeletions()
}
