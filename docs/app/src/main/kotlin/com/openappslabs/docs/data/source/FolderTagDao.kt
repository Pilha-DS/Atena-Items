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
import com.openappslabs.docs.data.model.FolderTag
import com.openappslabs.docs.data.model.FolderTagRow
import kotlinx.coroutines.flow.Flow

/**
 * Folder → tag links used for inheritance.
 *
 * Nothing here is written into the library folder: like descriptions, the link
 * lives only in the index database.
 */
@Dao
interface FolderTagDao {

    @Query(
        """
        SELECT ft.folderPath AS folderPath, t.path AS tagPath, t.id AS tagId
        FROM folder_tags ft
        JOIN tags t ON t.id = ft.tagId
        ORDER BY ft.folderPath ASC, t.path ASC
        """
    )
    fun observeRows(): Flow<List<FolderTagRow>>

    @Query(
        """
        SELECT ft.folderPath AS folderPath, t.path AS tagPath, t.id AS tagId
        FROM folder_tags ft
        JOIN tags t ON t.id = ft.tagId
        WHERE ft.folderPath = :folderPath
        ORDER BY t.path ASC
        """
    )
    suspend fun forFolder(folderPath: String): List<FolderTagRow>

    @Query(
        """
        SELECT ft.folderPath AS folderPath, t.path AS tagPath, t.id AS tagId
        FROM folder_tags ft
        JOIN tags t ON t.id = ft.tagId
        ORDER BY ft.folderPath ASC, t.path ASC
        """
    )
    suspend fun allRows(): List<FolderTagRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(link: FolderTag)

    @Query("DELETE FROM folder_tags WHERE folderPath = :folderPath")
    suspend fun clearFolder(folderPath: String)

    /**
     * Forgets [path] and everything below it when the folder is deleted from
     * the window — same reason as folder descriptions: a revived name must not
     * inherit yesterday's tags.
     */
    @Query("DELETE FROM folder_tags WHERE folderPath = :path OR folderPath LIKE :prefix ESCAPE '\\'")
    suspend fun deleteTree(path: String, prefix: String)

    /** Drops links whose tag was deleted (the tag tree removal). */
    @Query("DELETE FROM folder_tags WHERE tagId IN (:ids)")
    suspend fun deleteByTagIds(ids: List<Long>)

    /** Links on [path] and every folder below it, for remapping after a move. */
    @Query(
        """
        SELECT folderPath, tagId FROM folder_tags
        WHERE folderPath = :path OR folderPath LIKE :prefix ESCAPE '\'
        """
    )
    suspend fun tree(path: String, prefix: String): List<FolderTag>
}
