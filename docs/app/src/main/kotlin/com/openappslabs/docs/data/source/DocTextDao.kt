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
import com.openappslabs.docs.data.model.Doc
import com.openappslabs.docs.data.model.DocText
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes the text layer of the library.
 *
 * Everything here is optional data: a missing row is "not read yet" and a
 * deleted row only costs a re-read.
 */
@Dao
interface DocTextDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: DocText)

    @Query("SELECT * FROM doc_text WHERE docId = :docId")
    suspend fun get(docId: Long): DocText?

    @Query("SELECT COUNT(*) FROM doc_text WHERE charCount > 0")
    fun observeWithText(): Flow<Int>

    /** Every extracted text: the universal search ranks content last. */
    @Query("SELECT * FROM doc_text")
    fun observeAll(): Flow<List<DocText>>

    @Query("SELECT COUNT(*) FROM doc_text")
    suspend fun count(): Int

    /** Documents still waiting for a read, newest first: the useful ones come first. */
    @Query(
        """
        SELECT d.* FROM documents d
        LEFT JOIN doc_text t ON t.docId = d.id
        WHERE d.status = 'PRESENT' AND t.docId IS NULL
        ORDER BY d.modifiedTime DESC
        LIMIT :limit
        """
    )
    suspend fun pending(limit: Int): List<Doc>

    /** Forgets the text of documents that changed on disk. */
    @Query("DELETE FROM doc_text WHERE docId IN (:ids)")
    suspend fun forget(ids: List<Long>)

    @Query("DELETE FROM doc_text")
    suspend fun forgetAll()
}
