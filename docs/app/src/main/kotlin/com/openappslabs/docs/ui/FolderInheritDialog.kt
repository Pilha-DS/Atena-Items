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

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.openappslabs.docs.data.tags.TagPaths
import com.openappslabs.docs.data.tree.PathNode
import com.openappslabs.docs.ui.i18n.DocsStrings

/**
 * Picks which tags a folder passes to every document under it.
 *
 * The inheritance links themselves are written only on *Salvar*. Adding a tag
 * that does not exist yet calls [onEnsureTag] immediately, so it shows up in
 * the sidebar even if the dialog is closed without saving — otherwise "criar"
 * looked like a no-op and tags such as `termo` never appeared.
 *
 * The list is filterable and scrolls with a visible bar: year tags alone
 * (`2018`…`2027`) push the rest below the fold, and a dialog without search
 * looked like those later tags did not exist.
 */
@Composable
fun FolderInheritDialog(
    folderPath: String,
    tags: List<PathNode>,
    selected: List<String>,
    strings: DocsStrings,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    /** Creates [path] in the tag index right away (normalized already). */
    onEnsureTag: (String) -> Unit = {}
) {
    // Only the folder keys the draft: if `selected` is replaced while the
    // dialog is open (Room refresh after ensure), resetting would drop tags
    // the user just typed and had not saved yet.
    var chosen by remember(folderPath) { mutableStateOf(selected.toSet()) }
    var draft by remember { mutableStateOf("") }
    var filter by remember(folderPath) { mutableStateOf("") }
    // Tags typed here that are not in the tree yet still have to survive until
    // save — otherwise "criar e marcar" would vanish from the checklist.
    var extras by remember(folderPath) { mutableStateOf(emptyList<String>()) }
    val known = remember(tags) { tags.map { it.path }.toSet() }
    val listed = remember(tags, extras, chosen, filter) {
        TagPickList.prepare(
            nodes = tags,
            extras = extras.filter { it !in known },
            selected = chosen,
            filter = filter
        )
    }
    val listState = rememberLazyListState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(strings.inheritTagsTitle)
                Text(
                    folderPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column {
                Text(
                    strings.inheritTagsHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(strings.tagListFilter) },
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                if (listed.nodes.isEmpty() && listed.extras.isEmpty()) {
                    Text(
                        if (filter.isBlank()) strings.noTags else strings.tagListFilterEmpty,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 12.dp)
                        ) {
                            items(listed.nodes, key = { it.path }) { node ->
                                InheritRow(
                                    path = node.path,
                                    depth = node.depth,
                                    checked = node.path in chosen,
                                    onToggle = {
                                        chosen = if (node.path in chosen) {
                                            chosen - node.path
                                        } else {
                                            chosen + node.path
                                        }
                                    }
                                )
                            }
                            items(listed.extras, key = { "new:$it" }) { path ->
                                InheritRow(
                                    path = path,
                                    depth = TagPaths.depth(path),
                                    checked = path in chosen,
                                    onToggle = {
                                        chosen = if (path in chosen) {
                                            chosen - path
                                        } else {
                                            chosen + path
                                        }
                                    },
                                    isNew = true,
                                    newLabel = strings.inheritTagNew
                                )
                            }
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(listState),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(strings.newTagPlaceholder) },
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = {
                            val path = TagPaths.normalize(draft) ?: return@Button
                            // Clear the filter first: a filter like "contrato"
                            // would hide a brand-new "termo" and look like the
                            // create button did nothing.
                            filter = ""
                            extras = (extras + path).distinct()
                            chosen = chosen + path
                            draft = ""
                            onEnsureTag(path)
                        },
                        enabled = draft.isNotBlank()
                    ) {
                        Text(strings.inheritTagAdd)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(chosen.sorted()) }) {
                Text(strings.inheritTagsSave)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.close) }
        }
    )
}

@Composable
private fun InheritRow(
    path: String,
    depth: Int,
    checked: Boolean,
    onToggle: () -> Unit,
    isNew: Boolean = false,
    newLabel: String = ""
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(
            path,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(start = (depth * 12).dp)
                .weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (isNew) {
            Text(
                newLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * Orders and filters a flat tag list for pickers.
 *
 * Checked paths come first (so a long year list does not hide the current
 * selection), then the rest alphabetically. [filter] matches any path
 * substring, case-insensitive.
 */
internal object TagPickList {

    data class Prepared(
        val nodes: List<PathNode>,
        val extras: List<String>
    )

    fun prepare(
        nodes: List<PathNode>,
        extras: List<String>,
        selected: Set<String>,
        filter: String
    ): Prepared {
        val query = filter.trim()
        val visibleNodes = if (query.isEmpty()) {
            nodes
        } else {
            // Checked rows stay visible under a filter: otherwise creating
            // "termo" while filtering "contrato" makes the new tag vanish.
            nodes.filter {
                it.path in selected || it.path.contains(query, ignoreCase = true)
            }
        }
        // Extras are always pending selections the user just typed — never
        // hide them behind the filter.
        val (checked, rest) = visibleNodes.partition { it.path in selected }
        return Prepared(
            nodes = checked + rest,
            extras = extras.sorted()
        )
    }
}
