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
import androidx.compose.ui.unit.dp
import com.openappslabs.docs.data.move.FolderPath
import com.openappslabs.docs.ui.i18n.DocsStrings

/**
 * Creates a folder — or a whole branch — inside the library.
 *
 * The text is a path with `/`, the same spelling as a tag: `faculdade/2026`
 * creates both levels, which is what makes creating subfolders one action
 * instead of two dialogs. What the user types is checked here so the button
 * never sends something the data layer would refuse for a reason already
 * visible on screen.
 *
 * [base] is pre-filled from the folder being looked at, so creating a subfolder
 * of what is on screen is typing the name and pressing create.
 */
@Composable
fun NewFolderDialog(
    base: String,
    strings: DocsStrings,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(base) { mutableStateOf(base) }

    val normalized = draft.takeIf { it.isNotBlank() }?.let { FolderPath.normalize(it) }
    val invalid = draft.isNotBlank() && (normalized == null || normalized.isEmpty())
    val canCreate = draft.isNotBlank() && !invalid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.newFolderTitle) },
        text = {
            Column {
                Text(
                    strings.newFolderHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = invalid,
                    placeholder = { Text(strings.newFolderPlaceholder) },
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                if (invalid) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        strings.newFolderInvalid,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canCreate, onClick = { onCreate(draft) }) {
                Text(strings.newFolderCreate)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.close) }
        }
    )
}
