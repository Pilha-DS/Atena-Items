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

package com.openappslabs.jotter.ui.screens.homescreen

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openappslabs.jotter.data.crypto.NoteCrypto
import com.openappslabs.jotter.data.model.Note
import com.openappslabs.jotter.data.model.NoteFormat
import com.openappslabs.jotter.data.repository.CategoryRepository
import com.openappslabs.jotter.data.repository.NotesRepository
import com.openappslabs.jotter.data.repository.UserPreferencesRepository
import com.openappslabs.jotter.data.model.SortDirection
import com.openappslabs.jotter.data.model.SortType
import com.openappslabs.jotter.platform.Startup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.comparisons.compareByDescending
import kotlin.comparisons.then

class HomeScreenViewModel(
    private val notesRepository: NotesRepository,
    private val categoryRepository: CategoryRepository,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {
    private val _selectedCategory = MutableStateFlow("All")
    private val _searchQuery = MutableStateFlow("")

    /**
     * The list is the "rest of the app": when Jotter opens, the general note
     * window loads its single row first and this waits for it. Reading the
     * whole table at the same time made that first read compete for the
     * database and slowed down what the user was actually looking at.
     */
    private val notesFlow: Flow<List<Note>> = flow {
        Startup.awaitGeneralNote()
        emitAll(notesRepository.getAllNotes())
    }

    val uiState: StateFlow<UiState> = combine(
        notesFlow,
        categoryRepository.getAllCategories().map { list -> list.map { it.name } },
        userPreferencesRepository.userPreferencesFlow,
        _selectedCategory,
        _searchQuery
    ) { notes, categories, prefs, selectedCategory, searchQuery ->
        val activeCategories = categories.filter { category ->
            notes.any { it.category == category }
        }

        val validatedCategory = if (selectedCategory != "All" && 
            !listOf("Pinned", "Locked").contains(selectedCategory) && 
            !activeCategories.contains(selectedCategory)) {
            "All"
        } else {
            selectedCategory
        }

        val filteredNotes = if (searchQuery.isNotBlank()) {
            notes.filter {
                !it.isLocked && (
                    it.title.contains(searchQuery, ignoreCase = true) ||
                        it.content.contains(searchQuery, ignoreCase = true)
                )
            }
        } else {
            when (validatedCategory) {
                "All"     -> notes
                "Pinned"  -> notes.filter { it.isPinned }
                "Locked"  -> notes.filter { it.isLocked }
                else      -> notes.filter { it.category == validatedCategory }
            }
        }

        val sortType = SortType.valueOf(prefs.sortType)
        val sortDirection = SortDirection.valueOf(prefs.sortDirection)

        val baseComparator: Comparator<Note> = when (sortType) {
            SortType.ALPHABETICAL -> Comparator { a, b -> a.title.compareTo(b.title, ignoreCase = true) }
            SortType.CREATED -> Comparator { a, b -> a.createdTime.compareTo(b.createdTime) }
            SortType.LAST_UPDATED -> Comparator { a, b -> a.updatedTime.compareTo(b.updatedTime) }
        }
        val comparator = if (sortDirection == SortDirection.DESCENDING) baseComparator.reversed() else baseComparator

        val sortedNotes = filteredNotes.sortedWith(
            compareByDescending<Note> { it.isPinned }.then(comparator)
        )

        UiState(
            allNotes = sortedNotes,
            selectedCategory = validatedCategory,
            searchQuery = searchQuery,
            isGridView = prefs.isGridView,
            allAvailableCategories = activeCategories,
            showAddCategoryButton = prefs.showAddCategoryButton,
            showSortButton = prefs.showSortButton,
            isBiometricEnabled = prefs.isBiometricEnabled,
            dateFormat = prefs.dateFormat,
            sortType = sortType,
            sortDirection = sortDirection,
            isLoadingNotes = false
        )
    }
    // Filtering, searching and sorting every note must not run on the thread
    // that draws the window.
    .flowOn(Dispatchers.Default)
    .distinctUntilChanged()
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UiState()
    )

    @Immutable
    data class UiState(
        val allNotes: List<Note> = emptyList(),
        val selectedCategory: String = "All",
        val searchQuery: String = "",
        val isGridView: Boolean = true,
        val allAvailableCategories: List<String> = emptyList(),
        val showAddCategoryButton: Boolean = true,
        val showSortButton: Boolean = false,
        val isBiometricEnabled: Boolean = false,
        val dateFormat: String = "dd MMM",
        val sortType: SortType = SortType.LAST_UPDATED,
        val sortDirection: SortDirection = SortDirection.DESCENDING,
        /** `true` until the first read finishes: keeps "no notes yet" from flashing. */
        val isLoadingNotes: Boolean = true
    )

    fun toggleGridView() {
        val currentIsGrid = uiState.value.isGridView
        viewModelScope.launch {
            userPreferencesRepository.setGridView(!currentIsGrid)
        }
    }

    fun setSortType(sortType: SortType) {
        viewModelScope.launch {
            userPreferencesRepository.setSortType(sortType.name)
        }
    }

    fun setSortDirection(sortDirection: SortDirection) {
        viewModelScope.launch {
            userPreferencesRepository.setSortDirection(sortDirection.name)
        }
    }

    fun selectCategory(category: String) {
        _selectedCategory.value = category
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    // Note settings edited straight from the list. They use the config-only
    // repository calls so the list order stays put. The target value comes from
    // the UI instead of `uiState`, which may not be collected yet.
    fun setPinned(noteId: Int, isPinned: Boolean) {
        viewModelScope.launch { notesRepository.setPinned(noteId, isPinned) }
    }

    fun setLocked(noteId: Int, isLocked: Boolean) {
        viewModelScope.launch { notesRepository.setLocked(noteId, isLocked) }
    }

    fun setCategory(noteId: Int, category: String) {
        viewModelScope.launch {
            if (category.isNotBlank()) categoryRepository.insertCategory(category)
            notesRepository.setNoteCategory(noteId, category)
        }
    }

    fun setOpenInEditMode(noteId: Int, openInEdit: Boolean?) {
        viewModelScope.launch { notesRepository.updateOpenInEditMode(noteId, openInEdit) }
    }

    /** Changes how the note is displayed when read; the text is untouched. */
    fun setFormat(noteId: Int, format: NoteFormat) {
        viewModelScope.launch { notesRepository.setFormat(noteId, format.key) }
    }

    fun trashNote(noteId: Int) {
        viewModelScope.launch { notesRepository.trashNote(Note(id = noteId)) }
    }

    // ---------------------------------------------------------------------
    // Per-note encryption, editable straight from the list. The password is
    // used only for this note; the app lock is not involved at all.
    // ---------------------------------------------------------------------

    /** Encrypts the note with its own password. Returns `false` when refused. */
    fun encryptNote(noteId: Int, password: String): Boolean {
        if (password.length < NoteCrypto.MIN_PASSWORD_LENGTH) return false
        val note = uiState.value.allNotes.find { it.id == noteId } ?: return false
        if (note.isEncrypted) return false

        val salt = NoteCrypto.newSalt()
        val key = runCatching { NoteCrypto.deriveKey(password, salt) }.getOrNull() ?: return false
        val payload = NoteCrypto.encrypt(key, note.title, note.content)

        // Stays readable for the rest of this app run.
        NoteCrypto.rememberKey(noteId, key)
        viewModelScope.launch {
            notesRepository.setEncryption(
                noteId = noteId,
                isEncrypted = true,
                salt = salt,
                payload = payload,
                title = "",
                content = ""
            )
            // Its own password replaces the app lock on this note.
            notesRepository.setLocked(noteId, false)
        }
        return true
    }

    /** Removes this note's password after verifying it. */
    fun decryptNote(noteId: Int, password: String): Boolean {
        val note = uiState.value.allNotes.find { it.id == noteId } ?: return false
        if (!note.isEncrypted) return false

        val key = NoteCrypto.verifyAndDerive(note, password) ?: return false
        val plaintext = NoteCrypto.decrypt(key, note.encryptedContent ?: return false) ?: return false

        NoteCrypto.forgetKey(noteId)
        viewModelScope.launch {
            notesRepository.setEncryption(
                noteId = noteId,
                isEncrypted = false,
                salt = null,
                payload = null,
                title = plaintext.first,
                content = plaintext.second
            )
        }
        return true
    }

    fun onNoteClicked(noteId: Int) {
    }
}