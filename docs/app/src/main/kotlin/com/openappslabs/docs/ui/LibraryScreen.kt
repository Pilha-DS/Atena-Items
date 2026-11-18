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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openappslabs.docs.data.ai.AiSettings
import com.openappslabs.docs.data.ai.KeyChange
import com.openappslabs.docs.data.model.Doc
import com.openappslabs.docs.data.model.DocKind
import com.openappslabs.docs.data.move.CreateIssue
import com.openappslabs.docs.data.move.CreateOutcome
import com.openappslabs.docs.data.move.FolderMoveIssue
import com.openappslabs.docs.data.move.FolderMoveOutcome
import com.openappslabs.docs.data.move.FolderPath
import com.openappslabs.docs.data.move.Inbox
import com.openappslabs.docs.data.move.MoveIssue
import com.openappslabs.docs.data.move.MoveOutcome
import com.openappslabs.docs.data.move.RemoveIssue
import com.openappslabs.docs.data.move.RemoveOutcome
import com.openappslabs.docs.data.repository.TagRemoval
import com.openappslabs.docs.data.tags.TagPaths
import com.openappslabs.docs.data.tree.PathNode
import com.openappslabs.docs.data.tree.PathTree
import com.openappslabs.docs.ui.format.docDetails
import com.openappslabs.docs.ui.format.formatTime
import com.openappslabs.docs.ui.i18n.DocsStrings
import com.openappslabs.docs.ui.i18n.LocalDocsStrings
import java.nio.file.Path

private val SIDEBAR_WIDTH = 224.dp

