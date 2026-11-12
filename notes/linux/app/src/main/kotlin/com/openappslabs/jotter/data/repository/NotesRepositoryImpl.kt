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

import com.openappslabs.jotter.data.crypto.NoteCrypto
import com.openappslabs.jotter.data.source.inTransaction
import com.openappslabs.jotter.data.model.BackupData
import com.openappslabs.jotter.data.model.Note
import com.openappslabs.jotter.data.model.NoteStatus
import com.openappslabs.jotter.data.source.CategoryDao
import com.openappslabs.jotter.data.source.JotterDatabase
import com.openappslabs.jotter.data.source.NoteDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import java.util.UUID

class NotesRepositoryImpl(
    private val noteDao: NoteDao,
    private val categoryDao: CategoryDao,
    private val database: JotterDatabase,
    private val appScope: CoroutineScope
) : NotesRepository {

    /**
     * One shared read of the list, mapped off the UI thread.
     *
     * The list is asked for from several places (home, trash badge, data
     * management). Without sharing, each one re-runs the query and re-maps
     * every row on every write, and the mapping even ran on the caller's
     * thread — the AWT event thread when the screen collected it. The short
     * subscription timeout keeps the last list for a moment, so returning from
     * a note shows the notes immediately instead of reading the table again.
     */
    private val allNotes: SharedFlow<List<Note>> = noteDao.getAllNotes()
        .map { notes -> notes.map(NoteCrypto::decryptIfUnlocked) }
        .flowOn(Dispatchers.Default)
        .shareIn(appScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)

    override fun getAllNotes(): Flow<List<Note>> = allNotes

    override fun getTrashedNotes(): Flow<List<Note>> =
        noteDao.getTrashedNotes()
            .map { notes -> notes.map(NoteCrypto::decryptIfUnlocked) }
            .flowOn(Dispatchers.Default)

    override fun observeStatus(): Flow<NoteStatus> =
        combine(noteDao.observeNoteCount(), noteDao.observeLatestTitle()) { count, title ->
            NoteStatus(count = count, latestTitle = title)
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    override fun observeNote(noteId: Int): Flow<Note?> =
        noteDao.observeNote(noteId)
            .map { note -> note?.let(NoteCrypto::decryptIfUnlocked) }
            .flowOn(Dispatchers.Default)

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
        database.inTransaction {
            if (note.uuid.isNotBlank()) {
                noteDao.addTombstone(note.uuid, System.currentTimeMillis())
            }
            noteDao.delete(note)
        }
    }

    override suspend fun emptyTrash() {
        database.inTransaction {
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
        database.inTransaction {
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
        database.inTransaction {
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
        // The general note is device-local and never synced, but it still gets
        // an identity so every row has a non-blank uuid.
        val draft = Note(isScratchpad = true, uuid = UUID.randomUUID().toString())
        val id = noteDao.insert(draft.copy(updatedTime = System.currentTimeMillis())).toInt()
        return noteDao.getNoteById(id) ?: draft.copy(id = id)
    }

    override suspend fun saveScratchpad(noteId: Int, content: String) {
        noteDao.updateScratchpadContent(
            noteId = noteId,
            content = content,
            updatedTime = System.currentTimeMillis()
        )
    }

    private companion object {
        /** How long the shared list survives with no screen watching it. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
