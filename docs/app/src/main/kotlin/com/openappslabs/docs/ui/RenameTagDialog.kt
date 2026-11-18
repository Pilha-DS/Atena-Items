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
import com.openappslabs.docs.data.tags.TagPaths
import com.openappslabs.docs.ui.i18n.DocsStrings

/**
 * Renames one tag (and everything under it).
 *
 * The path is the identifier: changing `nota-fiscal` to `nf` keeps every
 * document assignment and every search alias, because those hang on the row id.
 */
@Composable
fun RenameTagDialog(
    path: String,
    strings: DocsStrings,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(path) { mutableStateOf(path) }
    val normalized = TagPaths.normalize(draft)
    val unchanged = normalized == TagPaths.normalize(path)
    val canSave = normalized != null && !unchanged

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.renameTagTitle) },
        text = {
            Column {
                Text(
                    strings.renameTagBody(path),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text(strings.renameTagPlaceholder) },
                    isError = draft.isNotBlank() && normalized == null,
                    supportingText = {
                        if (draft.isNotBlank() && normalized == null) {
                            Text(strings.tagInvalid)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { normalized?.let(onRename) },
                enabled = canSave
            ) {
                Text(strings.renameTagConfirm)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.close) }
        }
    )
}
