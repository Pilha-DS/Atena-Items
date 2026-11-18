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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openappslabs.jotter.data.model.NoteFormat
import kotlinx.coroutines.CoroutineScope
import com.openappslabs.jotter.ui.i18n.LocalStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteActionSheet(
    sheetState: SheetState,
    scope: CoroutineScope,
    createdDate: String,
    modifiedDate: String,
    wordCount: Int,
    charCount: Int,
    category: String,
    isPinned: Boolean,
    isLocked: Boolean,
    isEncrypted: Boolean,
    openInEditMode: Boolean?,
    format: NoteFormat,
    isOpenInEditAvailable: Boolean,
    onPinClick: () -> Unit,
    onLockClick: () -> Unit,
    onEncryptionClick: () -> Unit,
    onShareClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onCategoryClick: () -> Unit,
    onOpenInEditModeChange: (Boolean?) -> Unit,
    onFormatChange: (NoteFormat) -> Unit,
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
                .padding(top = 16.dp, bottom = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ActionSection {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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

                        Row(modifier = Modifier.fillMaxWidth()) {
                            ActionButton(
                                icon = Icons.Default.Share,
                                text = strings.share,
                                onClick = onShareClick,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            ActionButton(
                                icon = Icons.Default.Delete,
                                text = strings.delete,
                                onClick = onDeleteClick,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (isOpenInEditAvailable) {
                            OpenModeSelector(
                                openInEditMode = openInEditMode,
                                onSelect = onOpenInEditModeChange
                            )
                        }

                        NoteFormatSelector(
                            format = format,
                            onSelect = onFormatChange
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            NoteInfoCard(
                createdDate = createdDate,
                modifiedDate = modifiedDate,
                wordCount = wordCount,
                charCount = charCount,
                category = category,
                onCategoryClick = onCategoryClick,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

@Composable
private fun ActionSection(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            content()
        }
    }
}

@Composable
fun ActionButton(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    isActive: Boolean = false
) {
    val isError = color == MaterialTheme.colorScheme.error
    val containerColor = when {
        isActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        isActive -> MaterialTheme.colorScheme.primary
        isError -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    val iconBadgeBg = when {
        isActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
    }
    val iconTint = when {
        isActive -> MaterialTheme.colorScheme.primary
        isError -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconBadgeBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = text,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}

/**
 * Per-note override of the global "default open mode". "Default" keeps the
 * note following the app-wide preference, "Edit"/"Read" pin it to one mode.
 */
@Composable
fun OpenModeSelector(
    openInEditMode: Boolean?,
    onSelect: (Boolean?) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = strings.openIn,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OpenModeOption(
                icon = Icons.Default.Settings,
                label = strings.openInDefault,
                isSelected = openInEditMode == null,
                onClick = { onSelect(null) },
                modifier = Modifier.weight(1f)
            )
            OpenModeOption(
                icon = Icons.Default.Create,
                label = strings.openInEdit,
                isSelected = openInEditMode == true,
                onClick = { onSelect(true) },
                modifier = Modifier.weight(1f)
            )
            OpenModeOption(
                icon = Icons.Default.Visibility,
                label = strings.openInRead,
                isSelected = openInEditMode == false,
                onClick = { onSelect(false) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun OpenModeOption(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}
