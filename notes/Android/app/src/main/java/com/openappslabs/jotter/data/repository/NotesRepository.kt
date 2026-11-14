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

import com.openappslabs.jotter.data.model.BackupData
import com.openappslabs.jotter.data.model.Note
import kotlinx.coroutines.flow.Flow

interface NotesRepository {
    fun getAllNotes(): Flow<List<Note>>
    fun getTrashedNotes(): Flow<List<Note>>
    fun getCategories(): Flow<List<String>>
    suspend fun getNoteById(noteId: Int): Note?
    suspend fun addNote(note: Note): Long
    suspend fun updateNote(note: Note)

    suspend fun trashNote(note: Note)
    suspend fun restoreNote(note: Note)
    suspend fun deleteNote(note: Note)
    suspend fun emptyTrash()

    suspend fun getBackupData(): BackupData
    suspend fun restoreBackupData(backupData: BackupData)

    /** Per-note open-mode override; `null` follows the global default. */
    suspend fun updateOpenInEditMode(noteId: Int, openInEditMode: Boolean?)

    // Config-only updates: they intentionally keep `updatedTime` untouched so
    // the note list does not reorder itself.
    suspend fun setPinned(noteId: Int, isPinned: Boolean)
    suspend fun setLocked(noteId: Int, isLocked: Boolean)
    suspend fun setNoteCategory(noteId: Int, category: String)

    /** Renderer used when reading the note; the text itself is untouched. */
    suspend fun setFormat(noteId: Int, format: String)

    /**
     * Writes the final state of a per-note encryption change: when encrypting,
     * the plain text is blanked and only the ciphertext is kept; when removing
     * it, the plain text is stored and the payload cleared.
     */
    suspend fun setEncryption(
        noteId: Int,
        isEncrypted: Boolean,
        salt: String?,
        payload: String?,
        title: String,
        content: String
    )

    suspend fun clearAllDatabaseData()
    suspend fun unlockAllNotes()

    /**
     * The single persistent "general note" (scratchpad), created on first use.
     * It is a regular note row flagged as scratchpad, with the fixed
     * `Scratchpad.UUID`, so it is the same note on every synced device.
     */
    suspend fun getOrCreateScratchpad(): Note

    /**
     * The same row, observed. The open screen follows it so text pulled from
     * the hub shows up without closing and reopening the note.
     */
    fun observeScratchpad(): Flow<Note?>

    /** Saves the scratchpad text. It has no title, so only `content` changes. */
    suspend fun saveScratchpad(noteId: Int, content: String)
}
