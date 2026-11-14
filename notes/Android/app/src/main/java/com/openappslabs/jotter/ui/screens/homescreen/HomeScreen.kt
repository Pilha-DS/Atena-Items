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

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.openappslabs.jotter.ui.components.SortSheet
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.SearchOff
import com.openappslabs.jotter.ui.components.EmptyState
import com.openappslabs.jotter.ui.components.CategoryBar
import com.openappslabs.jotter.ui.components.CategoryItems
import com.openappslabs.jotter.ui.components.FAB
import com.openappslabs.jotter.ui.components.NoteCard
import com.openappslabs.jotter.ui.components.NoteOptionsSheet
import com.openappslabs.jotter.ui.components.NotePasswordDialog
import com.openappslabs.jotter.ui.components.NotePasswordMode
import com.openappslabs.jotter.ui.components.SearchBar
import com.openappslabs.jotter.ui.screens.syncscreen.syncErrorMessage
import com.openappslabs.jotter.ui.theme.rememberJotterHaptics
import com.openappslabs.jotter.data.model.Note
import com.openappslabs.jotter.data.model.NoteFormat
import com.openappslabs.jotter.utils.BiometricAuthUtil
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.openappslabs.jotter.ui.i18n.LocalStrings

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onNoteClick: (Int) -> Unit,
    onAddNoteClick: (String?) -> Unit,
    onAddCategoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onGeneralNoteClick: () -> Unit = {},
    onLinksClick: () -> Unit = {},
    viewModel: HomeScreenViewModel = hiltViewModel()
) {
    val haptics = rememberJotterHaptics()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val syncFeedback by viewModel.syncFeedback.collectAsStateWithLifecycle()
    val listState = rememberLazyStaggeredGridState()
    val context = LocalContext.current
    val strings = LocalStrings.current
    val locale = strings.locale

    // One toast per manual reload: the result, or why the server said no.
    LaunchedEffect(syncFeedback?.id) {
        val feedback = syncFeedback ?: return@LaunchedEffect
        val message = feedback.error?.let { syncErrorMessage(it, strings) }
            ?: strings.syncResult(feedback.pushed, feedback.pulled)
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.clearSyncFeedback()
    }

    var showSortSheet by remember { mutableStateOf(false) }
    val sortSheetState = rememberModalBottomSheetState()

    // Note settings opened from the list (long press).
    var optionsNote by remember { mutableStateOf<Note?>(null) }
    // Note whose password is being set/removed from the list.
    var encryptionNote by remember { mutableStateOf<Note?>(null) }
    val noteOptionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val optionsScope = rememberCoroutineScope()

    // Keep showing the last snapshot if the note disappears (trashed) while the
    // sheet is closing, but always render the live values otherwise.
    val liveOptionsNote = optionsNote?.let { snapshot ->
        uiState.allNotes.firstOrNull { it.id == snapshot.id } ?: snapshot
    }
    val closeOptions = {
        optionsScope.launch {
            runCatching { noteOptionsSheetState.hide() }
            optionsNote = null
        }
    }
    val dateFormatter = remember(uiState.dateFormat, uiState.isGridView, locale) {
        val format = if (!uiState.isGridView) {
            if (uiState.dateFormat.contains("/")) "${uiState.dateFormat}/yyyy"
            else "${uiState.dateFormat} yyyy"
        } else {
            uiState.dateFormat
        }
        SimpleDateFormat(format, locale)
    }

    LaunchedEffect(uiState.selectedCategory) {
        listState.animateScrollToItem(0)
    }

    LaunchedEffect(uiState.sortType, uiState.sortDirection) {
        listState.animateScrollToItem(0)
    }

    Scaffold(
        floatingActionButton = {
            FAB(
                onClick = {
                    val category = if (uiState.selectedCategory == "All") null else uiState.selectedCategory
                    onAddNoteClick(category)
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            SearchBar(
                query = uiState.searchQuery,
                onQueryChange = viewModel::onSearchQueryChange,
                onProfileClick = {  },
                onSettingsClick = {
                    haptics.click()
                    onSettingsClick()
                },
                onSyncClick = if (syncState.configured) viewModel::syncNow else null,
                isSyncing = syncState.running,
                modifier = Modifier.padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 8.dp)
            )

            CategoryBar(
                categories = CategoryItems(uiState.allAvailableCategories),
                selectedCategory = uiState.selectedCategory,
                onCategorySelect = { viewModel.selectCategory(it) },
                onAddCategoryClick = onAddCategoryClick,
                showAddButton = uiState.showAddCategoryButton,
                showSortButton = uiState.showSortButton,
                modifier = Modifier.padding(bottom = 8.dp),
                sortDirection = uiState.sortDirection,
                sortType = uiState.sortType,
                onSortClick = {
                    haptics.tick()
                    showSortSheet = true
                },
                onGeneralNoteClick = onGeneralNoteClick,
                onLinksClick = onLinksClick
            )

            if (uiState.allNotes.isEmpty()) {
                val (emptyIcon, emptyTitle, emptyDescription) = when {
                    uiState.searchQuery.isNotBlank() -> Triple(
                        Icons.Outlined.SearchOff,
                        strings.noNotesFound,
                        strings.noNotesMatch(uiState.searchQuery)
                    )
                    uiState.selectedCategory != "All" -> Triple(
                        Icons.Outlined.FolderOff,
                        strings.noNotesInCategory(uiState.selectedCategory),
                        strings.notesInCategoryHint
                    )
                    else -> Triple(
                        Icons.Outlined.EditNote,
                        strings.noNotesYet,
                        strings.createFirstNoteHint
                    )
                }

                EmptyState(
                    icon = emptyIcon,
                    title = emptyTitle,
                    description = emptyDescription,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(bottom = 48.dp)
                )
            } else {
                LazyVerticalStaggeredGrid(
                    state = listState,
                columns = StaggeredGridCells.Fixed(if (uiState.isGridView) 2 else 1),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = 0.dp,
                        bottomEnd = 0.dp
                    )),
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalItemSpacing = 12.dp
            ) {
                items(uiState.allNotes, key = { it.id }) { note ->
                    val dateStr = remember(note.createdTime, uiState.dateFormat, uiState.isGridView, locale) {
                        dateFormatter.format(Date(note.createdTime))
                    }
                    NoteCard(
                        note = note,
                        date = dateStr,
                        isGridView = uiState.isGridView,
                        onClick   = { 
                            haptics.tick()
                            viewModel.onNoteClicked(note.id)
                            if (note.isEncrypted) {
                                // Its own password is requested by the editor.
                                onNoteClick(note.id)
                            } else if (note.isLocked && uiState.isBiometricEnabled) {
                                val activity = context as? FragmentActivity
                                if (activity != null) {
                                    BiometricAuthUtil.authenticate(
                                        activity = activity,
                                        title = strings.unlockNote,
                                        subtitle = strings.authenticateToViewLockedNote,
                                        onSuccess = {
                                            onNoteClick(note.id)
                                        },
                                        onError = { error ->
                                            Toast.makeText(context, strings.authenticationFailed, Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                } else {
                                    onNoteClick(note.id)
                                }
                            } else {
                                onNoteClick(note.id)
                            }
                        },
                        onLongClick = {
                            haptics.click()
                            optionsNote = note
                        },
                        onOptionsClick = {
                            optionsNote = note
                        },
                        modifier  = Modifier.animateItem()
                    )
                }
            }
            }
        }
    }

    if (showSortSheet) {
        SortSheet(
            sheetState = sortSheetState,
            currentSortType = uiState.sortType,
            currentSortDirection = uiState.sortDirection,
            onSortTypeChange = {
                viewModel.setSortType(it)
            },
            onSortDirectionChange = {
                viewModel.setSortDirection(it)
            },
            onDismissRequest = { showSortSheet = false }
        )
    }

    val encryptionTarget = encryptionNote
    if (encryptionTarget != null) {
        NotePasswordDialog(
            mode = if (encryptionTarget.isEncrypted) NotePasswordMode.REMOVE else NotePasswordMode.SET,
            onSubmit = { password ->
                val ok = if (encryptionTarget.isEncrypted) {
                    viewModel.decryptNote(encryptionTarget.id, password)
                } else {
                    viewModel.encryptNote(encryptionTarget.id, password)
                }
                if (ok) encryptionNote = null
                ok
            },
            onDismiss = { encryptionNote = null }
        )
    }

    val options = liveOptionsNote
    if (options != null) {
        NoteOptionsSheet(
            sheetState = noteOptionsSheetState,
            title = if (options.isEncrypted && options.title.isBlank()) strings.encryptedNote else options.title,
            isPinned = options.isPinned,
            isLocked = options.isLocked,
            isEncrypted = options.isEncrypted,
            category = options.category,
            categories = CategoryItems(uiState.allAvailableCategories),
            openInEditMode = options.openInEditMode,
            format = NoteFormat.fromKey(options.format),
            onFormatChange = { format ->
                haptics.tick()
                viewModel.setFormat(options.id, format)
            },
            onPinClick = {
                haptics.tick()
                viewModel.setPinned(options.id, !options.isPinned)
            },
            onEncryptionClick = {
                haptics.click()
                closeOptions()
                encryptionNote = options
            },
            onLockClick = {
                haptics.tick()
                if (options.isEncrypted) {
                    Toast.makeText(
                        context,
                        strings.noteHasOwnPassword,
                        Toast.LENGTH_SHORT
                    ).show()
                } else if (uiState.isBiometricEnabled) {
                    viewModel.setLocked(options.id, !options.isLocked)
                } else {
                    Toast.makeText(
                        context,
                        strings.enableNoteLockInSettings,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onCategorySelect = { category ->
                haptics.tick()
                viewModel.setCategory(options.id, category)
            },
            onOpenInEditModeChange = { mode ->
                haptics.tick()
                viewModel.setOpenInEditMode(options.id, mode)
            },
            onTrashClick = {
                haptics.heavy()
                viewModel.trashNote(options.id)
                closeOptions()
                Toast.makeText(context, strings.noteMovedToTrash, Toast.LENGTH_SHORT).show()
            },
            onDismissRequest = { optionsNote = null }
        )
    }
}