/**
 * The whole screen: tag tree, filter box and document list.
 *
 * It stays a dumb composable — every action goes up through a callback, so the
 * same screen runs against the real library or a fixture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    root: Path,
    state: LibraryUiState,
    onQueryChange: (String) -> Unit,
    onSortChange: (SortOrder) -> Unit,
    onSelectView: (LibraryView) -> Unit,
    onTagFilter: (String?) -> Unit,
    onRefresh: () -> Unit,
    onReadContent: () -> Unit,
    onRereadContent: () -> Unit,
    onOpenFolder: () -> Unit,
    onOpen: (Doc) -> Unit,
    onReveal: (Doc) -> Unit,
    onCopyPath: (Doc) -> Unit,
    onToggleFavorite: (Doc) -> Unit,
    onEditTags: (Doc) -> Unit,
    onToggleTag: (Long, String) -> Unit,
    onCreateTag: (Long, String) -> Unit,
    onCloseTagEditor: () -> Unit,
    onSuggestTags: (Doc) -> Unit,
    onApplySuggestions: (Long, List<String>) -> Unit,
    onCloseSuggestions: () -> Unit,
    onSuggestFolder: (Doc) -> Unit,
    onApplyFolder: (Long, String) -> Unit,
    onCloseFolderSuggestions: () -> Unit,
    onMoveDoc: (Doc) -> Unit,
    onApplyMove: (Long, String) -> Unit,
    onCloseMove: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onMoveFolder: (String, String) -> Unit,
    onImport: () -> Unit,
    onMoveToInbox: (Doc) -> Unit,
    onOpenDeleteFolder: (String) -> Unit,
    onConfirmDeleteFolder: (String) -> Unit,
    onCloseDeleteFolder: () -> Unit,
    onOpenDeleteTag: (String) -> Unit,
    onConfirmDeleteTag: (String) -> Unit,
    onCloseDeleteTag: () -> Unit,
    onOpenRenameTag: (String) -> Unit,
    onConfirmRenameTag: (String) -> Unit,
    onCloseRenameTag: () -> Unit,
    onOpenDescribe: (DescribeTarget) -> Unit,
    /**
     * Saves the description and reports back: the second argument turns the
     * result into the snackbar sentence, since only the caller knows whether the
     * field was emptied (which deletes) or filled.
     */
    onSaveDescription: (String, (Boolean) -> String) -> Unit,
    onCloseDescribe: () -> Unit,
    onOpenTagAliases: (String) -> Unit,
    onAddTagAlias: (String) -> Unit,
    onRemoveTagAlias: (String) -> Unit,
    onCloseTagAliases: () -> Unit,
    onOpenInheritTags: (String) -> Unit,
    onSaveInheritedTags: (List<String>, (Boolean) -> String) -> Unit,
    onCloseInheritTags: () -> Unit,
    onEnsureInheritTag: (String) -> Unit,
    onOpenAiSettings: () -> Unit,
    onTestAi: (AiSettings) -> Unit,
    onSaveAi: (AiSettings, KeyChange) -> Unit,
    onRemoveAiKey: (AiSettings) -> Unit,
    onCloseAiSettings: () -> Unit,
    aiConfigPath: String,
    onMessageShown: () -> Unit
) {
    val strings = LocalDocsStrings.current
    val snackbarHost = remember { SnackbarHostState() }
    var actionsOpen by remember { mutableStateOf(false) }
    var newFolderOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHost.showSnackbar(message)
        onMessageShown()
    }

    state.tagEditorDocId?.let { docId ->
        val doc = state.docs.firstOrNull { it.id == docId }
        TagPickerDialog(
            docName = doc?.name.orEmpty(),
            tags = PathTree.flatten(state.tags),
            applied = state.tagsOf(docId),
            inherited = state.inheritedOf(docId),
            strings = strings,
            onToggle = { path -> onToggleTag(docId, path) },
            onCreate = { raw -> onCreateTag(docId, raw) },
            onDismiss = onCloseTagEditor
        )
    }

    state.inheritingFolder?.let { folder ->
        FolderInheritDialog(
            folderPath = folder,
            tags = PathTree.flatten(state.tags),
            selected = state.folderInherited[folder].orEmpty(),
            strings = strings,
            onSave = { paths ->
                onSaveInheritedTags(paths) { ok ->
                    when {
                        !ok -> strings.inheritTagsFailed
                        paths.isEmpty() -> strings.inheritTagsCleared
                        else -> strings.inheritTagsSaved
                    }
                }
            },
            onDismiss = onCloseInheritTags,
            onEnsureTag = onEnsureInheritTag
        )
    }

    state.suggestionDocId?.let { docId ->
        val doc = state.docs.firstOrNull { it.id == docId }
        SuggestionDialog(
            docName = doc?.name.orEmpty(),
            suggestions = state.suggestions,
            isSuggesting = state.isSuggesting,
            note = state.suggestionNote,
            trouble = state.suggestionTrouble,
            strings = strings,
            onApply = { paths -> onApplySuggestions(docId, paths) },
            onDismiss = onCloseSuggestions,
            onlyApplied = state.suggestionOnlyApplied
        )
    }

    state.folderDocId?.let { docId ->
        val doc = state.docs.firstOrNull { it.id == docId }
        FolderSuggestionDialog(
            docName = doc?.name.orEmpty(),
            currentFolder = doc?.folder.orEmpty(),
            suggestions = state.folderSuggestions,
            // The browser walks the same tree as the sidebar: the disk, empty
            // folders included, so a folder created here is a level away.
            folders = state.folders,
            isSuggesting = state.isSuggestingFolder,
            note = state.folderNote,
            trouble = state.folderTrouble,
            strings = strings,
            onApply = { target -> onApplyFolder(docId, target) },
            onDismiss = onCloseFolderSuggestions,
            staysHere = state.folderStaysHere
        )
    }

    state.moveDocId?.let { docId ->
        val doc = state.docs.firstOrNull { it.id == docId }
        MoveDocDialog(
            docName = doc?.name.orEmpty(),
            currentFolder = doc?.folder.orEmpty(),
            folders = state.folders,
            strings = strings,
            onApply = { target -> onApplyMove(docId, target) },
            onDismiss = onCloseMove
        )
    }

    if (newFolderOpen) {
        NewFolderDialog(
            base = (state.view as? LibraryView.Folder)?.path
                ?.let { "$it/" }
                .orEmpty(),
            strings = strings,
            onCreate = { raw ->
                newFolderOpen = false
                onCreateFolder(raw)
            },
            onDismiss = { newFolderOpen = false }
        )
    }

    state.deleteFolderPath?.let { path ->
        DeleteFolderDialog(
            path = path,
            files = state.deleteFolderFiles,
            strings = strings,
            onDelete = { onConfirmDeleteFolder(path) },
            onDismiss = onCloseDeleteFolder
        )
    }

    state.deleteTagPath?.let { path ->
        // The counts come from the sidebar tree: the same number the row was
        // showing, so the dialog cannot contradict the list behind it.
        val node = PathTree.flatten(state.tags).firstOrNull { it.path == path }
        DeleteTagDialog(
            path = path,
            documents = node?.count ?: 0,
            nested = node?.let(::subtreeSize) ?: 0,
            strings = strings,
            onDelete = { onConfirmDeleteTag(path) },
            onDismiss = onCloseDeleteTag
        )
    }

    state.renameTagPath?.let { path ->
        RenameTagDialog(
            path = path,
            strings = strings,
            onRename = onConfirmRenameTag,
            onDismiss = onCloseRenameTag
        )
    }

    state.describing?.let { target ->
        val isFolder = target is DescribeTarget.Folder
        // Read from the state, not from the row that opened the dialog: the
        // dialog must show what is stored, even when opened from a menu.
        val current = if (isFolder) {
            state.folderDescriptions[target.path]
        } else {
            state.tagDescriptions[target.path]
        }.orEmpty()
        DescribeDialog(
            title = if (isFolder) strings.describeFolderTitle else strings.describeTagTitle,
            subject = if (isFolder) target.path else "#${target.path}",
            hint = if (isFolder) strings.describeFolderHint else strings.describeTagHint,
            placeholder = if (isFolder) {
                strings.describeFolderPlaceholder
            } else {
                strings.describeTagPlaceholder
            },
            current = current,
            strings = strings,
            onSave = { text ->
                onSaveDescription(text) { saved ->
                    when {
                        !saved -> strings.describeFailed
                        text.isBlank() -> strings.describeRemoved
                        else -> strings.describeSaved
                    }
                }
            },
            onDismiss = onCloseDescribe
        )
    }

    state.aliasTagPath?.let { path ->
        TagAliasesDialog(
            tagPath = path,
            aliases = state.aliasList,
            strings = strings,
            onAdd = onAddTagAlias,
            onRemove = onRemoveTagAlias,
            onDismiss = onCloseTagAliases
        )
    }

    state.aiSettings?.let { settings ->
        AiSettingsDialog(
            settings = settings,
            isTesting = state.isTestingAi,
            testOk = state.aiTestOk,
            configPath = aiConfigPath,
            strings = strings,
            onTest = onTestAi,
            onSave = onSaveAi,
            onRemoveKey = onRemoveAiKey,
            onDismiss = onCloseAiSettings
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(strings.appName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            root.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    if (state.isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).padding(end = 4.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = strings.refresh)
                        }
                    }
                    Box {
                        IconButton(onClick = { actionsOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = actionsOpen,
                            onDismissRequest = { actionsOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (state.isReading) strings.readingContent else strings.readContent
                                    )
                                },
                                enabled = !state.isReading,
                                onClick = {
                                    actionsOpen = false
                                    onReadContent()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(strings.rereadContent) },
                                enabled = !state.isReading,
                                onClick = {
                                    actionsOpen = false
                                    onRereadContent()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                                text = { Text(strings.newFolderAction) },
                                onClick = {
                                    actionsOpen = false
                                    newFolderOpen = true
                                }
                            )
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                                text = { Text(strings.aiMenu) },
                                onClick = {
                                    actionsOpen = false
                                    onOpenAiSettings()
                                }
                            )
                        }
                    }
                    if (state.isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).padding(end = 4.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                    } else {
                        // The way files enter the library: the chooser opens
                        // wherever the person keeps them, and the destination is
                        // the inbox by definition.
                        TextButton(onClick = onImport) { Text(strings.importAction) }
                    }
                    TextButton(onClick = onOpenFolder) { Text(strings.openFolder) }
                }
            )
        },
        bottomBar = { StatusBar(state, strings) },
        snackbarHost = { SnackbarHost(snackbarHost) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LibrarySidebar(
                state = state,
                strings = strings,
                onSelectView = onSelectView,
                onSelectTag = onTagFilter,
                onNewFolder = { newFolderOpen = true },
                onMoveFolder = onMoveFolder,
                onDeleteFolder = onOpenDeleteFolder,
                onDeleteTag = onOpenDeleteTag,
                onRenameTag = onOpenRenameTag,
                onDescribeFolder = { path -> onOpenDescribe(DescribeTarget.Folder(path)) },
                onDescribeTag = { path -> onOpenDescribe(DescribeTarget.Tag(path)) },
                onTagAliases = onOpenTagAliases,
                onInheritTags = onOpenInheritTags,
                modifier = Modifier.width(SIDEBAR_WIDTH)
            )
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(Modifier.fillMaxSize()) {
                SearchRow(
                    query = state.query,
                    sort = state.sort,
                    strings = strings,
                    onQueryChange = onQueryChange,
                    onSortChange = onSortChange
                )

                val hints = filterHints(state, strings)
                if (hints.isNotEmpty()) {
                    Text(
                        hints.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp)
                    )
                }

                if (state.docs.isEmpty() && !state.isScanning) {
                    EmptyState(
                        state = state,
                        strings = strings,
                        onRefresh = onRefresh
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.docs, key = { it.uuid }) { doc ->
                            DocRow(
                                doc = doc,
                                tags = state.tagsOf(doc.id),
                                strings = strings,
                                onOpen = { onOpen(doc) },
                                onReveal = { onReveal(doc) },
                                onCopyPath = { onCopyPath(doc) },
                                onToggleFavorite = { onToggleFavorite(doc) },
                                onEditTags = { onEditTags(doc) },
                                onSuggestTags = { onSuggestTags(doc) },
                                onSuggestFolder = { onSuggestFolder(doc) },
                                onMoveDoc = { onMoveDoc(doc) },
                                onMoveToInbox = { onMoveToInbox(doc) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchRow(
    query: String,
    sort: SortOrder,
    strings: DocsStrings,
    onQueryChange: (String) -> Unit,
    onSortChange: (SortOrder) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text(strings.searchPlaceholder) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
        )
        FilterChip(
            selected = sort == SortOrder.NAME,
            onClick = { onSortChange(SortOrder.NAME) },
            label = { Text(strings.sortName) }
        )
        FilterChip(
            selected = sort == SortOrder.RECENT,
            onClick = { onSortChange(SortOrder.RECENT) },
            label = { Text(strings.sortRecent) }
        )
    }
}

@Composable
private fun DocRow(
    doc: Doc,
    tags: List<String>,
    strings: DocsStrings,
    onOpen: () -> Unit,
    onReveal: () -> Unit,
    onCopyPath: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEditTags: () -> Unit,
    onSuggestTags: () -> Unit,
    onSuggestFolder: () -> Unit,
    onMoveDoc: () -> Unit,
    onMoveToInbox: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = kindIcon(doc.kind),
                contentDescription = strings.kindLabel(doc.kind),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (doc.favorite) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        doc.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    docDetails(
                        kindLabel = strings.kindLabel(doc.kind),
                        size = doc.size,
                        modifiedTime = doc.modifiedTime,
                        folder = doc.folder
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    TagChips(tags)
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(strings.open) },
                        onClick = {
                            menuOpen = false
                            onOpen()
                        }
                    )
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                if (doc.favorite) Icons.Default.StarBorder else Icons.Default.Star,
                                contentDescription = null
                            )
                        },
                        text = {
                            Text(if (doc.favorite) strings.favoriteRemove else strings.favoriteAdd)
                        },
                        onClick = {
                            menuOpen = false
                            onToggleFavorite()
                        }
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
                        text = { Text(strings.tagAction) },
                        onClick = {
                            menuOpen = false
                            onEditTags()
                        }
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                        text = { Text(strings.suggestAction) },
                        onClick = {
                            menuOpen = false
                            onSuggestTags()
                        }
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                        text = { Text(strings.suggestFolderAction) },
                        onClick = {
                            menuOpen = false
                            onSuggestFolder()
                        }
                    )
                    // Manual destination: the way out of `init` without waiting
                    // on the model, and the same picker from any other folder.
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null)
                        },
                        text = { Text(strings.moveDocAction) },
                        onClick = {
                            menuOpen = false
                            onMoveDoc()
                        }
                    )
                    // A document already in the inbox has nothing to gain from
                    // this entry, so it is not shown there.
                    if (!Inbox.holds(doc.folder)) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.Inbox, contentDescription = null) },
                            text = { Text(strings.moveToInbox) },
                            onClick = {
                                menuOpen = false
                                onMoveToInbox()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(strings.revealInFolder) },
                        onClick = {
                            menuOpen = false
                            onReveal()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(strings.copyPath) },
                        onClick = {
                            menuOpen = false
                            onCopyPath()
                        }
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 58.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

