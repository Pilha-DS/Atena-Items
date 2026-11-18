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
import androidx.room.Update
import com.openappslabs.docs.data.model.Doc
import com.openappslabs.docs.data.model.DocIdPath
import com.openappslabs.docs.data.model.DocStatus
import kotlinx.coroutines.flow.Flow

/**
 * Reads the index, filtered.
 *
 * Every filter is optional and expressed as a bound parameter, so one pair of
 * queries serves the whole screen: text search, sort, tag, favorites, recency
 * and folder. Adding a view is adding a parameter, not a new query that can
 * drift from this one.
 *
 * Convention: `0`/`""` means "no filter" for the numeric/prefix knobs, and
 * `NULL` for the exact-value ones. `tag` filters the tag *and its descendants*
 * — both from `doc_tags` and from `folder_tags` (a document under a folder that
 * inherits the tag matches); `folderPrefix` matches everything under a folder;
 * `recentSince` is an epoch millisecond cut-off.
 */
@Dao
interface DocDao {

    @Query(
        """
        SELECT * FROM documents
        WHERE status = :status
          AND (
            name LIKE :pattern ESCAPE '\'
            OR EXISTS (
              SELECT 1 FROM doc_text tx
              WHERE tx.docId = documents.id AND tx.text LIKE :pattern ESCAPE '\'
            )
          )
          AND (
            :tag IS NULL OR EXISTS (
              SELECT 1 FROM doc_tags dt
              JOIN tags t ON t.id = dt.tagId
              WHERE dt.docId = documents.id
                AND (t.path = :tag OR t.path LIKE :tagPrefix ESCAPE '\')
            ) OR EXISTS (
              SELECT 1 FROM folder_tags ft
              JOIN tags t ON t.id = ft.tagId
              WHERE (t.path = :tag OR t.path LIKE :tagPrefix ESCAPE '\')
                AND documents.relPath LIKE (ft.folderPath || '/%')
            )
          )
          AND (:favoriteOnly = 0 OR favorite = 1)
          AND (:recentSince = 0 OR modifiedTime >= :recentSince)
          AND (:folder IS NULL OR relPath LIKE :folderPrefix ESCAPE '\')
        ORDER BY name COLLATE NOCASE ASC
        """
    )
    fun observeByName(
        status: DocStatus,
        pattern: String,
        tag: String?,
        tagPrefix: String,
        favoriteOnly: Int,
        recentSince: Long,
        folder: String?,
        folderPrefix: String
    ): Flow<List<Doc>>

    @Query(
        """
        SELECT * FROM documents
        WHERE status = :status
          AND (
            name LIKE :pattern ESCAPE '\'
            OR EXISTS (
              SELECT 1 FROM doc_text tx
              WHERE tx.docId = documents.id AND tx.text LIKE :pattern ESCAPE '\'
            )
          )
          AND (
            :tag IS NULL OR EXISTS (
              SELECT 1 FROM doc_tags dt
              JOIN tags t ON t.id = dt.tagId
              WHERE dt.docId = documents.id
                AND (t.path = :tag OR t.path LIKE :tagPrefix ESCAPE '\')
            ) OR EXISTS (
              SELECT 1 FROM folder_tags ft
              JOIN tags t ON t.id = ft.tagId
              WHERE (t.path = :tag OR t.path LIKE :tagPrefix ESCAPE '\')
                AND documents.relPath LIKE (ft.folderPath || '/%')
            )
          )
          AND (:favoriteOnly = 0 OR favorite = 1)
          AND (:recentSince = 0 OR modifiedTime >= :recentSince)
          AND (:folder IS NULL OR relPath LIKE :folderPrefix ESCAPE '\')
        ORDER BY modifiedTime DESC, name COLLATE NOCASE ASC
        """
    )
    fun observeByModified(
        status: DocStatus,
        pattern: String,
        tag: String?,
        tagPrefix: String,
        favoriteOnly: Int,
        recentSince: Long,
        folder: String?,
        folderPrefix: String
    ): Flow<List<Doc>>

    @Query("SELECT COUNT(*) FROM documents WHERE status = :status")
    fun observeCount(status: DocStatus): Flow<Int>

    @Query("SELECT COUNT(*) FROM documents WHERE status = :status AND favorite = 1")
    fun observeFavoriteCount(status: DocStatus): Flow<Int>

    /** Relative paths only: the folder tree is derived from them. */
    @Query("SELECT relPath FROM documents WHERE status = :status")
    fun observePaths(status: DocStatus): Flow<List<String>>

    /**
     * Id and path of every present document: inheritance counts need the folder
     * of each row, and assignments are keyed by id.
     */
    @Query("SELECT id AS id, relPath AS relPath FROM documents WHERE status = :status")
    fun observeIdPaths(status: DocStatus): Flow<List<DocIdPath>>

    @Query("SELECT * FROM documents")
    suspend fun all(): List<Doc>

    /** One row by its relative path: the check before a move. */
    @Query("SELECT * FROM documents WHERE relPath = :relPath")
    suspend fun findByPath(relPath: String): Doc?

    /** One row by id, for an action that outlived the list it was opened from. */
    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun findById(id: Long): Doc?

    /** Same list as [observePaths], for a one-shot read (folder suggestions). */
    @Query("SELECT relPath FROM documents WHERE status = :status")
    suspend fun paths(status: DocStatus): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(doc: Doc): Long

    @Update
    suspend fun update(doc: Doc)

    @Query("UPDATE documents SET status = :status WHERE id IN (:ids)")
    suspend fun setStatus(ids: List<Long>, status: DocStatus)

    /**
     * Marks everything below [pattern] as [status] in one statement.
     *
     * Used by the folder deletion: the files are gone from the disk, and the
     * list must stop showing them even when a scan is already running and
     * cannot be restarted. The row survives as `MISSING`, with its tags.
     */
    @Query("UPDATE documents SET status = :status WHERE relPath LIKE :pattern ESCAPE '\\'")
    suspend fun setStatusUnder(pattern: String, status: DocStatus)

    @Query("UPDATE documents SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    /**
     * Counts one open from the window for the fuzzy frecency.
     *
     * [now] is epoch millis; the scan never writes these columns.
     */
    @Query(
        """
        UPDATE documents
        SET visitCount = visitCount + 1, lastVisitedAt = :now
        WHERE id = :id
        """
    )
    suspend fun recordVisit(id: Long, now: Long)

    /**
     * Every document whose path is [folder] itself as a file prefix: files
     * directly in the folder and in every subfolder (`folder/%`).
     */
    @Query("SELECT * FROM documents WHERE relPath LIKE :prefix ESCAPE '\\'")
    suspend fun underPrefix(prefix: String): List<Doc>
}
