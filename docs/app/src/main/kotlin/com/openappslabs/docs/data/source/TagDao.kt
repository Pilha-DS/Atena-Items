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
import com.openappslabs.docs.data.model.DocStatus
import com.openappslabs.docs.data.model.DocTag
import com.openappslabs.docs.data.model.DocTagRow
import com.openappslabs.docs.data.model.Tag
import com.openappslabs.docs.data.model.TagCount
import com.openappslabs.docs.data.model.TagDocRow
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY path ASC")
    fun observeAll(): Flow<List<Tag>>

    @Query("SELECT * FROM tags ORDER BY path ASC")
    suspend fun all(): List<Tag>

    @Query("SELECT * FROM tags WHERE path = :path LIMIT 1")
    suspend fun findByPath(path: String): Tag?

    /**
     * The tag and every tag below it: `academico` also brings
     * `academico/faculdade`.
     *
     * The same `LIKE` shape the document queries use, with the wildcards
     * escaped, so a tag literally named `50%` matches only itself.
     */
    @Query("SELECT * FROM tags WHERE path = :path OR path LIKE :prefix ESCAPE '\\'")
    suspend fun subtree(path: String, prefix: String): List<Tag>

    @Query("SELECT COUNT(*) FROM doc_tags WHERE tagId IN (:ids)")
    suspend fun countAssignments(ids: List<Long>): Int

    @Query("DELETE FROM doc_tags WHERE tagId IN (:ids)")
    suspend fun unassignTags(ids: List<Long>)

    @Query("DELETE FROM tags WHERE id IN (:ids)")
    suspend fun deleteTags(ids: List<Long>)

    /**
     * Writes the sentence the user typed about a tag.
     *
     * An update and not an insert: the tag has to exist for its description to
     * mean anything, and the caller checks that first. Empty text is stored as
     * empty, which is how "no description" is spelled.
     */
    @Query("UPDATE tags SET description = :description WHERE path = :path")
    suspend fun setDescription(path: String, description: String)

    /** Renames one tag row; callers remap the whole subtree in depth order. */
    @Query("UPDATE tags SET path = :newPath, name = :newName WHERE id = :id")
    suspend fun updatePath(id: Long, newPath: String, newName: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: Tag): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun assign(link: DocTag)

    @Query("DELETE FROM doc_tags WHERE docId = :docId AND tagId = :tagId")
    suspend fun unassign(docId: Long, tagId: Long)

    @Query("SELECT tagId FROM doc_tags WHERE docId = :docId")
    suspend fun tagIdsFor(docId: Long): List<Long>

    /**
     * Counts only what the list actually shows: a document whose file is gone
     * still keeps its tags, but the sidebar must not claim it.
     */
    @Query(
        """
        SELECT dt.tagId AS tagId, COUNT(*) AS count
        FROM doc_tags dt
        JOIN documents d ON d.id = dt.docId
        WHERE d.status = :status
        GROUP BY dt.tagId
        """
    )
    fun observeCounts(status: DocStatus): Flow<List<TagCount>>

    /** Every applied tag, so a row can show its own and the picker its state. */
    @Query(
        """
        SELECT dt.docId AS docId, t.path AS path
        FROM doc_tags dt
        JOIN tags t ON t.id = dt.tagId
        JOIN documents d ON d.id = dt.docId
        WHERE d.status = :status
        ORDER BY t.path ASC
        """
    )
    fun observeAssignments(status: DocStatus): Flow<List<DocTagRow>>

    /**
     * One document per row for every tag, so the prompt can show what a tag is
     * *used for* instead of only its name.
     *
     * Ordered by tag then file name, so the first rows of a tag are a stable
     * example and the caller can take the first few.
     */
    @Query(
        """
        SELECT t.path AS path, d.name AS name
        FROM doc_tags dt
        JOIN tags t ON t.id = dt.tagId
        JOIN documents d ON d.id = dt.docId
        WHERE d.status = :status
        ORDER BY t.path ASC, d.name COLLATE NOCASE ASC
        """
    )
    suspend fun taggedNames(status: DocStatus): List<TagDocRow>
}
