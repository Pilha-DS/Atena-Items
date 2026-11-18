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
import com.openappslabs.docs.data.model.TagAlias
import com.openappslabs.docs.data.model.TagAliasRow
import kotlinx.coroutines.flow.Flow

@Dao
interface TagAliasDao {

    @Query(
        """
        SELECT t.path AS tagPath, a.alias AS alias
        FROM tag_aliases a
        JOIN tags t ON t.id = a.tagId
        ORDER BY t.path ASC, a.alias ASC
        """
    )
    fun observeRows(): Flow<List<TagAliasRow>>

    @Query(
        """
        SELECT a.alias FROM tag_aliases a
        JOIN tags t ON t.id = a.tagId
        WHERE t.path = :tagPath
        ORDER BY a.alias ASC
        """
    )
    suspend fun aliasesOf(tagPath: String): List<String>

    @Query(
        """
        SELECT a.* FROM tag_aliases a
        JOIN tags t ON t.id = a.tagId
        WHERE t.path = :tagPath
        ORDER BY a.alias ASC
        """
    )
    fun observeOf(tagPath: String): Flow<List<TagAlias>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(alias: TagAlias): Long

    @Query("DELETE FROM tag_aliases WHERE tagId = :tagId AND alias = :alias")
    suspend fun delete(tagId: Long, alias: String)

    @Query("DELETE FROM tag_aliases WHERE tagId IN (:ids)")
    suspend fun deleteByTagIds(ids: List<Long>)
}
