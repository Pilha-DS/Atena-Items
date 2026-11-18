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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openappslabs.docs.data.ai.FolderSuggestion
import com.openappslabs.docs.data.ai.ModelTrouble
import com.openappslabs.docs.data.move.FolderPath
import com.openappslabs.docs.data.tree.PathNode
import com.openappslabs.docs.data.tree.PathTree
import com.openappslabs.docs.ui.i18n.DocsStrings

/**
 * Shows where the app (or the local model) thinks the document belongs, and
 * lets the person walk the tree instead of typing a path.
 *
 * One document lives in one folder, so this is a radio choice and not a list of
 * checkboxes — the difference from the tag dialog is the shape of the answer,
 * not only the wording. The radio rows are the model's shortcuts; the browser
 * below them is the same choice made by navigation, and it is the way out of
 * `init` without knowing the folder names by heart: entering a folder is
 * already picking it, like a save dialog.
 *
 * The text field no longer asks for a path. It asks for a *name* inside the
 * folder being shown (`faculdade/2026` + `semestre-1`), which is the only thing
 * left that navigation cannot express: a folder that does not exist yet.
 *
 * Nothing is created or moved until the user confirms. A folder that does not
 * exist yet is offered like any other, marked as new: confirming it is what
 * creates it, including for a document still in `init`. The machine never
 * creates anything on its own.
 */
@Composable
fun FolderSuggestionDialog(
    docName: String,
    currentFolder: String,
    suggestions: List<FolderSuggestion>,
    /** The folder tree the sidebar shows: the disk, empty folders included. */
    folders: List<PathNode>,
    isSuggesting: Boolean,
    note: SuggestionNote?,
    trouble: ModelTrouble?,
    strings: DocsStrings,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
    /**
     * True when the model answered the folder the document is already in.
     *
     * Shown as a sentence above the rows: the model did have an opinion, and
     * hiding it makes the remaining (new) folders look like the only answer.
     */
    staysHere: Boolean = false
) {
    // The root always exists, so it is always a legal destination: it is the way
    // out of `init` and the shortcut back to the top of a deep branch.
    val rows = remember(suggestions, strings) {
        if (suggestions.any { it.target == FolderPath.ROOT }) {
            suggestions
        } else {
            suggestions + FolderSuggestion(
                target = FolderPath.ROOT,
                reason = strings.folderRootReason,
                isNew = false,
                fromModel = false
            )
        }
    }

    // One destination, two ways to reach it. `browse` is null until the person
    // chooses something: the tree is then shown at the top of the library, and
    // the destination is the model's first candidate — the tree does not steal
    // the pick from the suggestion, nor the other way around.
    var browse by remember(docName, currentFolder) { mutableStateOf<String?>(null) }
    var typed by remember(docName, currentFolder) { mutableStateOf("") }

    val level = browse ?: FolderPath.ROOT
    // Typing wins over the tree: the name is relative to the folder on screen,
    // so it is a new subfolder where the person is standing, not a full path.
    val target: String? = when {
        typed.isNotBlank() -> resolveFolderTarget(level, typed)
        browse != null -> browse
        else -> rows.firstOrNull()?.target
    }
    val typedInvalid = typed.isNotBlank() && target == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.suggestFolderTitle) },
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

                if (isSuggesting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(strings.suggestFolderThinking, style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    if (staysHere) {
                        Text(
                            strings.suggestFolderStay,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (note != null) {
                        Text(
                            when (note) {
                                SuggestionNote.NONE_FOUND -> strings.suggestFolderEmpty
                                SuggestionNote.MODEL_OFFLINE ->
                                    trouble?.let(strings::modelTrouble)
                                        ?: strings.suggestFolderModelOffline
                                SuggestionNote.FAILED -> strings.suggestFailed
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (rows.isNotEmpty()) {
                        Text(
                            strings.suggestFolderList,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column(
                            Modifier
                                .heightIn(max = 168.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            rows.forEach { suggestion ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = target == suggestion.target,
                                            onClick = {
                                                typed = ""
                                                browse = suggestion.target
                                            }
                                        )
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = target == suggestion.target,
                                        onClick = {
                                            typed = ""
                                            browse = suggestion.target
                                        }
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            if (suggestion.target.isEmpty()) {
                                                strings.folderRoot
                                            } else {
                                                suggestion.target
                                            },
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            buildString {
                                                append(suggestion.reason)
                                                if (suggestion.isNew) {
                                                    append(" · ").append(strings.suggestFolderNew)
                                                }
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.size(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.size(8.dp))
                Text(
                    strings.chooseFolderTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(2.dp))
                FolderBrowser(
                    folders = folders,
                    level = level,
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
                        // A destination that does not exist yet is not an error:
                        // it will be created on confirm, so it gets the accent
                        // color instead of the muted one.
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
                // While the model is still thinking the first candidate is not a
                // choice yet, so only an explicit pick (tree or name) may move
                // the file before the answer arrives.
                enabled = target != null && (!isSuggesting || browse != null || typed.isNotBlank()),
                onClick = { target?.let(onApply) }
            ) {
                Text(strings.suggestFolderApply)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.close) }
        }
    )
}
