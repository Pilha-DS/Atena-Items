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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openappslabs.jotter.data.model.Note
import com.openappslabs.jotter.data.model.NoteFormat
import com.openappslabs.jotter.ui.theme.rememberJotterHaptics
import com.openappslabs.jotter.ui.i18n.LocalStrings

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NoteCard(
    note: Note,
    date: String,
    isGridView: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onOptionsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptics = rememberJotterHaptics()
    val strings = LocalStrings.current

    val cardModifier = remember(isGridView) {
        if (isGridView) {
            Modifier.aspectRatio(1f)
        } else {
            Modifier.fillMaxWidth().height(140.dp)
        }
    }

    val isEncryptionLocked = note.isEncrypted && note.content.isBlank()

    val format = remember(note.format) { NoteFormat.fromKey(note.format) }

    val displayContent = remember(note.content, note.isLocked, isEncryptionLocked, isGridView, strings) {
        when {
            isEncryptionLocked -> strings.unlockToRead
            note.isLocked && !isGridView -> strings.lockedNote
            else -> note.content
        }
    }

    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val contentColor = remember(note.isLocked, isEncryptionLocked, onSurfaceVariant) {
        if (note.isLocked || isEncryptionLocked) onSurfaceVariant.copy(alpha = 0.5f) else onSurfaceVariant
    }

    val isCategoryBlank = note.category.isBlank()
    val categoryText = remember(note.category, isCategoryBlank, strings) {
        if (isCategoryBlank) strings.uncategorizedUpper else note.category.uppercase(strings.locale)
    }

    val chipContainerColor = if (isCategoryBlank) {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    Card(
        modifier = modifier
            .then(cardModifier)
            .combinedClickable(
                onClick = {
                    haptics.tick()
                    onClick()
                },
                onLongClick = {
                    haptics.tick()
                    onLongClick()
                }
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = when {
                            note.title.isNotEmpty() -> note.title
                            note.isEncrypted -> strings.encryptedNote
                            else -> strings.untitled
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (note.isPinned) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = strings.pinnedLabel,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        if (note.isPinned && (note.isLocked && !isGridView)) {
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        if (note.isLocked && !isGridView) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = strings.lockedLabel,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        if (note.isEncrypted) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = strings.encryptedNote,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        if (format.isRich) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = format.shortLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        NoteSettingsButton(onClick = onOptionsClick)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (note.isLocked && isGridView) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = strings.lockedLabel,
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else {
                    Text(
                        text = displayContent,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                        maxLines = if (isGridView) 4 else 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 20.sp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(chipContainerColor)
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = categoryText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isCategoryBlank) contentColor.copy(alpha = 0.7f) else onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}
/**
 * Gear that opens the note settings straight from the list. It is a child of
 * the card's clickable surface, so its own click is consumed here and never
 * opens the note.
 */
@Composable
private fun NoteSettingsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = rememberJotterHaptics()
    val strings = LocalStrings.current
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                haptics.click()
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = strings.noteSettingsDescription,
            tint = tint.copy(alpha = 0.85f),
            modifier = Modifier.size(16.dp)
        )
    }
}