/** The active filters, in one line, so an empty result is explained. */
private fun filterHints(state: LibraryUiState, strings: DocsStrings): List<String> = buildList {
    when (val view = state.view) {
        is LibraryView.Recent -> add(strings.recentFilterHint)
        is LibraryView.Favorites -> add(strings.favoritesFilterHint)
        is LibraryView.Folder -> add(strings.folderFilterHint(view.path))
        LibraryView.All -> Unit
    }
    if (state.tagFilters.isNotEmpty()) add(strings.tagFilterHint(state.tagFilters))
}

/**
 * The sentence for a move.
 *
 * The controller reports an outcome and the screen turns it into text, which is
 * the same split the tag actions use: the state holder has no wording and the
 * wording has no filesystem in it.
 */
fun folderMoveMessage(outcome: MoveOutcome, strings: DocsStrings): String = when (outcome) {
    is MoveOutcome.Moved -> strings.folderMoved(FolderPath.of(outcome.to))
    is MoveOutcome.Rejected -> when (outcome.issue) {
        MoveIssue.ALREADY_THERE -> strings.folderMoveAlreadyThere
        MoveIssue.INVALID_FOLDER -> strings.folderMoveInvalid
        MoveIssue.SOURCE_MISSING -> strings.folderMoveSourceMissing
        MoveIssue.TARGET_TAKEN -> strings.folderMoveTargetTaken
        MoveIssue.FAILED -> strings.folderMoveFailed
    }
}

