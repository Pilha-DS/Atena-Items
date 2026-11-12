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

package com.openappslabs.jotter.ui.screens.trashscreen

import com.openappslabs.jotter.ui.components.BackButton

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import android.widget.Toast
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openappslabs.jotter.ui.components.EmptyState
import com.openappslabs.jotter.ui.components.NoteCard
import com.openappslabs.jotter.ui.components.ConfirmationDialog
import com.openappslabs.jotter.ui.theme.rememberJotterHaptics
import com.openappslabs.jotter.utils.BiometricAuthUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.openappslabs.jotter.ui.i18n.LocalStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBackClick: () -> Unit,
    onNoteClick: (Int) -> Unit = {},
    viewModel: TrashScreenViewModel = hiltViewModel()
) {
    val haptics = rememberJotterHaptics()
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val strings = LocalStrings.current
    val trashedNotes = uiState.trashedNotes
    val showEmptyTrashDialog = uiState.showEmptyTrashDialog
    val showRestoreDialog = uiState.showRestoreAllDialog
    
    val locale = strings.locale
    val dateFormatter = remember(uiState.dateFormat, uiState.isGridView, locale) {
        val format = if (!uiState.isGridView) {
            if (uiState.dateFormat.contains("/")) "${uiState.dateFormat}/yyyy"
            else "${uiState.dateFormat} yyyy"
        } else {
            uiState.dateFormat
        }
        SimpleDateFormat(format, locale)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = strings.trash,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    BackButton(onClick = onBackClick)
                },
                actions = {
                    if (trashedNotes.isNotEmpty()) {
                        Surface(
                            onClick = {
                                haptics.click()
                                viewModel.onRestoreAllClicked()
                            },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.padding(end = 12.dp).size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Restore,
                                    contentDescription = strings.restoreAll,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        },
        floatingActionButton = {
            if (trashedNotes.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        haptics.click()
                        viewModel.onEmptyTrashClicked()
                    },
                    icon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
                    text = { Text(strings.emptyTrash) },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (trashedNotes.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.DeleteForever,
                    title = strings.nothingInTrash,
                    description = strings.notesYouDeleteAppearHere,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 48.dp)
                )
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                LazyVerticalStaggeredGrid(
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
                    contentPadding = PaddingValues(bottom = 80.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalItemSpacing = 12.dp
                ) {
                    items(trashedNotes, key = { it.id }) { note ->
                        val dateStr = remember(note.updatedTime, uiState.dateFormat, uiState.isGridView, locale) {
                            dateFormatter.format(Date(note.updatedTime))
                        }

                        NoteCard(
                            note = note,
                            date = dateStr,
                            isGridView = uiState.isGridView,
                            onClick = {
                                haptics.tick()
                                viewModel.onNoteClicked(note.id)
                                if (note.isLocked && uiState.isBiometricEnabled) {
                                    val activity = context as? FragmentActivity
                                    if (activity != null) {
                                        BiometricAuthUtil.authenticate(
                                            activity = activity,
                                            title = strings.unlockNote,
                                            subtitle = strings.authenticateToViewLockedNote,
                                            onSuccess = { onNoteClick(note.id) },
                                            onError = { Toast.makeText(context, strings.authenticationFailed, Toast.LENGTH_SHORT).show() }
                                        )
                                    } else {
                                        onNoteClick(note.id)
                                    }
                                } else {
                                    onNoteClick(note.id)
                                }
                            }
                        )
                    }
                }
            }
        }

        if (showEmptyTrashDialog) {
            ConfirmationDialog(
                icon = Icons.Default.DeleteForever,
                title = strings.emptyTrashQuestion,
                message = strings.emptyTrashMessage,
                confirmText = strings.emptyTrash,
                isDestructive = true,
                onDismiss = { viewModel.dismissEmptyTrashDialog() },
                onConfirm = { viewModel.confirmEmptyTrash() }
            )
        }

        if (showRestoreDialog) {
            ConfirmationDialog(
                icon = Icons.Default.Restore,
                title = strings.restoreAllNotesQuestion,
                message = strings.restoreAllNotesMessage(trashedNotes.size),
                confirmText = strings.restoreAll,
                isDestructive = false,
                onDismiss = { viewModel.dismissRestoreAllDialog() },
                onConfirm = { viewModel.confirmRestoreAll() }
            )
        }
    }
}
