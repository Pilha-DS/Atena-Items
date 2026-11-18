/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Atena Docs
 *
 * Atena Docs is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Atena Docs is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Atena Docs.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.docs.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.openappslabs.docs.ui.i18n.DocsStrings

/**
 * Asks for the folder name typed out before deleting it — files and all.
 *
 * The typed confirmation is the whole guard: deleting a folder of the library
 * removes the documents from the disk, and no other click in this app destroys
 * anything. Typing the name is deliberate in a way a second button is not, and
 * it forces the person to read what the sentence says is inside.
 *
 * [files] is null while the disk is being counted, and the field stays disabled
 * until then: confirming a number that is not on screen yet would defeat the
 * purpose of showing it.
 */
@Composable
fun DeleteFolderDialog(
    path: String,
    files: Int?,
    strings: DocsStrings,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val name = path.substringAfterLast('/')
    var typed by remember(path) { mutableStateOf("") }

    val matches = typed.trim() == name
    val ready = files != null && matches

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.deleteFolderTitle) },
        text = {
            Column {
                Text(path, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    files?.let { strings.deleteFolderBody(path, it) }
                        ?: strings.deleteFolderCounting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = files != null,
                    isError = typed.isNotBlank() && !matches,
                    placeholder = { Text(name) },
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    strings.deleteFolderType(name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = onDelete) {
                Text(
                    strings.deleteFolderConfirm,
                    color = if (ready) MaterialTheme.colorScheme.error else Color.Unspecified
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.close) }
        }
    )
}
