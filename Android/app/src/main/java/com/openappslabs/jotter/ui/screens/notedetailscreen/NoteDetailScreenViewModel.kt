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

package com.openappslabs.jotter.ui.screens.notedetailscreen

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.openappslabs.jotter.data.crypto.NoteCrypto
import com.openappslabs.jotter.data.model.Note
import com.openappslabs.jotter.data.model.NoteFormat
import com.openappslabs.jotter.data.repository.CategoryRepository
import com.openappslabs.jotter.data.repository.NotesRepository
import com.openappslabs.jotter.data.repository.UserPreferences
import com.openappslabs.jotter.data.repository.UserPreferencesRepository
import com.openappslabs.jotter.navigation.AppRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.crypto.SecretKey
import javax.inject.Inject

@HiltViewModel
class NoteDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val notesRepository: NotesRepository,
    private val categoryRepository: CategoryRepository,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val route = savedStateHandle.toRoute<AppRoutes.NoteDetail>()
    private val noteId = route.noteId
    private val passedCategory = route.category

    val userPreferences: StateFlow<UserPreferences> = userPreferencesRepository.userPreferencesFlow
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, UserPreferences())

    @Immutable
    data class UiState(
        val id: Int? = null,
        val title: String = "",
        val content: String = "",
        val category: String = "",
        val isPinned: Boolean = false,
        val isLocked: Boolean = false,
        val isTrashed: Boolean = false,
        val createdTime: Long = System.currentTimeMillis(),
        val lastEdited: Long = System.currentTimeMillis(),
        val isNotePersisted: Boolean = false,
        val isLoading: Boolean = true,
        val isModified: Boolean = false,
        /** `null` = follow the global "default open mode" preference. */
        val openInEditMode: Boolean? = null,
        /** The note has its own password (independent from the app lock). */
        val isEncrypted: Boolean = false,
        /** `false` only for an encrypted note whose password was not given yet. */
        val isUnlocked: Boolean = true,
        val encryptionSalt: String? = null,
        val encryptedContent: String? = null,
        /** Renderer used when this note is read; the text is always plain. */
        val format: NoteFormat = NoteFormat.TEXT
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * Key of a note that is still being created: it has no id yet, so it
     * cannot live in the [NoteCrypto] session cache until the first insert.
     */
    private var pendingKey: SecretKey? = null

    val availableCategories: StateFlow<List<String>> = categoryRepository.getAllCategories()
        .map { categoryList -> categoryList.map { it.name } }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    init {
        if (noteId != -1) {
            loadNote(noteId)
        } else {
            val initialState = UiState(
                category = passedCategory ?: "",
                isNotePersisted = false,
                isLoading = false
            )
            _uiState.update { initialState }
        }
        observeCategoryCleanup()
        observeNoteUpdates()
    }

    private fun observeCategoryCleanup() {
        viewModelScope.launch {
            availableCategories.drop(1).collectLatest { categories ->
                val currentCategory = uiState.value.category
                if (currentCategory.isNotBlank() && !categories.contains(currentCategory)) {
                    _uiState.update { it.copy(category = "") }
                }
            }
        }
    }

    private fun observeNoteUpdates() {
        if (noteId != -1) {
            viewModelScope.launch {
                notesRepository.getAllNotes().collectLatest { notes ->
                    val updatedNote = notes.find { it.id == noteId }
                    if (updatedNote != null && updatedNote.category != uiState.value.category) {
                        _uiState.update { it.copy(category = updatedNote.category) }
                    }
                }
            }
        }
    }

    private fun loadNote(id: Int) {
        viewModelScope.launch {
            // The repository already decrypts when this run unlocked the note.
            val note = notesRepository.getNoteById(id)
            if (note != null) {
                val unlocked = !note.isEncrypted || NoteCrypto.isUnlocked(note.id)
                val newState = UiState(
                    id = note.id,
                    title = if (unlocked) note.title else "",
                    content = if (unlocked) note.content else "",
                    category = note.category,
                    isPinned = note.isPinned,
                    isLocked = note.isLocked,
                    isTrashed = note.isTrashed,
                    createdTime = note.createdTime,
                    lastEdited = note.updatedTime,
                    isNotePersisted = true,
                    isLoading = false,
                    isModified = false,
                    openInEditMode = note.openInEditMode,
                    isEncrypted = note.isEncrypted,
                    isUnlocked = unlocked,
                    encryptionSalt = note.encryptionSalt,
                    encryptedContent = note.encryptedContent,
                    format = NoteFormat.fromKey(note.format)
                )
                _uiState.update { newState }
            } else {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun updateTitle(newTitle: String) {
        _uiState.update { it.copy(title = newTitle, isModified = true) }
    }

    fun updateContent(newContent: String) {
        _uiState.update { it.copy(content = newContent, isModified = true) }
    }

    fun updateCategory(newCategory: String) {
        _uiState.update { it.copy(category = newCategory, isModified = true) }
    }

    fun togglePin() {
        val newValue = !_uiState.value.isPinned
        _uiState.update { it.copy(isPinned = newValue) }
        val id = _uiState.value.id ?: return
        viewModelScope.launch { notesRepository.setPinned(id, newValue) }
    }

    fun toggleLock() {
        // A note with its own password is protected by it, not by the app lock.
        if (_uiState.value.isEncrypted) return
        val newValue = !_uiState.value.isLocked
        _uiState.update { it.copy(isLocked = newValue) }
        val id = _uiState.value.id ?: return
        viewModelScope.launch { notesRepository.setLocked(id, newValue) }
    }

    /**
     * Per-note override of the open mode. `null` keeps following the global
     * preference, so changing that setting still affects this note.
     */
    fun setOpenInEditMode(openInEdit: Boolean?) {
        if (_uiState.value.openInEditMode == openInEdit) return
        _uiState.update { it.copy(openInEditMode = openInEdit) }
        val id = _uiState.value.id ?: return
        viewModelScope.launch { notesRepository.updateOpenInEditMode(id, openInEdit) }
    }

    /**
     * Changes how the note is displayed when read. Neither the text nor the
     * note's timestamp changes, so the list order stays put.
     */
    fun setFormat(format: NoteFormat) {
        if (_uiState.value.format == format) return
        _uiState.update { it.copy(format = format) }
        val id = _uiState.value.id ?: return
        viewModelScope.launch { notesRepository.setFormat(id, format.key) }
    }

    // ---------------------------------------------------------------------
    // Per-note encryption
    // ---------------------------------------------------------------------

    /**
     * Turns encryption on with the given password. Works both while creating a
     * note (key kept until the first insert) and for an existing note (written
     * right away). Returns `false` only for an unusable password.
     */
    fun enableEncryption(password: String): Boolean {
        if (password.length < NoteCrypto.MIN_PASSWORD_LENGTH) return false
        val salt = NoteCrypto.newSalt()
        val key = runCatching { NoteCrypto.deriveKey(password, salt) }.getOrNull() ?: return false

        val id = _uiState.value.id
        if (id != null) {
            NoteCrypto.rememberKey(id, key)
        } else {
            pendingKey = key
        }

        _uiState.update {
            it.copy(
                isEncrypted = true,
                isUnlocked = true,
                encryptionSalt = salt,
                encryptedContent = null,
                // The app lock no longer applies to this note.
                isLocked = false,
                isModified = true
            )
        }

        // An existing note must be rewritten right away: the plain text that is
        // in the database now has to become ciphertext.
        if (id != null) {
            viewModelScope.launch { writeCurrent() }
        }
        return true
    }

    /**
     * Turns encryption off while the note was never written, so there is no
     * ciphertext to verify yet: the pending password is simply discarded.
     */
    fun cancelPendingEncryption() {
        if (_uiState.value.isNotePersisted) return
        pendingKey = null
        _uiState.update {
            it.copy(
                isEncrypted = false,
                isUnlocked = true,
                encryptionSalt = null,
                encryptedContent = null
            )
        }
    }

    /** Verifies the password and stores the note as plain text again. */
    fun disableEncryption(password: String): Boolean {
        val state = _uiState.value
        if (!state.isEncrypted) return true
        val key = runCatching { NoteCrypto.deriveKey(password, state.encryptionSalt ?: return false) }
            .getOrNull() ?: return false
        val plaintext = NoteCrypto.decrypt(key, state.encryptedContent ?: "") ?: return false

        NoteCrypto.forgetKey(state.id ?: -1)
        _uiState.update {
            state.copy(
                title = plaintext.first,
                content = plaintext.second,
                isEncrypted = false,
                isUnlocked = true,
                encryptionSalt = null,
                encryptedContent = null,
                isModified = true
            )
        }
        viewModelScope.launch { writeCurrent() }
        return true
    }

    /** Decrypts a locked note after the user typed its own password. */
    fun unlockWithPassword(password: String): Boolean {
        val state = _uiState.value
        if (!state.isEncrypted) return true
        val key = runCatching { NoteCrypto.deriveKey(password, state.encryptionSalt ?: return false) }
            .getOrNull() ?: return false
        val plaintext = NoteCrypto.decrypt(key, state.encryptedContent ?: "") ?: return false

        state.id?.let { NoteCrypto.rememberKey(it, key) }
        _uiState.update {
            state.copy(
                title = plaintext.first,
                content = plaintext.second,
                isUnlocked = true,
                isLoading = false
            )
        }
        return true
    }

    fun saveNote() {
        viewModelScope.launch {
            writeCurrent()
            _uiState.value.id?.let { loadNote(it) }
        }
    }

    /** Writes the current editor state, encrypting it when needed. */
    private suspend fun writeCurrent() {
        val currentState = _uiState.value
        // Never overwrite the stored payload of a note that is still locked.
        if (currentState.isEncrypted && !currentState.isUnlocked) return

        if (currentState.category.isNotBlank()) {
            categoryRepository.insertCategory(currentState.category)
        }

        val toWrite = currentState.toNoteForStorage() ?: return

        if (currentState.isNotePersisted) {
            notesRepository.updateNote(toWrite)
        } else {
            val newId = notesRepository.addNote(toWrite).toInt()
            _uiState.update { it.copy(id = newId, isNotePersisted = true) }
            // The note now has an id, so its key can join the session cache.
            pendingKey?.let { key ->
                NoteCrypto.rememberKey(newId, key)
                pendingKey = null
            }
        }

        // What was just written is the new baseline; keep typing-safe if the
        // user changed something while the write was running.
        _uiState.update {
            if (it.title == currentState.title &&
                it.content == currentState.content &&
                it.category == currentState.category
            ) {
                it.copy(isModified = false)
            } else {
                it
            }
        }
    }

    /**
     * Builds the row to store. Encrypted notes are written with blank title and
     * content plus a fresh ciphertext, so the database never sees the plain
     * text. Returns `null` when there is no key to encrypt with.
     */
    private fun UiState.toNoteForStorage(): Note? {
        val base = Note(
            id = id ?: 0,
            title = title,
            content = content,
            category = category,
            isPinned = isPinned,
            isLocked = isLocked,
            isTrashed = isTrashed,
            createdTime = createdTime,
            openInEditMode = openInEditMode,
            isEncrypted = isEncrypted,
            encryptionSalt = encryptionSalt,
            encryptedContent = encryptedContent,
            format = format.key
        )

        if (!isEncrypted) {
            return base.copy(encryptionSalt = null, encryptedContent = null)
        }

        val key = (id?.let(NoteCrypto::keyFor) ?: pendingKey) ?: return null
        val payload = NoteCrypto.encrypt(key, title, content)
        return base.copy(title = "", content = "", encryptedContent = payload)
    }

    fun deleteNote() {
        viewModelScope.launch {
            val note = uiState.value
            val noteToDelete = Note(id = note.id ?: 0)
            if (note.isTrashed) {
                notesRepository.deleteNote(noteToDelete)
            } else {
                notesRepository.trashNote(noteToDelete)
            }
        }
    }

    fun restoreNote() {
        viewModelScope.launch {
            notesRepository.restoreNote(Note(id = uiState.value.id ?: 0))
        }
    }

    fun undoChanges() {
        if (noteId != -1) {
            loadNote(noteId)
        }
    }
}
