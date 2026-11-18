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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openappslabs.docs.data.move.FolderPath
import com.openappslabs.docs.data.move.Inbox
import com.openappslabs.docs.data.tree.PathTree
import com.openappslabs.docs.ui.i18n.DocsStrings
import java.awt.datatransfer.StringSelection

/** In-app payload for dragging a library folder from the sidebar grip. */
private const val FOLDER_DND_PREFIX = "atena-docs/folder:"

private fun folderDragPayload(path: String): String = "$FOLDER_DND_PREFIX$path"

@OptIn(ExperimentalComposeUiApi::class)
private fun folderDragPath(event: DragAndDropEvent): String? {
    val text = (event.dragData() as? DragData.Text)?.readText() ?: return null
    if (!text.startsWith(FOLDER_DND_PREFIX)) return null
    return text.removePrefix(FOLDER_DND_PREFIX).takeIf { it.isNotEmpty() }
}

/**
 * Navigation, left of the list: the three ready-made views, the folder tree of
 * the library and the tag tree.
 *
 * All three live in one scrolling list because they are all "what am I looking
 * at" and a fixed header for each would eat a small window. Clicking the
 * current choice again goes back to everything — no "clear" button needed.
 *
 * Folder moves use a narrow drag grip on the left of each folder row: the rest
 * of the row stays a plain click, so selecting never starts a transfer.
 */
