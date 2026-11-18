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

package com.openappslabs.docs

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.openappslabs.docs.data.ai.AiSettings
import com.openappslabs.docs.data.ai.FileAiController
import com.openappslabs.docs.data.ai.FolderSuggester
import com.openappslabs.docs.data.ai.ModelSlot
import com.openappslabs.docs.data.ai.TagSuggester
import com.openappslabs.docs.data.move.DocMover
import com.openappslabs.docs.data.move.FolderCreator
import com.openappslabs.docs.data.move.FolderMover
import com.openappslabs.docs.data.move.FolderRemover
import com.openappslabs.docs.data.move.Inbox
import com.openappslabs.docs.data.move.InboxImport
import com.openappslabs.docs.data.model.Doc
import com.openappslabs.docs.data.repository.DescriptionRepository
import com.openappslabs.docs.data.repository.DocIndexer
import com.openappslabs.docs.data.repository.DocTextIndexer
import com.openappslabs.docs.data.repository.FolderTagRepository
import com.openappslabs.docs.data.repository.TagRepository
import com.openappslabs.docs.data.scan.FolderScanner
import com.openappslabs.docs.data.source.DatabaseFactory
import com.openappslabs.docs.data.text.SystemTextHarvester
import com.openappslabs.docs.platform.DocsPaths
import com.openappslabs.docs.platform.FileActions
import com.openappslabs.docs.platform.FilePicker
import com.openappslabs.docs.platform.FileTimes
import com.openappslabs.docs.platform.IpcBridge
import com.openappslabs.docs.platform.Xdg
import com.openappslabs.docs.ui.LibraryController
import com.openappslabs.docs.ui.LibraryScreen
import com.openappslabs.docs.ui.folderCreateMessage
import com.openappslabs.docs.ui.folderMoveMessage
import com.openappslabs.docs.ui.folderRemoveMessage
import com.openappslabs.docs.ui.folderTreeMoveMessage
import com.openappslabs.docs.ui.i18n.PtBrDocsStrings
import com.openappslabs.docs.ui.tagRemoveMessage
import com.openappslabs.docs.ui.theme.DocsTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Dimension

/** How often the toggle file is checked; one click must feel instant. */
private const val TOGGLE_POLL_MS = 160L