/** The sentence for moving a folder of the tree by drag-and-drop. */
fun folderTreeMoveMessage(outcome: FolderMoveOutcome, strings: DocsStrings): String = when (outcome) {
    is FolderMoveOutcome.Moved -> strings.folderTreeMoved(outcome.to)
    is FolderMoveOutcome.Rejected -> when (outcome.issue) {
        FolderMoveIssue.INVALID -> strings.folderTreeMoveInvalid
        FolderMoveIssue.NOT_FOUND -> strings.folderTreeMoveMissing
        FolderMoveIssue.ROOT -> strings.folderTreeMoveRoot
        FolderMoveIssue.INBOX -> strings.folderTreeMoveInbox
        FolderMoveIssue.INTO_SELF -> strings.folderTreeMoveIntoSelf
        FolderMoveIssue.TARGET_TAKEN -> strings.folderTreeMoveTargetTaken
        FolderMoveIssue.TOO_DEEP -> strings.folderTreeMoveTooDeep
        FolderMoveIssue.FAILED -> strings.folderTreeMoveFailed
    }
}

/** The sentence for creating a folder, same split as [folderMoveMessage]. */
fun folderCreateMessage(outcome: CreateOutcome, strings: DocsStrings): String = when (outcome) {
    is CreateOutcome.Created -> strings.folderCreated(outcome.path)
    is CreateOutcome.Rejected -> when (outcome.issue) {
        CreateIssue.INVALID -> strings.newFolderInvalid
        CreateIssue.EXISTS -> strings.newFolderExists
        CreateIssue.FAILED -> strings.newFolderFailed
    }
}

