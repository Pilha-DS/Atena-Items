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

package com.openappslabs.jotter.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openappslabs.jotter.data.model.NoteFormat
import com.openappslabs.jotter.ui.i18n.LocalStrings

/**
 * Note settings without leaving the note list: the same options that the note
 * editor exposes (pin, lock, category, open mode) plus trashing the note.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NoteOptionsSheet(
    sheetState: SheetState,
    title: String,
    isPinned: Boolean,
    isLocked: Boolean,
    isEncrypted: Boolean,
    category: String,
    categories: CategoryItems,
    openInEditMode: Boolean?,
    format: NoteFormat,
    onPinClick: () -> Unit,
    onLockClick: () -> Unit,
    onEncryptionClick: () -> Unit,
    onCategorySelect: (String) -> Unit,
    onOpenInEditModeChange: (Boolean?) -> Unit,
    onFormatChange: (NoteFormat) -> Unit,
    onTrashClick: () -> Unit,
    onDismissRequest: () -> Unit
) {
    val scrollState = rememberScrollState()
    val strings = LocalStrings.current

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (title.isBlank()) strings.untitled else title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = strings.noteSettings,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        ActionButton(
                            icon = if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            text = if (isPinned) strings.unpinNote else strings.pinNote,
                            onClick = onPinClick,
                            isActive = isPinned,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        ActionButton(
                            icon = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            text = if (isLocked) strings.unlockNoteAction else strings.lockNote,
                            onClick = onLockClick,
                            isActive = isLocked,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    ActionButton(
                        icon = if (isEncrypted) Icons.Default.LockOpen else Icons.Default.Key,
                        text = if (isEncrypted) strings.removeNotePassword else strings.encryptWithPassword,
                        onClick = onEncryptionClick,
                        isActive = isEncrypted,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OpenModeSelector(
                        openInEditMode = openInEditMode,
                        onSelect = onOpenInEditModeChange
                    )

                    NoteFormatSelector(
                        format = format,
                        onSelect = onFormatChange
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = strings.category,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OptionChip(
                                label = strings.none,
                                isSelected = category.isBlank(),
                                onClick = { onCategorySelect("") }
                            )
                            categories.items.forEach { item ->
                                OptionChip(
                                    label = item,
                                    isSelected = item == category,
                                    onClick = { onCategorySelect(item) }
                                )
                            }
                        }
                    }

                    ActionButton(
                        icon = Icons.Default.Delete,
                        text = strings.moveToTrash,
                        onClick = onTrashClick,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val background by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        label = "noteOptionChipBg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        label = "noteOptionChipContent"
    )

    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = contentColor,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 13.dp, vertical = 7.dp)
    )
}
