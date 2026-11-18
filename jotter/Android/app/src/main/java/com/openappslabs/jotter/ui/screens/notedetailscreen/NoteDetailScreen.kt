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

import com.openappslabs.jotter.ui.components.BackButton

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openappslabs.jotter.ui.components.CategoryItems
import com.openappslabs.jotter.ui.components.CategorySheet
import com.openappslabs.jotter.ui.components.ConfirmationDialog
import com.openappslabs.jotter.ui.components.TwoOptionButton
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Visibility
import com.openappslabs.jotter.ui.components.NoteActionSheet
import com.openappslabs.jotter.ui.components.NotePasswordDialog
import com.openappslabs.jotter.ui.components.RichTextView
import com.openappslabs.jotter.ui.components.NotePasswordMode
import com.openappslabs.jotter.ui.theme.rememberJotterHaptics
import com.openappslabs.jotter.utils.NoteUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.openappslabs.jotter.ui.i18n.LocalStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(
    modifier: Modifier = Modifier,
    onBackClick: () -> Unit,
    onManageCategoryClick: () -> Unit = {},
    viewModel: NoteDetailViewModel = hiltViewModel()
) {
    val haptics = rememberJotterHaptics()
    val strings = LocalStrings.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val userPrefs by viewModel.userPreferences.collectAsStateWithLifecycle()
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showCategorySheet by remember { mutableStateOf(false) }
    var showRestoreNoteDialog by remember { mutableStateOf(false) }
    var pendingDiscard by remember { mutableStateOf(false) }
    var showNoteActionSheet by remember { mutableStateOf(false) }
    var showEncryptionDialog by remember { mutableStateOf(false) }
    var encryptionMode by remember { mutableStateOf(NotePasswordMode.SET) }
    // The encryption switch only belongs to the create flow; existing notes are
    // handled from the note options.
    val isCreatingNote = !uiState.isLoading && !uiState.isNotePersisted
    val noteActionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val availableCategories by viewModel.availableCategories.collectAsStateWithLifecycle()
    val keyboardController = LocalSoftwareKeyboardController.current
    val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val titleFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }


    // The global "default open mode" can be overridden per note. Recomputing is
    // keyed on the load finishing (and on the global default changing), so
    // picking an option in the action sheet applies the next time the note
    // opens instead of yanking the editor away right now.
    var isViewMode by remember(uiState.isLoading, userPrefs.defaultOpenInEdit) {
        val openInEdit = if (uiState.id == null) {
            true
        } else {
            uiState.openInEditMode ?: userPrefs.defaultOpenInEdit
        }
        mutableStateOf(!openInEdit)
    }

    val isSaveEnabled = !isViewMode && uiState.isUnlocked && uiState.isModified &&
        (uiState.title.isNotBlank() || uiState.content.isNotBlank())

    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val primaryColor = MaterialTheme.colorScheme.primary

    val titleStyle = remember(onSurfaceColor) {
        TextStyle(
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
            color = onSurfaceColor,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both
            )
        )
    }

    val contentStyle = remember(onSurfaceColor) {
        TextStyle(
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Normal,
            color = onSurfaceColor.copy(alpha = 0.95f),
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both
            )
        )
    }

    val cursorBrush = remember(primaryColor) {
        SolidColor(primaryColor)
    }

    LaunchedEffect(uiState.isNotePersisted) {
        if (!uiState.isNotePersisted) {
            delay(200)
            titleFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    LaunchedEffect(isImeVisible) {
        if (!isImeVisible && pendingDiscard) {
            pendingDiscard = false
            showDiscardDialog = true
        }
    }

    fun handleBack() {
        if (uiState.isNotePersisted && !isViewMode && uiState.isModified) {
            if (isImeVisible) {
                pendingDiscard = true
                keyboardController?.hide()
            } else {
                showDiscardDialog = true
            }
        } else {
            keyboardController?.hide()
            if (isImeVisible) {
                scope.launch {
                    delay(120)
                    onBackClick()
                }
            } else {
                onBackClick()
            }
        }
    }

    val shouldInterceptBack = (uiState.isNotePersisted && !isViewMode && uiState.isModified) || 
                              (!uiState.isNotePersisted && isImeVisible)
    BackHandler(enabled = shouldInterceptBack) {
        handleBack()
    }

    val isTrashed = uiState.isTrashed

    // Read mode is a reader, not an editor that refuses keystrokes: the title
    // and the body are plain text inside a SelectionContainer, so a long press
    // selects a word and the copy menu shows up. A read-only field keeps a
    // caret and, in some formats, never offers the selection handles at all,
    // which is what made copying impossible here.
    val isReadOnly = isViewMode || isTrashed || !uiState.isUnlocked

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    if (uiState.isNotePersisted && !isTrashed && uiState.isUnlocked) {
                        TwoOptionButton(
                            isSecondSelected = isViewMode,
                            onToggle = {
                                haptics.tick()
                                isViewMode = !isViewMode
                            },
                            firstIcon = Icons.Default.Create,
                            secondIcon = Icons.Default.Visibility,
                            firstDescription = strings.editMode,
                            secondDescription = strings.viewMode,
                            iconButtonSize = 48.dp
                        )
                    } else {
                        Text(
                            text = "",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                },
                navigationIcon = {
                    val showCloseIcon = !isViewMode && uiState.isModified
                    BackButton(
                        onClick = ::handleBack,
                        icon = if (showCloseIcon) Icons.Default.Close else Icons.Default.ChevronLeft,
                        contentDescription = if (showCloseIcon) strings.close else strings.back
                    )
                },
                actions = {
                    if (isTrashed) {
                        Surface(
                            onClick = {
                                haptics.click()
                                showRestoreNoteDialog = true
                            },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            enabled = true,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Restore,
                                    contentDescription = strings.restore,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    } else if (isViewMode || !uiState.isUnlocked) {
                        Surface(
                            onClick = {
                                haptics.click()
                                showNoteActionSheet = true
                            },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = strings.actions,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    } else {
                        Surface(
                            onClick = {
                                haptics.success()
                                viewModel.saveNote()
                                isViewMode = true
                                keyboardController?.hide()
                            },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            enabled = isSaveEnabled,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Done,
                                    contentDescription = strings.save,
                                    tint = if (isSaveEnabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !isReadOnly
                    ) {
                        contentFocusRequester.requestFocus()
                    }
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                if (uiState.isEncrypted && !uiState.isUnlocked) {
                    LockedNoteGate(
                        onUnlockClick = {
                            haptics.click()
                            encryptionMode = NotePasswordMode.UNLOCK
                            showEncryptionDialog = true
                        }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                if (isCreatingNote) {
                    EncryptionSwitchRow(
                        isEncrypted = uiState.isEncrypted,
                        onToggle = { enabled ->
                            haptics.tick()
                            if (enabled) {
                                encryptionMode = NotePasswordMode.SET
                                showEncryptionDialog = true
                            } else if (uiState.isNotePersisted) {
                                encryptionMode = NotePasswordMode.REMOVE
                                showEncryptionDialog = true
                            } else {
                                viewModel.cancelPendingEncryption()
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (uiState.isUnlocked) {
                    Spacer(modifier = Modifier.height(8.dp))
                    if (isReadOnly) {
                        SelectionContainer {
                            Text(
                                text = uiState.title,
                                style = titleStyle,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        BasicTextField(
                            value = uiState.title,
                            onValueChange = { viewModel.updateTitle(it) },
                            textStyle = titleStyle,
                            cursorBrush = cursorBrush,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { contentFocusRequester.requestFocus() }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(titleFocusRequester),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (uiState.title.isEmpty()) {
                                        Text(
                                            text = strings.untitled,
                                            style = titleStyle.copy(color = onSurfaceColor.copy(alpha = 0.3f))
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    when {
                        // Read mode renders the plain text with the note's own
                        // language. Nothing is converted: the editor still shows
                        // exactly what is stored.
                        isReadOnly && uiState.format.isRich && !isTrashed -> SelectionContainer {
                            RichTextView(
                                text = uiState.content,
                                format = uiState.format,
                                baseStyle = contentStyle
                            )
                        }

                        // Plain text in read mode is a Text, not a read-only
                        // field, so long press/drag selects and copies.
                        isReadOnly -> SelectionContainer {
                            Text(
                                text = uiState.content,
                                style = contentStyle,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        else -> BasicTextField(
                            value = uiState.content,
                            onValueChange = { viewModel.updateContent(it) },
                            textStyle = contentStyle,
                            cursorBrush = cursorBrush,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(contentFocusRequester),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (uiState.content.isEmpty()) {
                                        Text(
                                            text = strings.startTyping,
                                            style = contentStyle.copy(color = onSurfaceColor.copy(alpha = 0.3f))
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showCategorySheet) {
        CategorySheet(
            categories = CategoryItems(availableCategories),
            selectedCategory = uiState.category,
            onCategorySelect = { newCategory ->
                haptics.tick()
                viewModel.updateCategory(newCategory)
                isViewMode = false
            },
            onManageCategoriesClick = {
                haptics.click()
                showCategorySheet = false
                showNoteActionSheet = false
                onManageCategoryClick()
            },
            onDismiss = { showCategorySheet = false }
        )
    }

    if (showEncryptionDialog) {
        NotePasswordDialog(
            mode = encryptionMode,
            onSubmit = { password ->
                val ok = when (encryptionMode) {
                    NotePasswordMode.SET -> viewModel.enableEncryption(password)
                    NotePasswordMode.UNLOCK -> viewModel.unlockWithPassword(password)
                    NotePasswordMode.REMOVE -> viewModel.disableEncryption(password)
                }
                if (ok) showEncryptionDialog = false
                ok
            },
            onDismiss = { showEncryptionDialog = false }
        )
    }

    if (showDiscardDialog) {
        ConfirmationDialog(
            icon = Icons.Default.Close,
            title = strings.discardChangesQuestion,
            message = strings.discardChangesMessage,
            confirmText = strings.discard,
            isDestructive = true,
            onDismiss = { showDiscardDialog = false },
            onConfirm = {
                showDiscardDialog = false
                keyboardController?.hide()
                if (uiState.isNotePersisted) {
                    viewModel.undoChanges()
                    isViewMode = true
                } else {
                    scope.launch {
                        delay(120)
                        onBackClick()
                    }
                }
            }
        )
    }


    if (showNoteActionSheet) {
        val locale = Locale.getDefault()
        val dateString = remember(uiState.createdTime, userPrefs.is24HourFormat, userPrefs.dateFormat, locale) {
            val datePattern = if (userPrefs.dateFormat.contains("/")) "${userPrefs.dateFormat}/yyyy"
            else "${userPrefs.dateFormat} yyyy"
            val timePattern = if (userPrefs.is24HourFormat) "HH:mm" else "hh:mm a"
            val pattern = "$datePattern, $timePattern"
            SimpleDateFormat(pattern, locale).format(Date(uiState.createdTime))
        }

        val modifiedDateString = remember(uiState.lastEdited, userPrefs.is24HourFormat, userPrefs.dateFormat, locale) {
            val datePattern = if (userPrefs.dateFormat.contains("/")) "${userPrefs.dateFormat}/yyyy"
            else "${userPrefs.dateFormat} yyyy"
            val timePattern = if (userPrefs.is24HourFormat) "HH:mm" else "hh:mm a"
            val pattern = "$datePattern, $timePattern"
            SimpleDateFormat(pattern, locale).format(Date(uiState.lastEdited))
        }

        NoteActionSheet(
            sheetState = noteActionSheetState,
            scope = scope,
            createdDate = dateString,
            modifiedDate = modifiedDateString,
            wordCount = NoteUtils.countWords(uiState.content),
            charCount = NoteUtils.countCharacters(uiState.content),
            category = uiState.category,
            isPinned = uiState.isPinned,
            isLocked = uiState.isLocked,
            isEncrypted = uiState.isEncrypted,
            openInEditMode = uiState.openInEditMode,
            format = uiState.format,
            isOpenInEditAvailable = uiState.isNotePersisted,
            onOpenInEditModeChange = { mode ->
                haptics.tick()
                viewModel.setOpenInEditMode(mode)
            },
            onFormatChange = { format ->
                haptics.tick()
                viewModel.setFormat(format)
            },
            onPinClick = {
                haptics.tick()
                viewModel.togglePin()
            },
            onEncryptionClick = {
                haptics.click()
                showNoteActionSheet = false
                encryptionMode = if (uiState.isEncrypted) {
                    NotePasswordMode.REMOVE
                } else {
                    NotePasswordMode.SET
                }
                showEncryptionDialog = true
            },
            onLockClick = {
                haptics.tick()
                if (userPrefs.isBiometricEnabled) {
                    viewModel.toggleLock()
                } else {
                    scope.launch {
                        if (snackbarHostState.currentSnackbarData == null) {
                            snackbarHostState.showSnackbar(
                                message = strings.enableNoteLockInSettings,
                                duration = SnackbarDuration.Short
                            )
                        }
                    }
                }
            },
            onShareClick = {
                haptics.click()
                val textToShare = NoteUtils.formatNote(uiState.title, uiState.content)
                NoteUtils.shareNote(context, textToShare)
                showNoteActionSheet = false
            },
            onDeleteClick = {
                haptics.heavy()
                showNoteActionSheet = false
                viewModel.deleteNote()
                onBackClick()
            },
            onCategoryClick = {
                haptics.click()
                showNoteActionSheet = false
                showCategorySheet = true
            },
            onDismissRequest = { showNoteActionSheet = false }
        )
    }

    if (showRestoreNoteDialog) {
        ConfirmationDialog(
            icon = Icons.Default.Restore,
            title = strings.restoreNoteQuestion,
            message = strings.restoreNoteMessage,
            confirmText = strings.restore,
            isDestructive = false,
            onDismiss = { showRestoreNoteDialog = false },
            onConfirm = {
                showRestoreNoteDialog = false
                viewModel.restoreNote()
                onBackClick()
            }
        )
    }
}


/**
 * Create-time switch: turns on a password that belongs to this note only.
 */
@Composable
private fun EncryptionSwitchRow(
    isEncrypted: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Key,
                contentDescription = null,
                tint = if (isEncrypted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strings.encryptThisNote,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (isEncrypted) strings.protectedByOwnPassword
                    else strings.independentFromAppLock,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = isEncrypted, onCheckedChange = onToggle)
        }
    }
}

/**
 * Shown instead of the editor when an encrypted note is not unlocked yet. The
 * plain text is not even in memory at this point.
 */
@Composable
private fun LockedNoteGate(
    onUnlockClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Text(
            text = strings.thisNoteIsEncrypted,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = strings.enterItsOwnPassword,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            onClick = onUnlockClick,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Text(
                text = strings.unlock,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
