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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openappslabs.docs.data.move.FolderPath
import com.openappslabs.docs.data.tree.PathNode
import com.openappslabs.docs.data.tree.PathTree
import com.openappslabs.docs.ui.i18n.DocsStrings

/**
 * Picks a destination folder for one document — no model, no suggestion list.
 *
 * This is the way out of `init` when the person already knows where the file
 * goes: walk the same tree the sidebar shows, optionally type a name for a
 * folder that does not exist yet, and confirm. Creating and moving still wait
 * for that click; the dialog never writes on its own.
 */
@Composable
fun MoveDocDialog(
    docName: String,
    currentFolder: String,
    folders: List<PathNode>,
    strings: DocsStrings,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Start at the library root so leaving `init` is one navigation away, not
    // a search for the current waiting-room path.
    var browse by remember(docName, currentFolder) { mutableStateOf(FolderPath.ROOT) }
    var typed by remember(docName, currentFolder) { mutableStateOf("") }

    val target: String? = when {
        typed.isNotBlank() -> resolveFolderTarget(browse, typed)
        else -> browse
    }
    val typedInvalid = typed.isNotBlank() && target == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.moveDocTitle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    docName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    strings.suggestFolderCurrent(currentFolder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    strings.moveDocHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(8.dp))
                FolderBrowser(
                    folders = folders,
                    level = browse,
                    strings = strings,
                    onNavigate = { path ->
                        typed = ""
                        browse = path
                    }
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = typedInvalid,
                    placeholder = { Text(strings.chooseFolderNewPlaceholder) },
                    textStyle = MaterialTheme.typography.bodySmall
                )
                target?.let { destination ->
                    Spacer(Modifier.size(6.dp))
                    val exists = PathTree.contains(folders, destination)
                    Text(
                        buildString {
                            append(
                                strings.chooseFolderDestination(
                                    if (destination.isEmpty()) strings.folderRoot else destination
                                )
                            )
                            if (!exists) append(" · ").append(strings.suggestFolderNew)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (exists) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = target != null,
                onClick = { target?.let(onApply) }
            ) {
                Text(strings.moveDocApply)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.close) }
        }
    )
}
