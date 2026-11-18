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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openappslabs.docs.data.move.FolderPath
import com.openappslabs.docs.data.move.Inbox
import com.openappslabs.docs.data.tree.PathNode
import com.openappslabs.docs.data.tree.PathTree
import com.openappslabs.docs.ui.i18n.DocsStrings

/**
 * One step of the breadcrumb: what the user reads and where it leads.
 *
 * [path] is the folder as the library spells it, so clicking an old crumb is
 * the same navigation as clicking a row — no separate "up" concept.
 */
data class FolderCrumb(val label: String, val path: String)

/** The segments from the root to [path]; empty for the root itself. */
fun folderTrail(path: String): List<FolderCrumb> {
    val normalized = FolderPath.normalize(path) ?: return emptyList()
    if (normalized.isEmpty()) return emptyList()
    val crumbs = ArrayList<FolderCrumb>()
    var accumulated = FolderPath.ROOT
    for (segment in normalized.split(FolderPath.SEPARATOR)) {
        accumulated = FolderPath.join(accumulated, segment)
        crumbs += FolderCrumb(label = segment, path = accumulated)
    }
    return crumbs
}

/**
 * Where the document goes, given where the browser is standing and what was
 * typed in its field.
 *
 * The typed name is relative to [browse] on purpose: navigating to
 * `faculdade/2026` and typing `semestre-1` is the way to create a folder
 * without writing a path. Blank means "the folder I am looking at", so entering
 * a folder is already the choice.
 */
fun resolveFolderTarget(browse: String, typed: String): String? {
    val base = FolderPath.normalize(browse) ?: return null
    val name = typed.trim()
    if (name.isEmpty()) return base
    return FolderPath.normalize(FolderPath.join(base, name))
}

/**
 * A file-manager style picker over the library tree.
 *
 * Clicking a folder enters it, and the folder being shown is the destination —
 * the same promise as a save dialog, and the reason nothing has to be typed: a
 * document in `init` is moved by walking to where it belongs. The breadcrumb
 * goes back without a separate button, and a folder with no subfolder still
 * works as a destination (that is how the last level is chosen).
 */
@Composable
fun FolderBrowser(
    folders: List<PathNode>,
    level: String,
    strings: DocsStrings,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val crumbs = folderTrail(level)
    val children = PathTree.childrenOf(folders, level)

    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Crumb(
                label = strings.folderRoot,
                current = crumbs.isEmpty(),
                onClick = { onNavigate(FolderPath.ROOT) }
            )
            crumbs.forEach { crumb ->
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Crumb(
                    label = crumb.label,
                    current = crumb.path == level,
                    onClick = { onNavigate(crumb.path) }
                )
            }
        }
        Spacer(Modifier.size(4.dp))
        if (children.isEmpty()) {
            Text(
                strings.chooseFolderEmpty,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 132.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                children.forEach { node ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { onNavigate(node.path) }
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            // The inbox is a waiting room, not a shelf: the icon
                            // says so before the name is read.
                            if (Inbox.holds(node.path)) Icons.Default.Inbox else Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            node.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (node.count > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                node.count.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = strings.chooseFolderEnter,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Crumb(label: String, current: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
        color = if (current) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
        },
        maxLines = 1,
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 3.dp)
    )
}
