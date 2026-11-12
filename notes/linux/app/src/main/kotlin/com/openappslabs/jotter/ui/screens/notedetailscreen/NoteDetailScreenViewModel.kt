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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openappslabs.jotter.data.crypto.NoteCrypto
import com.openappslabs.jotter.data.model.Note
import com.openappslabs.jotter.data.model.NoteFormat
import com.openappslabs.jotter.data.repository.CategoryRepository
import com.openappslabs.jotter.data.repository.NotesRepository
import com.openappslabs.jotter.data.repository.UserPreferences
import com.openappslabs.jotter.data.repository.UserPreferencesRepository
import com.openappslabs.jotter.platform.AutoSave
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

class NoteDetailViewModel(
    noteIdParam: Int = -1,
    categoryParam: String? = null,
    private val notesRepository: NotesRepository,
    private val categoryRepository: CategoryRepository,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val noteId: Int = noteIdParam
    private val passedCategory: String? = categoryParam

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

    private var originalState: UiState? = null

    /** Pending debounced write; cancelled and restarted on every keystroke. */
    private var autoSaveJob: Job? = null

    /**
     * Key of a note that is still being created: it has no id yet, so it
     * cannot live in the [NoteCrypto] session cache until the first insert.
     */
    private var pendingKey: SecretKey? = null

    /** The shell tells every editor to persist when the widget loses focus. */
    private val unregisterAutoSave = AutoSave.register { flushNow() }

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
            originalState = initialState
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
                    checkForChanges()
                }
            }
        }
    }

    private fun observeNoteUpdates() {
        if (noteId == -1) return
        viewModelScope.launch {
            // Only this row. Watching the whole list here made opening a note
            // read and decrypt every note in the database, which is exactly
            // what made the editor take a moment to appear.
            notesRepository.observeNote(noteId)
                .map { it?.category }
                .distinctUntilChanged()
                .collect { category ->
                    if (category != null && category != uiState.value.category) {
                        _uiState.update { it.copy(category = category) }
                        originalState = originalState?.copy(category = category)
                        checkForChanges()
                    }
                }
        }
    }

    private fun loadNote(id: Int) {
        // A pending debounced write would overwrite what is being loaded.
        autoSaveJob?.cancel()
        autoSaveJob = null
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
                originalState = newState
            } else {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun checkForChanges() {
        val current = _uiState.value
        val original = originalState ?: return
        val modified = current.title != original.title ||
                       current.content != original.content ||
                       current.category != original.category

        if (current.isModified != modified) {
            _uiState.update { it.copy(isModified = modified) }
        }
    }

    fun updateTitle(newTitle: String) {
        _uiState.update { it.copy(title = newTitle) }
        checkForChanges()
        scheduleAutoSave()
    }

    fun updateContent(newContent: String) {
        _uiState.update { it.copy(content = newContent) }
        checkForChanges()
        scheduleAutoSave()
    }

    fun updateCategory(newCategory: String) {
        _uiState.update { it.copy(category = newCategory) }
        checkForChanges()
        scheduleAutoSave()
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
                isLocked = false
            )
        }
        originalState = originalState?.copy(isEncrypted = true, isUnlocked = true, isLocked = false)

        if (id != null) {
            persistImmediately()
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
        originalState = originalState?.copy(
            isEncrypted = false,
            isUnlocked = true,
            encryptionSalt = null,
            encryptedContent = null
        )
    }

    /** Verifies the password and stores the note as plain text again. */
    fun disableEncryption(password: String): Boolean {
        val state = _uiState.value
        if (!state.isEncrypted) return true
        val key = runCatching { NoteCrypto.deriveKey(password, state.encryptionSalt ?: return false) }
            .getOrNull() ?: return false
        val plaintext = NoteCrypto.decrypt(key, state.encryptedContent ?: "") ?: return false

        NoteCrypto.forgetKey(state.id ?: -1)
        val newState = state.copy(
            title = plaintext.first,
            content = plaintext.second,
            isEncrypted = false,
            isUnlocked = true,
            encryptionSalt = null,
            encryptedContent = null
        )
        _uiState.update { newState }
        originalState = newState.copy(isModified = false)
        persistImmediately()
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
        val newState = state.copy(
            title = plaintext.first,
            content = plaintext.second,
            isUnlocked = true,
            isLoading = false,
            isModified = false
        )
        _uiState.update { newState }
        originalState = newState
        return true
    }

    fun saveNote() {
        // The debounced write may be pending; persist right away instead.
        flush()
    }

    fun deleteNote() {
        // Do not let a queued auto-save write to a note that is going away.
        autoSaveJob?.cancel()
        autoSaveJob = null
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

    // ---------------------------------------------------------------------
    // Auto-save
    //
    // The widget hides itself when it loses focus, so writing has to be
    // stored without the user pressing anything: every change restarts a
    // short timer and the shell can force a flush when the window goes away.
    // ---------------------------------------------------------------------

    private fun hasChangesToWrite(state: UiState = uiState.value): Boolean {
        if (state.isLoading || state.isTrashed) return false
        // Never touch the stored payload of a note that is still locked.
        if (state.isEncrypted && !state.isUnlocked) return false
        // Never create a note out of an empty editor.
        return state.title.isNotBlank() || state.content.isNotBlank()
    }

    private fun scheduleAutoSave() {
        if (!hasChangesToWrite()) return
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(AUTO_SAVE_DELAY_MS)
            persistNow()
        }
    }

    /** Persists pending edits immediately. Called when the widget hides. */
    fun flush() {
        autoSaveJob?.cancel()
        autoSaveJob = null
        if (!hasChangesToWrite()) return
        viewModelScope.launch { persistNow() }
    }

    /**
     * Same as [flush], but suspends until the row is written. Used by
     * [AutoSave], which waits for it when the process is shutting down —
     * `viewModelScope` may never get to run in that case.
     */
    private suspend fun flushNow() {
        autoSaveJob?.cancel()
        autoSaveJob = null
        if (!hasChangesToWrite()) return
        persistNow()
    }

    /** Writes even when nothing looks modified (encryption toggles). */
    private fun persistImmediately() {
        autoSaveJob?.cancel()
        autoSaveJob = null
        if (!hasChangesToWrite()) return
        viewModelScope.launch { persistNow(force = true) }
    }

    private suspend fun persistNow(force: Boolean = false) {
        val snapshot = _uiState.value
        if (!hasChangesToWrite(snapshot)) return
        if (!force && snapshot.isNotePersisted && !snapshot.isModified) return

        if (snapshot.category.isNotBlank()) {
            categoryRepository.insertCategory(snapshot.category)
        }

        val toWrite = snapshot.toNoteForStorage() ?: return

        if (snapshot.isNotePersisted) {
            notesRepository.updateNote(toWrite)
        } else {
            val newId = notesRepository.addNote(toWrite).toInt()
            // Adopt the id without disturbing what is being typed.
            _uiState.update { it.copy(id = newId, isNotePersisted = true) }
            // The note now has an id, so its key can join the session cache.
            pendingKey?.let { key ->
                NoteCrypto.rememberKey(newId, key)
                pendingKey = null
            }
        }

        // Everything that was just written counts as the new baseline.
        originalState = snapshot.copy(
            id = _uiState.value.id,
            isNotePersisted = true,
            lastEdited = System.currentTimeMillis()
        )
        checkForChanges()
        // The user kept typing while the write was running.
        if (_uiState.value.isModified) {
            scheduleAutoSave()
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

    override fun onCleared() {
        // The screen also flushes on disposal; this only cleans up the hook.
        unregisterAutoSave()
        super.onCleared()
    }

    private companion object {
        /** Short enough to feel instant, long enough to coalesce typing. */
        const val AUTO_SAVE_DELAY_MS = 700L
    }
}