fun main() {
    Xdg.ensureDirectories()
    System.setProperty("awt.useSystemAAFontSettings", "on")

    // The app is a singleton: a second launch (or the shortcut) becomes a
    // show/hide request for the window that is already running.
    if (!IpcBridge.acquireSingleInstanceLock()) {
        IpcBridge.requestToggle()
        return
    }
    IpcBridge.publishRuntimeDir()

    // The library root is created here so the folder always exists: an empty
    // directory is the only side effect this stage has on the user's tree. The
    // inbox below it is the same deal — `init/` is where unorganized files land,
    // and it costs nothing to have it waiting there.
    val libraryRoot = DocsPaths.ensureLibraryRoot()
    Inbox.ensure(libraryRoot)
    val strings = PtBrDocsStrings
    // Read once, outside the window. The dialog edits provider/model/address and
    // writes them back; the key line is carried over untouched.
    val aiSlot = ModelSlot(AiSettings.read(Xdg.aiConfigFile))
    val aiSettings = aiSlot.settings
    val aiController = FileAiController(Xdg.aiConfigFile, aiSlot)

    application {
        var isVisible by remember { mutableStateOf(true) }
        val windowState = rememberWindowState(
            size = DpSize(1100.dp, 720.dp),
            position = WindowPosition(Alignment.Center)
        )
        val scope = rememberCoroutineScope()

        // Built once per window. The database is the index, not the documents:
        // losing it costs a rescan and nothing else.
        val controller = remember {
            val database = DatabaseFactory.create()
            val docDao = database.docDao()
            val textDao = database.docTextDao()
            val harvester = SystemTextHarvester()
            // The sentences about folders and tags live in the index database,
            // next to the tags themselves, so the AI reads and the sidebar edits
            // the same text.
            val descriptions = DescriptionRepository(
                folders = database.folderDescriptionDao(),
                tags = database.tagDao()
            )
            val tagRepository = TagRepository(
                dao = database.tagDao(),
                folderTags = database.folderTagDao(),
                aliases = database.tagAliasDao()
            )
            val folderTags = FolderTagRepository(
                dao = database.folderTagDao(),
                tags = tagRepository
            )
            val textIndexer = DocTextIndexer(libraryRoot, textDao, harvester)
            // The same reader for the batch and for a single document: when the
            // user asks for a suggestion on a file the batch has not reached,
            // this is what makes the model see the content instead of "no text".
            val readNow: suspend (Doc) -> String? = { doc -> textIndexer.readOne(doc) }
            val createdTime: suspend (Doc) -> Long? = { doc ->
                FileTimes.created(libraryRoot.resolve(doc.relPath))
            }
            LibraryController(
                root = libraryRoot,
                dao = docDao,
                tagDao = database.tagDao(),
                tags = tagRepository,
                indexer = DocIndexer(libraryRoot, docDao, textDao),
                scope = scope,
                textDao = textDao,
                textIndexer = textIndexer,
                suggester = TagSuggester(
                    textDao = textDao,
                    tagDao = database.tagDao(),
                    // A function, not a client: the AI dialog can swap the model
                    // while the window is open, and a JRE without
                    // java.net.http (a trimmed runtime) must cost the model,
                    // not the window — the suggester then works from the text.
                    modelSource = { aiSlot.model },
                    textSource = readNow,
                    createdTimeSource = createdTime
                ),
                folderSuggester = FolderSuggester(
                    textDao = textDao,
                    dao = docDao,
                    modelSource = { aiSlot.model },
                    // The real tree, empty folders included: the index only has
                    // a row for a folder that already holds a document, so a
                    // folder the user just created was invisible to the model
                    // and got proposed as "new".
                    folderSource = { FolderScanner.scan(libraryRoot) },
                    // What the user said each folder is for, read on every
                    // suggestion so a description typed a minute ago is already
                    // followed.
                    descriptionSource = { descriptions.folderNotes() },
                    textSource = readNow,
                    createdTimeSource = createdTime
                ),
                mover = DocMover(libraryRoot, docDao),
                folderCreator = FolderCreator(libraryRoot),
                folderMover = FolderMover(
                    root = libraryRoot,
                    dao = docDao,
                    descriptions = database.folderDescriptionDao(),
                    folderTags = database.folderTagDao()
                ),
                folderRemover = FolderRemover(libraryRoot),
                descriptions = descriptions,
                folderTags = folderTags,
                importer = InboxImport(libraryRoot),
                ai = aiController,
                ocrAvailable = harvester.ocrAvailable
            )
        }

        // First run indexes the folder; afterwards this is a cheap diff.
        LaunchedEffect(Unit) { controller.refresh() }

        // A key in a file the whole machine can read is worth one sentence.
        LaunchedEffect(Unit) {
            if (aiSettings.keyFileExposed) controller.report(strings.apiKeyExposed)
        }

        LaunchedEffect(Unit) {
            var lastToken = IpcBridge.toggleToken()
            while (true) {
                delay(TOGGLE_POLL_MS)
                val token = IpcBridge.toggleToken()
                if (token == lastToken) continue
                lastToken = token
                // A stale file written before this launch is not a click.
                if (!IpcBridge.isFreshToggle(token)) continue
                IpcBridge.acknowledgeToggle(token)
                isVisible = !isVisible
                // Coming back to the window is the moment to notice files the
                // user dropped in the folder meanwhile.
                if (isVisible) controller.refresh()
            }
        }

        Window(
            onCloseRequest = ::exitApplication,
            visible = isVisible,
            state = windowState,
            title = strings.appName,
            icon = painterResource("icons/docs.png")
        ) {
            LaunchedEffect(window) {
                window.minimumSize = Dimension(880, 560)
            }
            DocsTheme {
                LibraryScreen(
                    root = libraryRoot,
                    state = controller.state,
                    onQueryChange = controller::search,
                    onSortChange = controller::sortBy,
                    onSelectView = controller::selectView,
                    onTagFilter = controller::filterByTag,
                    onRefresh = controller::refresh,
                    onReadContent = controller::readContent,
                    onRereadContent = controller::rereadContent,
                    onOpenFolder = {
                        if (!FileActions.open(libraryRoot)) controller.report(strings.openFolderFailed)
                    },
                    onOpen = { doc ->
                        if (FileActions.open(libraryRoot.resolve(doc.relPath))) {
                            controller.recordVisit(doc.id)
                        } else {
                            controller.report(strings.openFileFailed)
                        }
                    },
                    onReveal = { doc ->
                        if (!FileActions.reveal(libraryRoot.resolve(doc.relPath))) {
                            controller.report(strings.openFolderFailed)
                        }
                    },
                    onCopyPath = { doc ->
                        val message = if (FileActions.copyPath(libraryRoot.resolve(doc.relPath))) {
                            strings.copiedPath
                        } else {
                            strings.copyPathFailed
                        }
                        controller.report(message)
                    },
                    onEditTags = { doc -> controller.openTagEditor(doc.id) },
                    onSuggestTags = { doc -> controller.openSuggestions(doc.id) },
                    onApplySuggestions = { docId, paths ->
                        controller.applySuggestions(docId, paths) { applied ->
                            strings.suggestionsApplied(applied)
                        }
                    },
                    onCloseSuggestions = controller::closeSuggestions,
                    onSuggestFolder = { doc -> controller.openFolderSuggestions(doc.id) },
                    onApplyFolder = { docId, target ->
                        controller.applyFolderSuggestion(docId, target) { outcome ->
                            folderMoveMessage(outcome, strings)
                        }
                    },
                    onCloseFolderSuggestions = controller::closeFolderSuggestions,
                    onMoveDoc = { doc -> controller.openMove(doc.id) },
                    onApplyMove = { docId, target ->
                        controller.applyMove(docId, target) { outcome ->
                            folderMoveMessage(outcome, strings)
                        }
                    },
                    onCloseMove = controller::closeMove,
                    onCreateFolder = { raw ->
                        controller.createFolder(raw) { outcome ->
                            folderCreateMessage(outcome, strings)
                        }
                    },
                    onMoveFolder = { from, toParent ->
                        controller.moveFolder(from, toParent) { outcome ->
                            folderTreeMoveMessage(outcome, strings)
                        }
                    },
                    onImport = {
                        // The chooser is modal and native; the controller does
                        // the moving on the IO dispatcher. An empty answer is a
                        // cancelled dialog and must stay silent.
                        val files = FilePicker.chooseFiles(strings.importTitle, libraryRoot)
                        if (files.isNotEmpty()) {
                            controller.importToInbox(files) { report ->
                                strings.importReport(
                                    imported = report.imported,
                                    renamed = report.renamed,
                                    skipped = report.skipped,
                                    failed = report.failed
                                )
                            }
                        }
                    },
                    onMoveToInbox = { doc ->
                        controller.moveToInbox(doc.id) { outcome ->
                            folderMoveMessage(outcome, strings)
                        }
                    },
                    onOpenDeleteFolder = controller::openDeleteFolder,
                    onConfirmDeleteFolder = { path ->
                        controller.deleteFolder(path) { outcome ->
                            folderRemoveMessage(outcome, strings)
                        }
                    },
                    onCloseDeleteFolder = controller::closeDeleteFolder,
                    onOpenDeleteTag = controller::openDeleteTag,
                    onConfirmDeleteTag = { path ->
                        controller.deleteTag(path) { removal ->
                            tagRemoveMessage(path, removal, strings)
                        }
                    },
                    onCloseDeleteTag = controller::closeDeleteTag,
                    onOpenRenameTag = controller::openRenameTag,
                    onConfirmRenameTag = { raw ->
                        controller.renameTag(
                            raw,
                            strings::tagRenamed,
                            strings.renameTagFailed
                        )
                    },
                    onCloseRenameTag = controller::closeRenameTag,
                    onOpenDescribe = controller::openDescribe,
                    onSaveDescription = { text, message ->
                        controller.saveDescription(text, message)
                    },
                    onCloseDescribe = controller::closeDescribe,
                    onOpenTagAliases = controller::openAliases,
                    onAddTagAlias = { raw ->
                        controller.addAlias(
                            raw,
                            strings.tagAliasesInvalid,
                            strings.tagAliasesFailed
                        )
                    },
                    onRemoveTagAlias = { alias ->
                        controller.removeAlias(alias, strings.tagAliasesFailed)
                    },
                    onCloseTagAliases = controller::closeAliases,
                    onOpenInheritTags = controller::openInheritTags,
                    onSaveInheritedTags = { paths, message ->
                        controller.saveInheritedTags(paths, message)
                    },
                    onCloseInheritTags = controller::closeInheritTags,
                    onEnsureInheritTag = { path ->
                        controller.ensureTag(
                            path,
                            strings.tagInvalid,
                            strings::inheritTagCreated,
                            strings.tagApplyFailed
                        )
                    },
                    onOpenAiSettings = controller::openAiSettings,
                    onTestAi = controller::testAi,
                    onSaveAi = { candidate, key ->
                        controller.saveAi(candidate, key, strings.aiSaved, strings.aiSaveFailed)
                    },
                    onRemoveAiKey = { candidate ->
                        controller.removeAiKey(candidate, strings.aiKeyRemoved, strings.aiSaveFailed)
                    },
                    onCloseAiSettings = controller::closeAiSettings,
                    aiConfigPath = Xdg.aiConfigFile.toString(),
                    onToggleFavorite = controller::toggleFavorite,
                    onToggleTag = { docId, path ->
                        controller.toggleTag(docId, path, strings.tagApplyFailed)
                    },
                    onCreateTag = { docId, raw ->
                        controller.createTag(
                            docId,
                            raw,
                            strings.tagInvalid,
                            strings.tagApplyFailed
                        )
                    },
                    onCloseTagEditor = controller::closeTagEditor,
                    onMessageShown = controller::messageShown
                )
            }
        }
    }
}
