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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.openappslabs.docs.data.ai.ModelTrouble
import com.openappslabs.docs.data.ai.TagSuggestion
import com.openappslabs.docs.ui.i18n.DocsStrings

/**
 * Shows what the app (or the local model) thinks the document is about.
 *
 * Nothing is applied here: every line is a checkbox and the user confirms.
 * Tagging is cheap to undo but was expensive to build, so the machine proposes
 * and the person decides.
 *
 * A suggestion that does not exist yet is offered like any other, marked as new:
 * confirming it is what creates the tag, including for a document still in
 * `init`. The machine never creates anything on its own.
 */
@Composable
fun SuggestionDialog(
    docName: String,
    suggestions: List<TagSuggestion>,
    isSuggesting: Boolean,
    note: SuggestionNote?,
    trouble: ModelTrouble?,
    strings: DocsStrings,
    onApply: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    onlyApplied: Boolean = false
) {
    val selectable = remember(suggestions) { suggestions.map { it.path } }
    var checked by remember(selectable) {
        mutableStateOf(selectable.associateWith { true })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.suggestTitle) },
        text = {
            Column {
                Text(
                    docName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.size(12.dp))

                when {
                    isSuggesting -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(strings.suggestThinking, style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> {
                        if (note != null) {
                            Text(
                                when (note) {
                                    SuggestionNote.NONE_FOUND -> strings.suggestEmpty
                                    // The generic sentence only when the client
                                    // could not say what went wrong.
                                    SuggestionNote.MODEL_OFFLINE ->
                                        trouble?.let(strings::modelTrouble) ?: strings.suggestModelOffline
                                    SuggestionNote.FAILED -> strings.suggestFailed
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (onlyApplied) {
                            Text(
                                strings.suggestOnlyApplied,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        val items = suggestions
                        if (items.isNotEmpty()) {
                            Column(
                                Modifier
                                    .heightIn(max = 260.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                items.forEach { suggestion ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = checked[suggestion.path] == true,
                                            onCheckedChange = { value ->
                                                checked = checked + (suggestion.path to value)
                                            }
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                "#${suggestion.path}",
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                buildString {
                                                    append(suggestion.reason)
                                                    if (suggestion.isNew) {
                                                        append(" · ").append(strings.suggestTagNew)
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
                }
            }
        },
        confirmButton = {
            val selected = suggestions
                .map { it.path }
                .filter { checked[it] == true }
            TextButton(
                enabled = !isSuggesting && selected.isNotEmpty(),
                onClick = { onApply(selected) }
            ) {
                Text(strings.suggestApply)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.close) }
        }
    )
}
