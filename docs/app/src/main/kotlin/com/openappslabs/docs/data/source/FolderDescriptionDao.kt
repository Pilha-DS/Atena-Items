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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.openappslabs.docs.data.model.FolderDescription
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes the sentences the user writes about a folder.
 *
 * Descriptions live here and not in the filesystem: a `.docs` file inside the
 * user's folder would show up in Dolphin, in the scan and in every other app,
 * and the person asked for a note *about* the folder, not a document in it.
 */
@Dao
interface FolderDescriptionDao {

    @Query("SELECT * FROM folder_descriptions ORDER BY path ASC")
    fun observeAll(): Flow<List<FolderDescription>>

    @Query("SELECT * FROM folder_descriptions ORDER BY path ASC")
    suspend fun all(): List<FolderDescription>

    @Query("SELECT * FROM folder_descriptions WHERE path = :path LIMIT 1")
    suspend fun find(path: String): FolderDescription?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(description: FolderDescription)

    @Query("DELETE FROM folder_descriptions WHERE path = :path")
    suspend fun delete(path: String)

    /**
     * Forgets [path] and everything below it, used when the folder is deleted
     * from the window: a description of a folder that no longer exists would
     * come back attached to an unrelated folder created later with the same
     * name.
     */
    @Query("DELETE FROM folder_descriptions WHERE path = :path OR path LIKE :prefix ESCAPE '\\'")
    suspend fun deleteTree(path: String, prefix: String)

    /** The folder and every description below it, for remapping after a move. */
    @Query("SELECT * FROM folder_descriptions WHERE path = :path OR path LIKE :prefix ESCAPE '\\'")
    suspend fun tree(path: String, prefix: String): List<FolderDescription>
}