@Composable
fun LibrarySidebar(
    state: LibraryUiState,
    strings: DocsStrings,
    onSelectView: (LibraryView) -> Unit,
    onSelectTag: (String?) -> Unit,
    onNewFolder: () -> Unit,
    /**
     * Moves [source] folder into [targetParent] (empty = library root). Fired
     * by dropping a folder onto another folder or onto "Todos".
     */
    onMoveFolder: (source: String, targetParent: String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onDeleteTag: (String) -> Unit,
    onRenameTag: (String) -> Unit,
    onDescribeFolder: (String) -> Unit,
    onDescribeTag: (String) -> Unit,
    onTagAliases: (String) -> Unit,
    onInheritTags: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // The folder tree starts closed: it is navigation, not a dump of the disk.
    // Tags stay open — there are fewer of them, and picking one is the point.
    var expandedFolders by remember { mutableStateOf(emptySet<String>()) }
    val selectedFolder = (state.view as? LibraryView.Folder)?.path

    // Selecting a folder has to show it. Creating `faculdade/2026` selects the
    // new folder; with the parent closed the selection would be invisible.
    LaunchedEffect(selectedFolder) {
        if (selectedFolder != null) {
            expandedFolders = expandedFolders + PathTree.ancestors(selectedFolder)
        }
    }

    val folders = PathTree.visible(state.folders, expandedFolders)
    val tags = PathTree.flatten(state.tags)

    Surface(
        modifier = modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item { SectionHeader(strings.navigateTitle) }

            item {
                FilterRow(
                    selected = state.view is LibraryView.All,
                    icon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = strings.allDocuments,
                    count = state.total,
                    indent = 0,
                    onClick = { onSelectView(LibraryView.All) },
                    // Dropping on "Todos" puts the folder back at the library root.
                    dropParent = FolderPath.ROOT,
                    onMoveFolder = onMoveFolder
                )
            }
            item {
                FilterRow(
                    selected = state.view is LibraryView.Recent,
                    icon = { Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = strings.recent,
                    count = null,
                    indent = 0,
                    onClick = { onSelectView(LibraryView.Recent) }
                )
            }
            item {
                FilterRow(
                    selected = state.view is LibraryView.Favorites,
                    icon = { Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = strings.favorites,
                    count = state.favoriteCount,
                    indent = 0,
                    onClick = { onSelectView(LibraryView.Favorites) }
                )
            }

            item {
                SectionHeader(
                    text = strings.foldersTitle,
                    action = strings.newFolderAction,
                    onAction = onNewFolder
                )
            }
            if (folders.isEmpty()) {
                item {
                    Text(
                        strings.noFolders,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 12.dp, bottom = 8.dp)
                    )
                }
            } else {
                items(folders, key = { "folder:${it.path}" }) { node ->
                    val expanded = node.path in expandedFolders
                    val isInbox = node.path.equals(Inbox.FOLDER, ignoreCase = true)
                    FilterRow(
                        selected = selectedFolder == node.path,
                        icon = {
                            Icon(
                                // The inbox is not a destination like the others:
                                // it is where unorganized files wait.
                                if (Inbox.holds(node.path)) Icons.Default.Inbox else Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        label = node.name,
                        // An empty folder is shown on purpose; a zero would only
                        // repeat what the missing documents already say.
                        count = node.count.takeIf { it > 0 },
                        indent = node.depth,
                        // Only a branch gets an arrow: a leaf has nothing to open
                        // and still keeps the icon column aligned.
                        toggle = if (node.children.isEmpty()) {
                            null
                        } else {
                            TreeToggle(
                                expanded = expanded,
                                label = if (expanded) strings.collapseFolder else strings.expandFolder,
                                onClick = {
                                    expandedFolders = if (expanded) {
                                        expandedFolders - node.path
                                    } else {
                                        expandedFolders + node.path
                                    }
                                }
                            )
                        },
                        onClick = { onSelectView(LibraryView.Folder(node.path)) },
                        // The inbox itself is recreated by every scan, so
                        // deleting it would look like the click did nothing, and
                        // a description of it would never reach the model (the
                        // inbox is not a destination). Its subfolders are
                        // ordinary folders.
                        onDescribe = if (isInbox) null else ({ onDescribeFolder(node.path) }),
                        describeLabel = strings.describeAction,
                        onInherit = if (isInbox) null else ({ onInheritTags(node.path) }),
                        inheritLabel = strings.inheritTagsAction,
                        onDelete = if (isInbox) null else ({ onDeleteFolder(node.path) }),
                        deleteLabel = strings.deleteAction,
                        moreLabel = strings.moreActions,
                        description = state.folderDescriptions[node.path],
                        // Grip starts the drag; init has neither grip nor drop.
                        dragPath = if (isInbox) null else node.path,
                        dragHandleLabel = strings.dragFolderHandle,
                        dropParent = if (isInbox) null else node.path,
                        onMoveFolder = onMoveFolder
                    )
                }
            }

            item { SectionHeader(strings.tagsTitle) }
            if (tags.isEmpty()) {
                item {
                    Text(
                        strings.noTags,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 12.dp, bottom = 8.dp)
                    )
                }
            } else {
                items(tags, key = { "tag:${it.path}" }) { node ->
                    FilterRow(
                        selected = node.path in state.tagFilters,
                        icon = {
                            Icon(
                                Icons.AutoMirrored.Filled.Label,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        label = node.name,
                        count = node.count,
                        indent = node.depth,
                        onClick = { onSelectTag(node.path) },
                        onDescribe = { onDescribeTag(node.path) },
                        describeLabel = strings.describeAction,
                        onAliases = { onTagAliases(node.path) },
                        aliasesLabel = strings.tagAliasesAction,
                        onRename = { onRenameTag(node.path) },
                        renameLabel = strings.renameTagAction,
                        onDelete = { onDeleteTag(node.path) },
                        deleteLabel = strings.deleteAction,
                        moreLabel = strings.moreActions,
                        description = state.tagDescriptions[node.path]
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    text: String,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 10.dp, end = 6.dp, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (action != null && onAction != null) {
            // Icon-only, so the header stays one line; the tooltip is the
            // content description, read by the accessibility services.
            IconButton(onClick = onAction, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.CreateNewFolder,
                    contentDescription = action,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** One arrow of the folder tree: what it shows and what it does when clicked. */
private data class TreeToggle(
    val expanded: Boolean,
    val label: String,
    val onClick: () -> Unit
)

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun FilterRow(
    selected: Boolean,
    icon: @Composable () -> Unit,
    label: String,
    count: Int?,
    indent: Int,
    onClick: () -> Unit,
    toggle: TreeToggle? = null,
    /**
     * Opens the description field of this folder or tag.
     *
     * Lives in the same `⋮` menu as the delete action: two icon buttons in a
     * 224 dp row would leave the name with no room.
     */
    onDescribe: (() -> Unit)? = null,
    describeLabel: String = "",
    /** Opens the search-alias dialog for a tag. */
    onAliases: (() -> Unit)? = null,
    aliasesLabel: String = "",
    /** Opens the rename dialog for a tag. */
    onRename: (() -> Unit)? = null,
    renameLabel: String = "",
    /** Opens the inherited-tags dialog for a folder. */
    onInherit: (() -> Unit)? = null,
    inheritLabel: String = "",
    /**
     * Deletes the row's folder or tag, when it has one.
     *
     * Shares the `⋮` menu with [onDescribe]. The handle is always visible so
     * the action can be found without guessing that the row reacts to the
     * mouse; it is only dimmed until the row is hovered or selected.
     */
    onDelete: (() -> Unit)? = null,
    deleteLabel: String = "",
    moreLabel: String = "",
    /** What the user said this folder or tag is for, shown under the name. */
    description: String? = null,
    /**
     * When set, a narrow grip on the left starts an immediate drag of that
     * folder. The rest of the row stays a click.
     */
    dragPath: String? = null,
    dragHandleLabel: String = "",
    /**
     * When set (including empty for the library root), dropping a folder here
     * moves it into this parent. Tags leave both null.
     */
    dropParent: String? = null,
    onMoveFolder: ((source: String, targetParent: String) -> Unit)? = null
) {
    var dropHover by remember { mutableStateOf(false) }
    val background = when {
        dropHover -> MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        else -> MaterialTheme.colorScheme.surface
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val showActions =
        (onDescribe != null || onAliases != null || onInherit != null ||
            onRename != null || onDelete != null) &&
            (selected || hovered)
    var menuOpen by remember { mutableStateOf(false) }
    // Grip (optional) + arrow + icon + gap: the description sits under the name.
    val textStart = (if (dragPath != null) 16.dp else 0.dp) + 44.dp + (indent * 14).dp

    val dropTarget = remember(dropParent, onMoveFolder) {
        if (dropParent == null || onMoveFolder == null) {
            null
        } else {
            object : DragAndDropTarget {
                override fun onEntered(event: DragAndDropEvent) {
                    dropHover = true
                }

                override fun onExited(event: DragAndDropEvent) {
                    dropHover = false
                }

                override fun onEnded(event: DragAndDropEvent) {
                    dropHover = false
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    dropHover = false
                    val source = folderDragPath(event) ?: return false
                    // Dropped onto itself or back onto the same parent: swallow
                    // without a snackbar — the grip was used, nothing moved.
                    if (source == dropParent) return true
                    val destination = FolderPath.join(
                        dropParent,
                        source.substringAfterLast(FolderPath.SEPARATOR)
                    )
                    if (destination == source) return true
                    onMoveFolder(source, dropParent)
                    return true
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .background(background, RoundedCornerShape(6.dp))
            .hoverable(interaction)
            .then(
                if (dropTarget != null) {
                    Modifier.dragAndDropTarget(
                        shouldStartDragAndDrop = { event -> folderDragPath(event) != null },
                        target = dropTarget
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 2.dp + (indent * 14).dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (dragPath != null) {
                // Narrow grip only: immediate system drag, no fight with click.
                Icon(
                    Icons.Default.DragIndicator,
                    contentDescription = dragHandleLabel,
                    modifier = Modifier
                        .size(14.dp)
                        .dragAndDropSource {
                            DragAndDropTransferData(
                                transferable = DragAndDropTransferable(
                                    StringSelection(folderDragPayload(dragPath))
                                ),
                                supportedActions = listOf(DragAndDropTransferAction.Move)
                            )
                        },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                )
                Spacer(Modifier.width(2.dp))
            }
            // Fixed slot, filled or not: folders with and without subfolders keep
            // their icons in the same column.
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .then(if (toggle == null) Modifier else Modifier.clickable(onClick = toggle.onClick)),
                contentAlignment = Alignment.Center
            ) {
                if (toggle != null) {
                    Icon(
                        if (toggle.expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = toggle.label,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            icon()
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // The count steps aside while the row is hovered: the menu is what
            // the person is reaching for then, and three small things in a
            // 224 dp row do not fit.
            if (!showActions && count != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onDescribe != null || onAliases != null || onInherit != null ||
                onRename != null || onDelete != null
            ) {
                // Always drawn, only muted: a menu that appears out of nowhere
                // on hover is a menu nobody finds. The row is the place the
                // person is already looking at, so the handle lives there.
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = moreLabel,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = if (showActions) 1f else 0.45f
                            )
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        if (onDescribe != null) {
                            DropdownMenuItem(
                                text = { Text(describeLabel) },
                                onClick = {
                                    menuOpen = false
                                    onDescribe()
                                }
                            )
                        }
                        if (onAliases != null) {
                            DropdownMenuItem(
                                text = { Text(aliasesLabel) },
                                onClick = {
                                    menuOpen = false
                                    onAliases()
                                }
                            )
                        }
                        if (onInherit != null) {
                            DropdownMenuItem(
                                text = { Text(inheritLabel) },
                                onClick = {
                                    menuOpen = false
                                    onInherit()
                                }
                            )
                        }
                        if (onRename != null) {
                            DropdownMenuItem(
                                text = { Text(renameLabel) },
                                onClick = {
                                    menuOpen = false
                                    onRename()
                                }
                            )
                        }
                        if (onDelete != null) {
                            DropdownMenuItem(
                                text = {
                                    Text(deleteLabel, color = MaterialTheme.colorScheme.error)
                                },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }
        }
        if (!description.isNullOrBlank()) {
            // One line, muted: the note is there to be read at a glance and to
            // be found again, not to push the tree off the screen.
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = textStart, end = 8.dp, bottom = 6.dp)
            )
        }
    }
}

/** Compact `#tag` chips under a document name; three at most, then a +N. */
@Composable
fun TagChips(paths: List<String>, modifier: Modifier = Modifier) {
    if (paths.isEmpty()) return
    val shown = paths.take(3)
    Row(modifier = modifier) {
        shown.forEach { path ->
            Text(
                "#$path",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        if (paths.size > shown.size) {
            Text(
                "+${paths.size - shown.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