/** The sentence for deleting a folder — the outcome includes how much went. */
fun folderRemoveMessage(outcome: RemoveOutcome, strings: DocsStrings): String = when (outcome) {
    is RemoveOutcome.Removed -> strings.folderDeleted(outcome.path, outcome.files)
    is RemoveOutcome.Rejected -> when (outcome.issue) {
        RemoveIssue.INVALID -> strings.deleteFolderInvalid
        RemoveIssue.NOT_FOUND -> strings.deleteFolderMissing
        RemoveIssue.ROOT -> strings.deleteFolderRoot
        RemoveIssue.INBOX -> strings.deleteFolderInbox
        RemoveIssue.FAILED -> strings.folderDeleteFailed(outcome.files)
    }
}

/**
 * The sentence for deleting a tag.
 *
 * Zero tags removed means nothing was there: the row was stale (deleted in a
 * previous attempt) and the honest answer is the failure, not "apagada".
 */
fun tagRemoveMessage(path: String, removal: TagRemoval, strings: DocsStrings): String =
    if (removal.tags == 0) {
        strings.deleteTagFailed
    } else {
        strings.tagRemoved(TagPaths.name(path), removal.documents)
    }

/** Tags below [node], the node itself excluded. */
fun subtreeSize(node: PathNode): Int = node.children.sumOf { 1 + subtreeSize(it) }

