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

import androidx.room.withTransaction
import com.openappslabs.jotter.data.crypto.NoteCrypto
import com.openappslabs.jotter.data.model.BackupData
import com.openappslabs.jotter.data.model.Note
import com.openappslabs.jotter.data.model.Scratchpad
import com.openappslabs.jotter.data.source.CategoryDao
import com.openappslabs.jotter.data.source.JotterDatabase
import com.openappslabs.jotter.data.source.NoteDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import java.util.UUID

class NotesRepositoryImpl @Inject constructor(
    private val noteDao: NoteDao,
    private val categoryDao: CategoryDao,
    private val database: JotterDatabase
) : NotesRepository {

    // Reads go through `decryptIfUnlocked`: encrypted notes only expose their
    // plain text when this run of the app already unlocked them. Otherwise the
    // title/content come back blank and the UI shows a locked placeholder.
    override fun getAllNotes(): Flow<List<Note>> =
        noteDao.getAllNotes().map { notes -> notes.map(NoteCrypto::decryptIfUnlocked) }

    override fun getTrashedNotes(): Flow<List<Note>> =
        noteDao.getTrashedNotes().map { notes -> notes.map(NoteCrypto::decryptIfUnlocked) }

    override suspend fun getNoteById(noteId: Int): Note? =
        noteDao.getNoteById(noteId)?.let(NoteCrypto::decryptIfUnlocked)

    override suspend fun addNote(note: Note): Long {
        return noteDao.insert(
            note.copy(
                uuid = note.uuid.ifBlank { UUID.randomUUID().toString() },
                updatedTime = System.currentTimeMillis(),
                needsSync = true
            )
        )
    }

    override suspend fun updateNote(note: Note) {
        // A caller that never learned the uuid (an older screen, an archived
        // row) must not blank the column: the note would lose its identity.
        val uuid = note.uuid.ifBlank {
            noteDao.getNoteById(note.id)?.uuid ?: UUID.randomUUID().toString()
        }
        noteDao.update(
            note.copy(uuid = uuid, updatedTime = System.currentTimeMillis(), needsSync = true)
        )
    }

    override suspend fun trashNote(note: Note) {
        noteDao.updateNoteStatus(
            noteId = note.id,
            isArchived = false,
            isTrashed = true,
            updatedTime = System.currentTimeMillis()
        )
    }

    override suspend fun restoreNote(note: Note) {
        noteDao.updateNoteStatus(
            noteId = note.id,
            isArchived = false,
            isTrashed = false,
            updatedTime = System.currentTimeMillis()
        )
    }

    override suspend fun deleteNote(note: Note) {
        database.withTransaction {
            if (note.uuid.isNotBlank()) {
                noteDao.addTombstone(note.uuid, System.currentTimeMillis())
            }
            noteDao.delete(note)
        }
    }

    override suspend fun emptyTrash() {
        database.withTransaction {
            noteDao.tombstoneTrashed(System.currentTimeMillis())
            noteDao.emptyTrash()
        }
    }

    override fun getCategories(): Flow<List<String>> = categoryDao.getAllCategoryNames()

    override suspend fun getBackupData(): BackupData {
        val notes = noteDao.getAllNotesSync()
        val categories = categoryDao.getAllCategoriesSync()
        return BackupData(notes, categories)
    }

    override suspend fun restoreBackupData(backupData: BackupData) {
        database.withTransaction {
            noteDao.deleteAllNotes()
            categoryDao.deleteAllCategories()
            database.syncDao().clearAllDeletions()
            // A backup can predate the sync feature, so every restored note
            // gets an identity and is queued for upload.
            val restored = backupData.notes
                .distinctBy { it.uuid.ifBlank { it.id.toString() } }
                .map {
                    it.copy(
                        isArchived = false,
                        uuid = it.uuid.ifBlank { UUID.randomUUID().toString() },
                        needsSync = true
                    )
                }
            noteDao.insertAll(restored)
            categoryDao.insertAll(backupData.categories)
        }
    }

    override suspend fun updateOpenInEditMode(noteId: Int, openInEditMode: Boolean?) {
        noteDao.updateOpenInEditMode(noteId, openInEditMode)
    }

    override suspend fun setPinned(noteId: Int, isPinned: Boolean) {
        noteDao.updatePinned(noteId, isPinned)
    }

    override suspend fun setLocked(noteId: Int, isLocked: Boolean) {
        noteDao.updateLocked(noteId, isLocked)
    }

    override suspend fun setNoteCategory(noteId: Int, category: String) {
        noteDao.updateNoteCategory(noteId, category)
    }

    override suspend fun setFormat(noteId: Int, format: String) {
        noteDao.updateFormat(noteId, format)
    }

    override suspend fun setEncryption(
        noteId: Int,
        isEncrypted: Boolean,
        salt: String?,
        payload: String?,
        title: String,
        content: String
    ) {
        noteDao.updateEncryption(
            noteId = noteId,
            isEncrypted = isEncrypted,
            salt = salt,
            payload = payload,
            title = title,
            content = content
        )
    }

    override suspend fun clearAllDatabaseData() {
        database.withTransaction {
            noteDao.deleteAllNotes()
            categoryDao.deleteAllCategories()
            // No tombstones on purpose: this is a local wipe, not a deletion to
            // replicate. With sync on, the next sync brings the server copy back.
            database.syncDao().clearAllDeletions()
        }
    }

    override suspend fun unlockAllNotes() {
        noteDao.unlockAllNotes()
    }

    override suspend fun getOrCreateScratchpad(): Note {
        noteDao.getScratchpad()?.let { return it }
        // Created under the fixed scratchpad uuid: the desktop app resolves the
        // same row from the hub instead of keeping a private copy.
        //
        // A brand-new row is *older than everything* and not dirty: if the hub
        // already holds a general note written on another device, that one has
        // to win the first sync. Stamping "now" here would let an empty page
        // overwrite real text by LWW.
        val draft = Note(isScratchpad = true, uuid = Scratchpad.UUID, createdTime = 0L, updatedTime = 0L)
        val id = noteDao.insert(draft).toInt()
        return noteDao.getNoteById(id) ?: draft.copy(id = id)
    }

    override fun observeScratchpad(): Flow<Note?> = noteDao.observeScratchpad()

    override suspend fun saveScratchpad(noteId: Int, content: String) {
        noteDao.updateScratchpadContent(
            noteId = noteId,
            content = content,
            updatedTime = System.currentTimeMillis()
        )
    }
}
