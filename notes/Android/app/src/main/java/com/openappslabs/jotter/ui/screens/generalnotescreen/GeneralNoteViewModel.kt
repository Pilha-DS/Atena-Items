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

package com.openappslabs.jotter.ui.screens.generalnotescreen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openappslabs.jotter.data.model.Note
import com.openappslabs.jotter.data.repository.NotesRepository
import com.openappslabs.jotter.di.ApplicationScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The general note (scratchpad) on the phone.
 *
 * It is one row, created on first open and shared with the desktop app through
 * the fixed scratchpad uuid. The screen edits a local copy of the text and the
 * debounce in [GeneralNoteScreen] writes it back, so typing never waits for the
 * database.
 */
@HiltViewModel
class GeneralNoteViewModel @Inject constructor(
    private val repository: NotesRepository,
    @ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    /** `null` until the row is loaded, so the editor never flashes empty text. */
    private val _note = MutableStateFlow<Note?>(null)
    val note: StateFlow<Note?> = _note.asStateFlow()

    private val _content = MutableStateFlow<String?>(null)
    val content: StateFlow<String?> = _content.asStateFlow()

    /** Last text known to be in the database; avoids writing the same thing. */
    private var savedContent: String = ""

    init {
        viewModelScope.launch {
            val scratchpad = repository.getOrCreateScratchpad()
            savedContent = scratchpad.content
            _note.value = scratchpad
            _content.value = scratchpad.content
        }
    }

    fun onContentChange(value: String) {
        _content.value = value
    }

    /** Called by the debounce in the screen and when it goes away. */
    fun save() {
        val note = _note.value ?: return
        val text = _content.value ?: return
        if (text == savedContent) return
        savedContent = text
        // The app scope, not `viewModelScope`: leaving the screen cancels the
        // latter, and the last keystrokes must still be written.
        appScope.launch {
            repository.saveScratchpad(note.id, text)
        }
    }
}