@Composable
private fun EmptyState(
    state: LibraryUiState,
    strings: DocsStrings,
    onRefresh: () -> Unit
) {
    val filtered = state.query.isNotBlank() ||
        state.tagFilters.isNotEmpty() ||
        state.view != LibraryView.All
    val body = when {
        state.query.isNotBlank() -> strings.noResultsBody
        state.view is LibraryView.Recent -> strings.recentEmptyBody
        filtered -> strings.noResultsBody
        else -> strings.emptyBody
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (filtered) Icons.Default.Search else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (filtered) strings.noResultsTitle else strings.emptyTitle,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!filtered) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRefresh) { Text(strings.refresh) }
            }
        }
    }
}

@Composable
private fun StatusBar(state: LibraryUiState, strings: DocsStrings) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.isScanning) strings.scanning else strings.documents(state.total),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = state.error
                        ?: buildString {
                            append(
                                state.lastScan?.let { time -> strings.lastScan(formatTime(time)) }
                                    ?: strings.neverIndexed
                            )
                            (state.outcome?.moved ?: 0).takeIf { it > 0 }
                                ?.let { append(strings.renamedSuffix(it)) }
                            if (state.withText > 0) {
                                append(" · ").append(strings.textIndexed(state.withText))
                            }
                            if (state.isReading) {
                                append(" · ").append(strings.readingContent)
                            }
                            if (state.isImporting) {
                                append(" · ").append(strings.importing)
                            }
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                strings.shortcutHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun kindIcon(kind: DocKind): ImageVector = when (kind) {
    DocKind.PDF -> Icons.Default.PictureAsPdf
    DocKind.WORD -> Icons.Default.Description
    DocKind.SHEET -> Icons.Default.TableChart
    DocKind.SLIDES -> Icons.Default.Slideshow
    DocKind.TEXT -> Icons.AutoMirrored.Filled.Notes
    DocKind.MARKDOWN -> Icons.AutoMirrored.Filled.Article
    DocKind.IMAGE -> Icons.Default.Image
    DocKind.AUDIO -> Icons.Default.Audiotrack
    DocKind.VIDEO -> Icons.Default.Movie
    DocKind.ARCHIVE -> Icons.Default.FolderZip
    DocKind.CODE -> Icons.Default.Code
    DocKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}
