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
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.openappslabs.jotter.data.model.Note
import kotlinx.coroutines.flow.Flow
@Dao
interface NoteDao {

    @Query("SELECT * FROM notes WHERE isArchived = 0 AND isTrashed = 0 AND isScratchpad = 0 ORDER BY isPinned DESC, updatedTime DESC")
    fun getAllNotes(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :noteId")
    suspend fun getNoteById(noteId: Int): Note?

    /**
     * Live view of a single row. The note editor only needs to follow the
     * category of the note it has open; loading the whole list for that made
     * every note read and decrypt the entire database.
     */
    @Query("SELECT * FROM notes WHERE id = :noteId")
    fun observeNote(noteId: Int): Flow<Note?>

    /**
     * Count for the plasmoid counter. Touching `notes` invalidates it, so it
     * stays correct, but it never reads a note body.
     */
    @Query("SELECT COUNT(*) FROM notes WHERE isArchived = 0 AND isTrashed = 0 AND isScratchpad = 0")
    fun observeNoteCount(): Flow<Int>

    /** Title shown next to the counter; same order as [getAllNotes]. */
    @Query(
        "SELECT title FROM notes WHERE isArchived = 0 AND isTrashed = 0 AND isScratchpad = 0 " +
            "ORDER BY isPinned DESC, updatedTime DESC LIMIT 1"
    )
    fun observeLatestTitle(): Flow<String?>

    @Query("UPDATE notes SET isArchived = :isArchived, isTrashed = :isTrashed, updatedTime = :updatedTime, needsSync = 1 WHERE id = :noteId")
    suspend fun updateNoteStatus(noteId: Int, isArchived: Boolean, isTrashed: Boolean, updatedTime: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: Note): Long

    @Update
    suspend fun update(note: Note)

    @Query("UPDATE notes SET category = :newName, updatedTime = :updatedTime, needsSync = 1 WHERE category = :oldName")
    suspend fun updateNoteCategories(oldName: String, newName: String, updatedTime: Long)

    /**
     * Stores the per-note open-mode override. `NULL` means "follow the global
     * default", so the note stays in sync if the user changes that setting.
     */
    @Query("UPDATE notes SET openInEditMode = :openInEditMode, needsSync = 1 WHERE id = :noteId")
    suspend fun updateOpenInEditMode(noteId: Int, openInEditMode: Boolean?)

    // Settings changed from the note list must not bump `updatedTime`, or the
    // list would reorder itself under the user's finger.
    @Query("UPDATE notes SET isPinned = :isPinned, needsSync = 1 WHERE id = :noteId")
    suspend fun updatePinned(noteId: Int, isPinned: Boolean)

    @Query("UPDATE notes SET isLocked = :isLocked, needsSync = 1 WHERE id = :noteId")
    suspend fun updateLocked(noteId: Int, isLocked: Boolean)

    @Query("UPDATE notes SET category = :category, needsSync = 1 WHERE id = :noteId")
    suspend fun updateNoteCategory(noteId: Int, category: String)

    /**
     * Stores the renderer used when the note is read. The content is plain text
     * and is never rewritten by this, so switching back and forth is lossless.
     */
    @Query("UPDATE notes SET format = :format, needsSync = 1 WHERE id = :noteId")
    suspend fun updateFormat(noteId: Int, format: String)

    /**
     * Turns per-note encryption on or off. The caller passes the final values:
     * blank `title`/`content` plus a ciphertext when encrypting, and the plain
     * text with NULL payload when removing it. Keeps `updatedTime` untouched so
     * the list does not jump when this is done from the note list.
     */
    @Query(
        "UPDATE notes SET isEncrypted = :isEncrypted, encryptionSalt = :salt, " +
            "encryptedContent = :payload, title = :title, content = :content, " +
            "isLocked = 0, needsSync = 1 WHERE id = :noteId"
    )
    suspend fun updateEncryption(
        noteId: Int,
        isEncrypted: Boolean,
        salt: String?,
        payload: String?,
        title: String,
        content: String
    )

    @Delete
    suspend fun delete(note: Note)

    /** Announces a hard deletion to the server on the next sync. */
    @Query("INSERT OR REPLACE INTO deleted_notes(uuid, deletedAt) VALUES(:uuid, :deletedAt)")
    suspend fun addTombstone(uuid: String, deletedAt: Long)

    @Query("SELECT * FROM notes WHERE isArchived = 1 AND isTrashed = 0 AND isScratchpad = 0 ORDER BY updatedTime DESC")
    fun getArchivedNotes(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE isTrashed = 1 AND isScratchpad = 0 ORDER BY updatedTime DESC")
    fun getTrashedNotes(): Flow<List<Note>>

    // -----------------------------------------------------------------------
    // The single "general note" (scratchpad). It is an ordinary note row with
    // `isScratchpad = 1`, so there is only one note type in the app.
    // -----------------------------------------------------------------------

    @Query("SELECT * FROM notes WHERE isScratchpad = 1 ORDER BY id ASC LIMIT 1")
    suspend fun getScratchpad(): Note?

    /** Only touches the text: it has no title, category or timestamp to show. */
    @Query("UPDATE notes SET content = :content, updatedTime = :updatedTime WHERE id = :noteId")
    suspend fun updateScratchpadContent(noteId: Int, content: String, updatedTime: Long)

    /**
     * Copies the uuid of every trashed note into the tombstone table before
     * deleting the rows, so the deletion reaches the server (and the other
     * devices) on the next sync.
     */
    @Query("INSERT INTO deleted_notes(uuid, deletedAt) SELECT uuid, :deletedAt FROM notes WHERE isTrashed = 1 AND uuid != ''")
    suspend fun tombstoneTrashed(deletedAt: Long)

    @Query("DELETE FROM notes WHERE isTrashed = 1")
    suspend fun emptyTrash()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(notes: List<Note>)

    @Query("DELETE FROM notes")
    suspend fun deleteAllNotes()

    @Query("SELECT * FROM notes")
    suspend fun getAllNotesSync(): List<Note>

    @Query("UPDATE notes SET isLocked = 0, needsSync = 1 WHERE isLocked = 1")
    suspend fun unlockAllNotes()
}