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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.openappslabs.docs.data.model.Doc
import com.openappslabs.docs.data.model.DocIdPath
import com.openappslabs.docs.data.model.DocStatus
import com.openappslabs.docs.data.model.DocTagRow
import com.openappslabs.docs.data.model.Tag
import com.openappslabs.docs.data.ai.AiController
import com.openappslabs.docs.data.ai.AiSettings
import com.openappslabs.docs.data.ai.FolderSuggester
import com.openappslabs.docs.data.ai.FolderSuggestion
import com.openappslabs.docs.data.ai.KeyChange
import com.openappslabs.docs.data.ai.ModelTrouble
import com.openappslabs.docs.data.ai.SuggestionResult
import com.openappslabs.docs.data.ai.TagSuggester
import com.openappslabs.docs.data.ai.TagSuggestion
import com.openappslabs.docs.data.move.CreateIssue
import com.openappslabs.docs.data.move.CreateOutcome
import com.openappslabs.docs.data.move.DocMover
import com.openappslabs.docs.data.move.FolderCreator
import com.openappslabs.docs.data.move.FolderMoveOutcome
import com.openappslabs.docs.data.move.FolderMover
import com.openappslabs.docs.data.move.FolderMoveIssue
import com.openappslabs.docs.data.move.FolderPath
import com.openappslabs.docs.data.move.FolderRemover
import com.openappslabs.docs.data.move.Inbox
import com.openappslabs.docs.data.move.InboxImport
import com.openappslabs.docs.data.move.ImportReport
import com.openappslabs.docs.data.move.MoveIssue
import com.openappslabs.docs.data.move.MoveOutcome
import com.openappslabs.docs.data.move.RemoveIssue
import com.openappslabs.docs.data.move.RemoveOutcome
import com.openappslabs.docs.data.repository.DescriptionRepository
import com.openappslabs.docs.data.repository.DocIndexer
import com.openappslabs.docs.data.repository.DocTextIndexer
import com.openappslabs.docs.data.repository.FolderTagRepository
import com.openappslabs.docs.data.repository.IndexOutcome
import com.openappslabs.docs.data.repository.TagRemoval
import com.openappslabs.docs.data.repository.TagRepository
import com.openappslabs.docs.data.repository.TextOutcome
import com.openappslabs.docs.data.scan.FolderScanner
import com.openappslabs.docs.data.search.UniversalSearch
import com.openappslabs.docs.data.source.DocDao
import com.openappslabs.docs.data.source.DocTextDao
import com.openappslabs.docs.data.source.TagDao
import com.openappslabs.docs.data.source.sqlLikePattern
import com.openappslabs.docs.data.source.sqlLikeUnder
import com.openappslabs.docs.data.tags.FolderTagInherit
import com.openappslabs.docs.data.tags.TagAliases
import com.openappslabs.docs.data.tags.TagPaths
import com.openappslabs.docs.data.tree.PathNode
import com.openappslabs.docs.data.tree.PathTree
import com.openappslabs.docs.ui.format.formatDateTime
import com.openappslabs.docs.ui.format.formatSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

enum class SortOrder { NAME, RECENT }

/** Why a suggestion dialog has nothing to offer; the screen turns it into text. */
enum class SuggestionNote { NONE_FOUND, MODEL_OFFLINE, FAILED }

/**
 * What the description dialog is editing.
 *
 * A sealed target instead of two nullable paths: the dialog has one field and
 * one save button, and "is it a folder or a tag" is a question the controller
 * must answer exactly once — writing a folder note for a tag path would store a
 * sentence that no prompt ever reads.
 */
sealed interface DescribeTarget {
    val path: String

    data class Folder(override val path: String) : DescribeTarget
    data class Tag(override val path: String) : DescribeTarget
}

/** Ceiling for one content-reading session: the next launch continues. */
private const val DOCS_PER_SESSION = 200

/** Inputs of the tag-tree rebuild: tags, assignments, folders and inheritance. */
private data class TagTreeSnapshot(
    val tags: List<Tag>,
    val assignmentRows: List<DocTagRow>,
    val idPaths: List<DocIdPath>,
    val byFolder: Map<String, List<String>>
)

data class LibraryUiState(
    val docs: List<Doc> = emptyList(),
    val total: Int = 0,
    val favoriteCount: Int = 0,
    /** Documents with text read out of the content, fed by the text table. */
    val withText: Int = 0,
    val isReading: Boolean = false,
    val textOutcome: TextOutcome? = null,
    val query: String = "",
    val sort: SortOrder = SortOrder.NAME,
    val view: LibraryView = LibraryView.All,
    /**
     * Selected tag paths for the list filter.
     *
     * Empty means every document. Several paths mean AND: the document must
     * carry each of them (or a descendant), which is how "mes-10" + "nota-fiscal"
     * narrows the search without a second click clearing the first.
     */
    val tagFilters: Set<String> = emptySet(),
    val tags: List<PathNode> = emptyList(),
    val folders: List<PathNode> = emptyList(),
    /** Tags applied to each document id, in path order (explicit only). */
    val assignments: Map<Long, List<String>> = emptyMap(),
    /**
     * Tags each folder declares for inheritance (not yet walked up the chain).
     * Keyed by folder path; [tagsOf] resolves the ancestors.
     */
    val folderInherited: Map<String, List<String>> = emptyMap(),
    /** Folder of every present document, keyed by id — inheritance needs it. */
    val docFolders: Map<Long, String> = emptyMap(),
    /** Folder whose inherited-tag dialog is open, if any. */
    val inheritingFolder: String? = null,
    /** Document whose tag picker is open, if any. */
    val tagEditorDocId: Long? = null,
    /** Document whose tag suggestions are being shown, if any. */
    val suggestionDocId: Long? = null,
    val suggestions: List<TagSuggestion> = emptyList(),
    val isSuggesting: Boolean = false,
    /** Why there are no suggestions: no model, nothing found, failure. */
    val suggestionNote: SuggestionNote? = null,
    /** What the model answered instead of a suggestion, when it failed. */
    val suggestionTrouble: ModelTrouble? = null,
    /** True when the model only repeated tags the document already carries. */
    val suggestionOnlyApplied: Boolean = false,
    /** Document whose folder suggestions are being shown, if any. */
    val folderDocId: Long? = null,
    val folderSuggestions: List<FolderSuggestion> = emptyList(),
    val isSuggestingFolder: Boolean = false,
    val folderNote: SuggestionNote? = null,
    /** Same detail as [suggestionTrouble], for the folder dialog. */
    val folderTrouble: ModelTrouble? = null,
    /** True when the model says the document is already in the right folder. */
    val folderStaysHere: Boolean = false,
    /** Document whose manual move dialog is open — tree only, no model. */
    val moveDocId: Long? = null,
    /**
     * Folder waiting for the typed confirmation before being deleted.
     *
     * [deleteFolderFiles] is how many entries the dialog found inside it: null
     * while the disk is being counted, and the number is what makes the
     * sentence concrete instead of a vague warning.
     */
    val deleteFolderPath: String? = null,
    val deleteFolderFiles: Int? = null,
    /** Tag waiting for the confirmation before being deleted with its subtree. */
    val deleteTagPath: String? = null,
    /** Tag waiting to be renamed. */
    val renameTagPath: String? = null,
    /** Folder or tag whose description is being edited, if any. */
    val describing: DescribeTarget? = null,
    /** Tag whose search aliases are being edited, if any. */
    val aliasTagPath: String? = null,
    /** Current aliases of [aliasTagPath], newest edit reflected after each write. */
    val aliasList: List<String> = emptyList(),
    /** What the user said each folder is for, keyed by folder path. */
    val folderDescriptions: Map<String, String> = emptyMap(),
    /** What the user said each tag means, keyed by tag path. */
    val tagDescriptions: Map<String, String> = emptyMap(),
    /** True while the file chooser's answer is being moved into the inbox. */
    val isImporting: Boolean = false,
    /** The AI dialog is open, with these settings as the starting point. */
    val aiSettings: AiSettings? = null,
    /** True while the "test connection" of that dialog is running. */
    val isTestingAi: Boolean = false,
    /** Result of the last test: null = not tested yet. */
    val aiTestOk: Boolean? = null,
    val isScanning: Boolean = false,
    val lastScan: Long? = null,
    val outcome: IndexOutcome? = null,
    val error: String? = null,
    val message: String? = null
) {
    /** Tags inherited from the document's folder and its ancestors. */
    fun inheritedOf(docId: Long): List<String> {
        val folder = docFolders[docId] ?: return emptyList()
        return FolderTagInherit.resolve(folder, folderInherited)
    }

    /** Explicit tags plus inherited ones, sorted, no duplicates. */
    fun tagsOf(docId: Long): List<String> =
        (assignments[docId].orEmpty() + inheritedOf(docId)).distinct().sorted()

    fun isInherited(docId: Long, path: String): Boolean = path in inheritedOf(docId)
}

/** One combined filter: what to show, in which order, under which tags. */
private data class Listing(
    val query: String,
    val sort: SortOrder,
    val tags: Set<String>,
    val view: LibraryView
)

/** Intermediate pack for the universal-search combine (keeps arity down). */
private data class SearchPack(
    val docs: List<Doc>,
    val assignments: Map<Long, List<String>>,
    val folders: Map<Long, String>,
    val byFolder: Map<String, List<String>>,
    val texts: Map<Long, String>
)

/**
 * State holder of the library screen.
 *
 * Holds no Compose UI and no repository construction: the database, the DAOs
 * and the repositories arrive from outside, so the same controller runs under
 * `:app:run`, under the packaged app image, and in a test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryController(
    val root: Path,
    private val dao: DocDao,
    private val tagDao: TagDao,
    private val tags: TagRepository,
    private val indexer: DocIndexer,
    private val scope: CoroutineScope,
    /** Text layer: absent in tests that only care about names and tags. */
    private val textDao: DocTextDao? = null,
    private val textIndexer: DocTextIndexer? = null,
    /** Tag suggestions from the text (and from a local model, when configured). */
    private val suggester: TagSuggester? = null,
    /** Folder suggestions, from the same model when it is there. */
    private val folderSuggester: FolderSuggester? = null,
    /** The only writer of the user's tree; absent in tests that never move. */
    private val mover: DocMover? = null,
    /** Creates folders the user typed in the window; absent in tests. */
    private val folderCreator: FolderCreator? = null,
    /** Moves a folder (and its tree) inside the library; absent in tests. */
    private val folderMover: FolderMover? = null,
    /** The only code that deletes a folder from the user's tree; absent in tests. */
    private val folderRemover: FolderRemover? = null,
    /**
     * The sentences the user writes about folders and tags. Absent in tests
     * that never describe anything; the dialogs then have nothing to open.
     */
    private val descriptions: DescriptionRepository? = null,
    /**
     * Tags a folder passes to documents under it. Absent in tests that never
     * touch inheritance.
     */
    private val folderTags: FolderTagRepository? = null,
    /** Files chosen on the computer, moved into the inbox; absent in tests. */
    private val importer: InboxImport? = null,
    /**
     * Settings the window may change (provider, model, address). Absent in
     * tests, and it never carries the key: see [AiController].
     */
    private val ai: AiController? = null,
    /** False when Tesseract is not installed: the footer says so once. */
    val ocrAvailable: Boolean = true
) {

    var state by mutableStateOf(LibraryUiState())
        private set

    private val queryFlow = MutableStateFlow("")
    private val sortFlow = MutableStateFlow(SortOrder.NAME)
    private val tagFlow = MutableStateFlow<Set<String>>(emptySet())
    private val viewFlow = MutableStateFlow<LibraryView>(LibraryView.All)

    /**
     * Every folder that exists on disk, empty ones included.
     *
     * Kept apart from the index because a folder has no row there: it becomes
     * visible only when a document lands inside. The tree is the union of this
     * list and the folders derived from the indexed paths.
     */
    private val folderDirsFlow = MutableStateFlow<List<String>>(emptyList())

    init {
        // Any change of the four filters cancels the previous query instead of
        // letting every keystroke finish against the database.
        scope.launch {
            val inheritedFlow = folderTags?.observeByFolder() ?: flowOf(emptyMap())
            combine(queryFlow, sortFlow, tagFlow, viewFlow, ::Listing)
                .flatMapLatest { listing ->
                    observeListing(listing, inheritedFlow)
                }
                .collect { docs -> state = state.copy(docs = docs) }
        }
        scope.launch {
            dao.observeCount(DocStatus.PRESENT).collect { total ->
                state = state.copy(total = total)
            }
        }
        scope.launch {
            dao.observeFavoriteCount(DocStatus.PRESENT).collect { favorites ->
                state = state.copy(favoriteCount = favorites)
            }
        }
        // How much of the library already has searchable content.
        scope.launch {
            textDao?.observeWithText()?.collect { withText ->
                state = state.copy(withText = withText)
            }
        }
        // The folder tree is the disk tree: a folder exists even when it is
        // empty, which is the whole point of creating one from the window. The
        // counts still come from the index, since only files have rows.
        scope.launch {
            combine(dao.observePaths(DocStatus.PRESENT), folderDirsFlow) { paths, dirs ->
                paths to dirs
            }.collect { (paths, dirs) ->
                val counts = HashMap<String, Int>()
                for (relPath in paths) {
                    val folder = relPath.substringBeforeLast('/', "")
                    if (folder.isNotEmpty()) counts[folder] = (counts[folder] ?: 0) + 1
                }
                // `init` is part of the library by construction: it is created
                // at launch and must be reachable before the first file lands.
                val all = (dirs + counts.keys + Inbox.FOLDER).distinct()
                state = state.copy(folders = PathTree.build(all, counts))
            }
        }
        // Before the first scan: the sidebar shows what is already on disk.
        scope.launch { reloadFolders() }
        // The tag tree needs the tags, the explicit assignments, the folder of
        // each document and the inheritance map: PathTree rolls parents up, and
        // a document under a folder that inherits a tag must count once even
        // when it also carries the tag by hand.
        scope.launch {
            val inheritedFlow = folderTags?.observeByFolder() ?: flowOf(emptyMap())
            combine(
                tagDao.observeAll(),
                tagDao.observeAssignments(DocStatus.PRESENT),
                dao.observeIdPaths(DocStatus.PRESENT),
                inheritedFlow
            ) { all, assignmentRows, idPaths, byFolder ->
                TagTreeSnapshot(all, assignmentRows, idPaths, byFolder)
            }.collect { snap ->
                val assignments = snap.assignmentRows.groupBy({ it.docId }, { it.path })
                val docFolders = snap.idPaths.associate { it.id to it.folder }
                val exact = HashMap<String, MutableSet<Long>>()
                for ((docId, paths) in assignments) {
                    for (path in paths) {
                        exact.getOrPut(path) { mutableSetOf() }.add(docId)
                    }
                }
                for (row in snap.idPaths) {
                    for (path in FolderTagInherit.resolve(row.folder, snap.byFolder)) {
                        exact.getOrPut(path) { mutableSetOf() }.add(row.id)
                    }
                }
                val exactCounts = snap.tags.associate { tag ->
                    tag.path to (exact[tag.path]?.size ?: 0)
                }
                state = state.copy(
                    tags = PathTree.build(snap.tags.map { it.path }, exactCounts),
                    tagDescriptions = snap.tags
                        .filter { it.description.isNotBlank() }
                        .associate { it.path to it.description },
                    assignments = assignments,
                    docFolders = docFolders,
                    folderInherited = snap.byFolder
                )
            }
        }
        // What the user said each folder is for: the sidebar shows it and the
        // suggestion prompt follows it.
        scope.launch {
            descriptions?.observeFolders()?.collect { notes ->
                state = state.copy(folderDescriptions = notes)
            }
        }
    }

    fun search(query: String) {
        queryFlow.value = query
        state = state.copy(query = query)
    }

    fun sortBy(order: SortOrder) {
        sortFlow.value = order
        state = state.copy(sort = order)
    }

    /**
     * Toggles [path] in the tag filter.
     *
     * Clicking an unselected tag adds it (AND with the ones already on); clicking
     * it again removes only that one. [path] null clears every tag filter.
     */
    fun filterByTag(path: String?) {
        val next = when {
            path == null -> emptySet()
            path in state.tagFilters -> state.tagFilters - path
            else -> state.tagFilters + path
        }
        tagFlow.value = next
        state = state.copy(tagFilters = next)
    }

    /**
     * Switches the list view.
     *
     * [LibraryView.Recent] also picks the chronological order — that is what
     * "recentes" means, and making the user set it twice would be rude.
     */
    fun selectView(view: LibraryView) {
        val next = if (view == state.view && view !is LibraryView.Folder) {
            LibraryView.All
        } else {
            view
        }
        viewFlow.value = next
        if (next is LibraryView.Recent) {
            sortFlow.value = SortOrder.RECENT
            state = state.copy(view = next, sort = SortOrder.RECENT)
        } else {
            state = state.copy(view = next)
        }
    }

    fun toggleFavorite(doc: Doc) {
        scope.launch {
            dao.setFavorite(doc.id, !doc.favorite)
        }
    }

    /**
     * Records that the person opened [docId] from the window.
     *
     * Feeds the fuzzy frecency; a failed open must not call this.
     */
    fun recordVisit(docId: Long) {
        scope.launch {
            runCatching { dao.recordVisit(docId, System.currentTimeMillis()) }
        }
    }

    /** Re-reads the folder. Concurrent calls are ignored: one scan at a time. */
    fun refresh() {
        if (state.isScanning) return
        state = state.copy(isScanning = true, error = null)
        scope.launch {
            // The inbox is the entry point of the library: if it was deleted by
            // hand, it is back before the walk.
            runCatching { Inbox.ensure(root) }
            reloadFolders()
            val result = runCatching { indexer.scanNow() }
            state = result.fold(
                onSuccess = { outcome ->
                    state.copy(
                        isScanning = false,
                        lastScan = System.currentTimeMillis(),
                        outcome = outcome
                    )
                },
                onFailure = { failure ->
                    state.copy(isScanning = false, error = failure.message ?: failure::class.simpleName)
                }
            )
            // A new or edited file has no text yet: start reading right away so
            // the search covers it before the user asks.
            readContent()
        }
    }

    /**
     * Reads the content of the documents still waiting, batch by batch.
     *
     * Stops as soon as a batch brings nothing new — a file that cannot be read
     * (unsupported type, broken PDF) is stored as such and must not turn into
     * a loop. One session reads at most [DOCS_PER_SESSION] documents, so a
     * folder full of scans does not keep the disk busy all afternoon.
     */
    fun readContent() {
        val contentIndexer = textIndexer ?: return
        if (state.isReading) return
        state = state.copy(isReading = true)
        scope.launch {
            var outcome: TextOutcome? = null
            var remaining = DOCS_PER_SESSION
            while (remaining > 0) {
                val size = minOf(remaining, DocTextIndexer.DEFAULT_BATCH)
                val batch = runCatching { contentIndexer.indexPending(size) }.getOrNull() ?: break
                outcome = batch
                remaining -= batch.read
                if (batch.read < size) break
            }
            state = state.copy(isReading = false, textOutcome = outcome)
        }
    }

    /**
     * Forgets every stored text and reads the library again.
     *
     * The way out of a stored "nothing inside": the user installed Tesseract
     * after the first pass, or changed the tools. It is explicit because it is
     * expensive — OCR of every image again.
     */
    fun rereadContent() {
        if (state.isReading) return
        val textDao = textDao ?: return
        scope.launch {
            runCatching { textDao.forgetAll() }
            state = state.copy(textOutcome = null)
            readContent()
        }
    }

    fun openTagEditor(docId: Long) {
        state = state.copy(tagEditorDocId = docId)
    }

    fun closeTagEditor() {
        state = state.copy(tagEditorDocId = null)
    }

    /**
     * Asks for tag suggestions for one document.
     *
     * The dialog opens immediately, with the previous list cleared, and fills
     * in when the answer arrives — asking a local model can take seconds.
     */
    fun openSuggestions(docId: Long) {
        val suggester = suggester ?: return
        val doc = state.docs.firstOrNull { it.id == docId } ?: return
        state = state.copy(
            suggestionDocId = docId,
            suggestions = emptyList(),
            isSuggesting = true,
            suggestionNote = null,
            suggestionTrouble = null,
            suggestionOnlyApplied = false
        )
        scope.launch {
            val result: SuggestionResult? = runCatching { suggester.suggest(doc) }.getOrNull()
            if (state.suggestionDocId != docId) return@launch
            state = when {
                result == null -> state.copy(
                    isSuggesting = false,
                    suggestionNote = SuggestionNote.FAILED,
                    suggestionOnlyApplied = false
                )
                result.isEmpty -> state.copy(
                    isSuggesting = false,
                    suggestionNote = if (result.modelUnavailable) {
                        SuggestionNote.MODEL_OFFLINE
                    } else {
                        SuggestionNote.NONE_FOUND
                    },
                    suggestionTrouble = result.trouble,
                    suggestionOnlyApplied = result.onlyApplied
                )
                else -> state.copy(
                    isSuggesting = false,
                    suggestions = result.suggestions,
                    suggestionNote = if (result.modelUnavailable) SuggestionNote.MODEL_OFFLINE else null,
                    suggestionTrouble = result.trouble,
                    suggestionOnlyApplied = result.onlyApplied
                )
            }
        }
    }

    fun closeSuggestions() {
        state = state.copy(
            suggestionDocId = null,
            suggestions = emptyList(),
            suggestionNote = null,
            suggestionTrouble = null,
            suggestionOnlyApplied = false
        )
    }

    /**
     * Asks for folder suggestions for one document.
     *
     * Same shape as [openSuggestions]: the dialog opens at once, with the model
     * answer arriving later, because a local model takes seconds to answer and
     * a frozen button is worse than a spinner.
     */
    fun openFolderSuggestions(docId: Long) {
        val suggester = folderSuggester ?: return
        val doc = state.docs.firstOrNull { it.id == docId } ?: return
        state = state.copy(
            folderDocId = docId,
            folderSuggestions = emptyList(),
            isSuggestingFolder = true,
            folderNote = null,
            folderTrouble = null,
            folderStaysHere = false
        )
        scope.launch {
            val result = runCatching { suggester.suggest(doc) }.getOrNull()
            if (state.folderDocId != docId) return@launch
            state = when {
                result == null -> state.copy(
                    isSuggestingFolder = false,
                    folderNote = SuggestionNote.FAILED,
                    folderStaysHere = false
                )
                result.isEmpty -> state.copy(
                    isSuggestingFolder = false,
                    folderNote = when {
                        // The model did answer — "it is already here" — so the
                        // "nothing found" sentence would contradict the dialog.
                        result.staysHere -> null
                        result.modelUnavailable -> SuggestionNote.MODEL_OFFLINE
                        else -> SuggestionNote.NONE_FOUND
                    },
                    folderTrouble = result.trouble,
                    folderStaysHere = result.staysHere
                )
                else -> state.copy(
                    isSuggestingFolder = false,
                    folderSuggestions = result.suggestions,
                    folderNote = if (result.modelUnavailable) SuggestionNote.MODEL_OFFLINE else null,
                    folderTrouble = result.trouble,
                    folderStaysHere = result.staysHere
                )
            }
        }
    }

    fun closeFolderSuggestions() {
        state = state.copy(
            folderDocId = null,
            folderSuggestions = emptyList(),
            folderNote = null,
            folderTrouble = null,
            folderStaysHere = false
        )
    }

    /**
     * Opens the move dialog for one document — destination by tree, no model.
     *
     * Independent of [openFolderSuggestions]: the person who already knows
     * where the file goes should not wait on a spinner. Only one of the two
     * dialogs is open at a time; opening this one dismisses a suggestion that
     * was still on screen.
     */
    fun openMove(docId: Long) {
        if (mover == null) return
        if (state.docs.none { it.id == docId }) return
        closeFolderSuggestions()
        state = state.copy(moveDocId = docId)
    }

    fun closeMove() {
        state = state.copy(moveDocId = null)
    }

    /** Opens the AI dialog with what is in force; nothing is changed by opening. */
    fun openAiSettings() {
        val ai = ai ?: return
        val current = runCatching { ai.current() }.getOrNull() ?: return
        state = state.copy(aiSettings = current, aiTestOk = null, isTestingAi = false)
    }

    fun closeAiSettings() {
        state = state.copy(aiSettings = null, aiTestOk = null, isTestingAi = false)
    }

    /**
     * Tries the candidate settings without saving them.
     *
     * A key typed in the dialog is tried as typed ([AiController.test] falls back
     * to the stored one when the field is empty), so the person can check a new
     * token before committing it, and a typo in the model name shows up before
     * anything is written to disk.
     */
    fun testAi(candidate: AiSettings) {
        val ai = ai ?: return
        if (state.isTestingAi) return
        state = state.copy(isTestingAi = true, aiTestOk = null)
        scope.launch {
            val ok = runCatching { ai.test(candidate) }.getOrDefault(false)
            // The dialog may have been closed meanwhile.
            if (state.aiSettings == null) return@launch
            state = state.copy(isTestingAi = false, aiTestOk = ok)
        }
    }

    /**
     * Saves the candidate and applies it to the next suggestion.
     *
     * [key] is what the dialog asked for the `key=` line ([KeyChange.Keep] when
     * the field was left empty), and [saved]/[failed] are the sentences of the
     * screen: the controller owns no wording.
     */
    fun saveAi(
        candidate: AiSettings,
        key: KeyChange,
        saved: String,
        failed: String
    ) {
        val ai = ai ?: return
        scope.launch {
            val ok = runCatching { ai.save(candidate, key) }.getOrDefault(false)
            state = state.copy(
                aiSettings = null,
                aiTestOk = null,
                isTestingAi = false,
                message = if (ok) saved else failed
            )
        }
    }

    /** Deletes the stored key; [removed]/[failed] are the screen's sentences. */
    fun removeAiKey(candidate: AiSettings, removed: String, failed: String) {
        val ai = ai ?: return
        scope.launch {
            val ok = runCatching { ai.save(candidate, KeyChange.Remove) }.getOrDefault(false)
            state = state.copy(
                aiSettings = null,
                aiTestOk = null,
                isTestingAi = false,
                message = if (ok) removed else failed
            )
        }
    }

    /**
     * Moves the document into [target], creating the folder when it is new.
     *
     * Used by the suggestion dialog and by the manual move dialog: both end in
     * the same write. [message] turns the outcome into the snackbar sentence —
     * the controller owns no wording. Success is reported too: the file left
     * its folder, and silence would look like the click did nothing.
     */
    fun applyMove(docId: Long, target: String, message: (MoveOutcome) -> String) {
        val mover = mover ?: return
        closeFolderSuggestions()
        closeMove()
        scope.launch {
            // The dialog outlives the list it was opened from (a search while
            // it is open would drop the row), so the index is asked again.
            val doc = state.docs.firstOrNull { it.id == docId }
                ?: runCatching { dao.findById(docId) }.getOrNull()
                ?: return@launch
            val outcome = runCatching { mover.moveTo(doc, target) }
                .getOrElse { MoveOutcome.Rejected(MoveIssue.FAILED) }
            report(message(outcome))
            // A move can create a folder or empty one: the sidebar follows.
            if (outcome is MoveOutcome.Moved) reloadFolders()
        }
    }

    /** Same write as [applyMove]; kept as the name the suggestion dialog uses. */
    fun applyFolderSuggestion(docId: Long, target: String, message: (MoveOutcome) -> String) =
        applyMove(docId, target, message)

    /**
     * Sends a document back to the inbox.
     *
     * The same operation as a folder move with `init` as the target, which is
     * already on disk: nothing is created, so the inbox rule does not get in
     * the way.
     */
    fun moveToInbox(docId: Long, message: (MoveOutcome) -> String) =
        applyMove(docId, Inbox.FOLDER, message)

    /**
     * Creates the folder [raw] typed in the window, parents included.
     *
     * [message] turns the outcome into the snackbar sentence, including the
     * refusals the data layer reports (already exists, inside `init`, invalid).
     * On success the new folder is selected: it is empty, and an empty list
     * with the folder highlighted is a better answer than nothing happening.
     */
    fun createFolder(raw: String, message: (CreateOutcome) -> String) {
        val creator = folderCreator ?: return
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { creator.create(raw) }
                    .getOrElse { CreateOutcome.Rejected(CreateIssue.FAILED) }
            }
            report(message(outcome))
            if (outcome is CreateOutcome.Created) {
                reloadFolders()
                selectView(LibraryView.Folder(outcome.path))
            }
        }
    }

    /**
     * Moves folder [from] so it becomes a child of [toParent] (empty = library
     * root). Dropping a folder on another is the confirmation — same as moving
     * a document.
     *
     * [message] turns the outcome into the snackbar sentence. On success the
     * tree is re-read from disk and, if the open view sat under the moved
     * folder, the selection follows the new path.
     */
    fun moveFolder(from: String, toParent: String, message: (FolderMoveOutcome) -> String) {
        val mover = folderMover ?: return
        scope.launch {
            val outcome = runCatching { mover.moveTo(from, toParent) }
                .getOrElse { FolderMoveOutcome.Rejected(FolderMoveIssue.FAILED) }
            report(message(outcome))
            if (outcome !is FolderMoveOutcome.Moved) return@launch
            val current = (state.view as? LibraryView.Folder)?.path
            if (current != null && isInside(current, outcome.from)) {
                selectView(LibraryView.Folder(FolderMover.remapPath(outcome.from, outcome.to, current)))
            }
            reloadFolders()
        }
    }

    /**
     * Opens the written confirmation for deleting [path] and everything in it.
     *
     * Counting happens before the dialog can be confirmed: "apagar 12
     * arquivos" and "apagar uma pasta vazia" are different decisions, and the
     * person must not discover the difference after typing the name.
     */
    fun openDeleteFolder(path: String) {
        val remover = folderRemover ?: return
        state = state.copy(deleteFolderPath = path, deleteFolderFiles = null)
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                runCatching { remover.count(path) }.getOrDefault(0)
            }
            // The dialog may have been dismissed while the disk was read.
            if (state.deleteFolderPath != path) return@launch
            state = state.copy(deleteFolderFiles = files)
        }
    }

    fun closeDeleteFolder() {
        state = state.copy(deleteFolderPath = null, deleteFolderFiles = null)
    }

    /**
     * Deletes the folder — files and all — after the typed confirmation.
     *
     * [message] turns the outcome into the snackbar sentence. The index follows
     * through [refresh]: the deleted documents become `MISSING` rows (tags
     * included, in case a backup brings them back) and the sidebar tree is read
     * from the disk again, where the folder no longer is.
     */
    fun deleteFolder(path: String, message: (RemoveOutcome) -> String) {
        val remover = folderRemover ?: return
        closeDeleteFolder()
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { remover.remove(path) }
                    .getOrElse { RemoveOutcome.Rejected(RemoveIssue.FAILED) }
            }
            report(message(outcome))
            if (outcome !is RemoveOutcome.Removed) return@launch
            // Deliberate deletion does not wait for a scan: the rows below the
            // folder become MISSING now, so the list and the counts drop the
            // documents even if a scan is already running and `refresh` cannot
            // start another. The rows keep their tags for a restored backup.
            runCatching { dao.setStatusUnder(sqlLikeUnder(outcome.path), DocStatus.MISSING) }
            // Looking at a folder that just went away would leave an empty list
            // under a name that no longer exists.
            val current = (state.view as? LibraryView.Folder)?.path
            if (current != null && isInside(current, outcome.path)) {
                selectView(LibraryView.All)
            }
            // The sentences about the deleted folder would otherwise come back
            // attached to a new folder with the same name months later.
            descriptions?.forgetFolder(outcome.path)
            // Inherited tags of the deleted subtree would otherwise stick to a
            // brand-new folder created later with the same path.
            folderTags?.forgetFolder(outcome.path)
            // The tree is read from the disk first, so the sidebar is right even
            // when a scan was already running and `refresh` bows out; the scan
            // then picks up anything else that changed meanwhile.
            reloadFolders()
            refresh()
        }
    }

    /**
     * Opens the dialog that picks which tags [path] passes to documents under
     * it. The inbox and the library root are refused: there is nothing useful
     * to inherit from either.
     */
    fun openInheritTags(path: String) {
        if (folderTags == null) return
        val normalized = FolderPath.normalize(path) ?: return
        if (normalized.isEmpty()) return
        if (normalized.equals(Inbox.FOLDER, ignoreCase = true)) return
        state = state.copy(inheritingFolder = normalized)
    }

    fun closeInheritTags() {
        state = state.copy(inheritingFolder = null)
    }

    /**
     * Replaces the inherited set of the open folder with [paths].
     *
     * Empty list clears inheritance. The dialog closes only when the write
     * worked, same honesty as saving a description.
     */
    fun saveInheritedTags(paths: List<String>, message: (Boolean) -> String) {
        val repository = folderTags ?: return
        val folder = state.inheritingFolder ?: return
        scope.launch {
            val saved = repository.setInherited(folder, paths)
            if (saved) state = state.copy(inheritingFolder = null)
            report(message(saved))
        }
    }

    /**
     * Creates [raw] in the tag index without applying it to a document.
     *
     * Used by the inherit dialog: "Adicionar" used to only keep the path in
     * the draft until *Salvar*, so closing the dialog (or never saving) made
     * tags like `termo` look like they had been created and then vanished.
     */
    fun ensureTag(
        raw: String,
        invalidMessage: String,
        createdMessage: (String) -> String,
        failureMessage: String
    ) {
        val path = TagPaths.normalize(raw)
        if (path == null) {
            report(invalidMessage)
            return
        }
        scope.launch {
            val id = runCatching { tags.ensure(path) }.getOrNull()
            if (id == null) {
                report(failureMessage)
            } else {
                report(createdMessage(path))
            }
        }
    }

    /** Opens the confirmation for deleting [path] and its sub-tags. */
    fun openDeleteTag(path: String) {
        state = state.copy(deleteTagPath = path)
    }

    fun closeDeleteTag() {
        state = state.copy(deleteTagPath = null)
    }

    fun openRenameTag(path: String) {
        state = state.copy(renameTagPath = path)
    }

    fun closeRenameTag() {
        state = state.copy(renameTagPath = null)
    }

    /**
     * Renames the open tag to [raw].
     *
     * Filters that pointed at the old path (or under it) follow the new name.
     */
    fun renameTag(raw: String, successMessage: (String, String) -> String, failedMessage: String) {
        val from = state.renameTagPath ?: return
        val to = TagPaths.normalize(raw)
        if (to == null) {
            report(failedMessage)
            return
        }
        scope.launch {
            val ok = runCatching { tags.rename(from, to) }.getOrDefault(false)
            if (!ok) {
                report(failedMessage)
                return@launch
            }
            closeRenameTag()
            val remapped = state.tagFilters.map { filter ->
                when {
                    filter == from -> to
                    filter.startsWith("$from/") -> to + filter.removePrefix(from)
                    else -> filter
                }
            }.toSet()
            if (remapped != state.tagFilters) {
                tagFlow.value = remapped
                state = state.copy(tagFilters = remapped)
            }
            report(successMessage(from, to))
        }
    }

    /**
     * Opens the description field for a folder or a tag.
     *
     * Only one at a time: the dialog has a single save button, and the target is
     * what decides where the sentence is written.
     */
    fun openDescribe(target: DescribeTarget) {
        if (descriptions == null) return
        state = state.copy(describing = target)
    }

    fun closeDescribe() {
        state = state.copy(describing = null)
    }

    /**
     * Opens the search-alias dialog for one tag.
     *
     * The list is loaded once and refreshed after each add/remove: the dialog
     * owns no Flow collector of its own.
     */
    fun openAliases(path: String) {
        state = state.copy(aliasTagPath = path, aliasList = emptyList())
        scope.launch {
            val list = runCatching { tags.aliasesOf(path) }.getOrDefault(emptyList())
            if (state.aliasTagPath == path) {
                state = state.copy(aliasList = list)
            }
        }
    }

    fun closeAliases() {
        state = state.copy(aliasTagPath = null, aliasList = emptyList())
    }

    fun addAlias(raw: String, invalidMessage: String, failedMessage: String) {
        val path = state.aliasTagPath ?: return
        val normalized = TagAliases.normalize(raw)
        if (normalized == null || TagAliases.redundant(normalized, path)) {
            report(invalidMessage)
            return
        }
        scope.launch {
            val ok = runCatching { tags.addAlias(path, raw) }.getOrDefault(false)
            if (!ok) {
                report(failedMessage)
                return@launch
            }
            val list = runCatching { tags.aliasesOf(path) }.getOrDefault(emptyList())
            if (state.aliasTagPath == path) state = state.copy(aliasList = list)
        }
    }

    fun removeAlias(alias: String, failedMessage: String) {
        val path = state.aliasTagPath ?: return
        scope.launch {
            val ok = runCatching { tags.removeAlias(path, alias) }.getOrDefault(false)
            if (!ok) {
                report(failedMessage)
                return@launch
            }
            val list = runCatching { tags.aliasesOf(path) }.getOrDefault(emptyList())
            if (state.aliasTagPath == path) state = state.copy(aliasList = list)
        }
    }

    /**
     * Saves what was typed in the description dialog. Empty text removes it.
     *
     * The dialog closes only when the write worked: closing first would hide a
     * failure and the user would think the AI had read a sentence that is not
     * there.
     */
    fun saveDescription(text: String, message: (Boolean) -> String) {
        val repository = descriptions ?: return
        val target = state.describing ?: return
        scope.launch {
            val saved = when (target) {
                is DescribeTarget.Folder -> repository.describeFolder(target.path, text)
                is DescribeTarget.Tag -> repository.describeTag(target.path, text)
            }
            // The flow of the database updates the maps; the dialog closes here
            // so a successful save does not leave the field open.
            if (saved) state = state.copy(describing = null)
            report(message(saved))
        }
    }

    /**
     * Deletes the tag and everything below it, including the assignments.
     *
     * [message] receives what was removed — the tags of the subtree and how many
     * document links went with them — so the snackbar can report both. A tag
     * filter pointing into the deleted subtree is cleared: keeping it would show
     * an empty list under a row that is no longer in the sidebar.
     */
    fun deleteTag(path: String, message: (TagRemoval) -> String) {
        closeDeleteTag()
        scope.launch {
            val removal = runCatching { tags.removeTree(path) }
                .getOrDefault(TagRemoval(tags = 0, documents = 0))
            report(message(removal))
            val kept = state.tagFilters.filterNot { isInside(it, path) }.toSet()
            if (kept != state.tagFilters) {
                tagFlow.value = kept
                state = state.copy(tagFilters = kept)
            }
        }
    }

    /**
     * Documents for [listing]: one tag still goes through SQL; two or more are
     * AND-filtered in memory. A non-empty query skips the SQL name/content
     * clause and runs [UniversalSearch] so tag aliases and metadata rank too.
     */
    private fun observeListing(
        listing: Listing,
        inheritedFlow: Flow<Map<String, List<String>>>
    ): Flow<List<Doc>> {
        val needle = listing.query.trim()
        // Universal search owns text matching when there is a query; SQL would
        // hide tag-only hits before the ranker ran.
        val pattern = if (needle.isEmpty()) sqlLikePattern("") else "%%"
        val folder = (listing.view as? LibraryView.Folder)?.path
        val favoriteOnly = if (listing.view is LibraryView.Favorites) 1 else 0
        val recentSince = listing.view.recentSince(System.currentTimeMillis())
        val sqlTag = listing.tags.singleOrNull()
        val docsFlow = when (listing.sort) {
            SortOrder.NAME -> dao.observeByName(
                status = DocStatus.PRESENT,
                pattern = pattern,
                tag = sqlTag,
                tagPrefix = sqlTag?.let(TagPaths::descendantPattern) ?: "",
                favoriteOnly = favoriteOnly,
                recentSince = recentSince,
                folder = folder,
                folderPrefix = folder?.let(TagPaths::descendantPattern) ?: ""
            )
            SortOrder.RECENT -> dao.observeByModified(
                status = DocStatus.PRESENT,
                pattern = pattern,
                tag = sqlTag,
                tagPrefix = sqlTag?.let(TagPaths::descendantPattern) ?: "",
                favoriteOnly = favoriteOnly,
                recentSince = recentSince,
                folder = folder,
                folderPrefix = folder?.let(TagPaths::descendantPattern) ?: ""
            )
        }
        val byTags: Flow<List<Doc>> = if (listing.tags.size <= 1) {
            docsFlow
        } else {
            combine(
                docsFlow,
                tagDao.observeAssignments(DocStatus.PRESENT),
                dao.observeIdPaths(DocStatus.PRESENT),
                inheritedFlow
            ) { docs, assignmentRows, idPaths, byFolder ->
                val assignments = assignmentRows.groupBy({ it.docId }, { it.path })
                val folders = idPaths.associate { it.id to it.folder }
                docs.filter { doc ->
                    val carried = assignments[doc.id].orEmpty() +
                        FolderTagInherit.resolve(folders[doc.id].orEmpty(), byFolder)
                    listing.tags.all { required -> TagPaths.covers(carried, required) }
                }
            }
        }
        if (needle.isEmpty()) {
            // "Recentes" keeps chronological order; otherwise habit (frecency)
            // leads, like the launcher with an empty query.
            return if (listing.sort == SortOrder.RECENT) {
                byTags
            } else {
                byTags.map { docs ->
                    UniversalSearch.rank(
                        query = "",
                        docs = docs,
                        tagsOf = emptyMap(),
                        aliasesByTag = emptyMap(),
                        texts = emptyMap(),
                        metaOf = { emptyList() },
                        secondary = compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                    )
                }
            }
        }
        val textsFlow = textDao?.observeAll() ?: flowOf(emptyList())
        val aliasesFlow = tags.observeAliasesByPath()
        // Six inputs: nest two combines — the stdlib overload stops at five.
        val rankedBase = combine(
            byTags,
            tagDao.observeAssignments(DocStatus.PRESENT),
            dao.observeIdPaths(DocStatus.PRESENT),
            inheritedFlow,
            textsFlow
        ) { docs, assignmentRows, idPaths, byFolder, textRows ->
            SearchPack(
                docs = docs,
                assignments = assignmentRows.groupBy({ it.docId }, { it.path }),
                folders = idPaths.associate { it.id to it.folder },
                byFolder = byFolder,
                texts = textRows.associate { it.docId to it.text }
            )
        }
        return combine(rankedBase, aliasesFlow) { pack, aliases ->
            val tagsOf = HashMap<Long, List<String>>()
            for (doc in pack.docs) {
                val carried = (
                    pack.assignments[doc.id].orEmpty() +
                        FolderTagInherit.resolve(pack.folders[doc.id].orEmpty(), pack.byFolder)
                    ).distinct()
                tagsOf[doc.id] = carried
            }
            val secondary = when (listing.sort) {
                SortOrder.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { doc: Doc -> doc.name }
                SortOrder.RECENT -> compareByDescending<Doc> { it.modifiedTime }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            }
            UniversalSearch.rank(
                query = needle,
                docs = pack.docs,
                tagsOf = tagsOf,
                aliasesByTag = aliases,
                texts = pack.texts,
                metaOf = ::searchMeta,
                secondary = secondary
            )
        }
    }

    /** Strings the universal search treats as metadata for one document. */
    private fun searchMeta(doc: Doc): List<String> {
        val zone = ZoneId.systemDefault()
        val zoned = Instant.ofEpochMilli(doc.modifiedTime).atZone(zone)
        val ext = doc.name.substringAfterLast('.', "")
            .takeIf { it.isNotEmpty() && it != doc.name }
        return buildList {
            add(doc.kind.name)
            if (ext != null) add(ext)
            add(formatSize(doc.size))
            add(formatDateTime(doc.modifiedTime, zone))
            add("%02d/%04d".format(zoned.monthValue, zoned.year))
            add(zoned.year.toString())
        }
    }

    /**
     * Moves files chosen on the computer into the inbox.
     *
     * This is the "import" of the library: the person picks the files wherever
     * they are and they land in `init/`, ready to be tagged and organized. The
     * index is refreshed afterwards so the list shows them without a click.
     */
    fun importToInbox(sources: List<Path>, message: (ImportReport) -> String) {
        val importer = importer ?: return
        if (sources.isEmpty() || state.isImporting) return
        state = state.copy(isImporting = true)
        scope.launch {
            val outcome = runCatching { importer.import(sources) }
                .getOrElse { ImportReport(failed = sources.size) }
            state = state.copy(isImporting = false)
            report(message(outcome))
            if (outcome.imported > 0) refresh()
        }
    }

    /**
     * Applies what the user kept checked.
     *
     * For a document in the [Inbox] only tags that already exist are applied —
     * nothing is created there — so [message] receives both counts: how many
     * landed and how many were left out because they would have been new.
     */
    /**
     * Applies the tags the user checked in the suggestion dialog.
     *
     * A checked tag that does not exist yet is created by this call: the dialog
     * only enables *apply* after the person confirms, so there is no creation
     * without a click. That holds for a document in the [Inbox] too.
     */
    fun applySuggestions(docId: Long, paths: List<String>, message: (Int) -> String) {
        closeSuggestions()
        if (paths.isEmpty()) return
        scope.launch {
            var applied = 0
            for (path in paths) {
                if (path in state.tagsOf(docId)) continue
                if (tags.apply(docId, path)) applied += 1
            }
            if (applied > 0) report(message(applied))
        }
    }

    /**
     * Applies the tag if the document does not have it, removes it otherwise.
     *
     * An inherited tag cannot be toggled off here: it comes from the folder,
     * and only the folder dialog can take it away. Applying the same tag by
     * hand while it is already inherited is a no-op from this picker (the box
     * stays locked).
     */
    fun toggleTag(docId: Long, path: String, failureMessage: String) {
        if (state.isInherited(docId, path)) return
        val applied = path in state.assignments[docId].orEmpty()
        scope.launch {
            val ok = if (applied) tags.remove(docId, path) else tags.apply(docId, path)
            if (!ok) report(failureMessage)
        }
    }

    /**
     * Creates the tag typed in the picker and applies it to the open document.
     * The text is normalized first; invalid input is a message, not an
     * exception.
     */
    fun createTag(
        docId: Long,
        raw: String,
        invalidMessage: String,
        failureMessage: String = invalidMessage
    ) {
        val path = TagPaths.normalize(raw)
        if (path == null) {
            report(invalidMessage)
            return
        }
        scope.launch {
            val ok = runCatching { tags.apply(docId, path) }.getOrDefault(false)
            if (!ok) report(failureMessage)
        }
    }

    /** Transient feedback for the snackbar (open failed, path copied…). */
    fun report(message: String) {
        if (message.isEmpty()) return
        state = state.copy(message = message)
    }

    /** Re-reads the folders from disk; the tree itself is rebuilt by the flow. */
    private suspend fun reloadFolders() {
        val folders = withContext(Dispatchers.IO) {
            runCatching { FolderScanner.scan(root) }.getOrDefault(emptyList())
        }
        folderDirsFlow.value = folders
    }

    fun messageShown() {
        state = state.copy(message = null)
    }

    /**
     * True when [path] is [folder] or lives below it, in either the folder or
     * the tag spelling — both are `/`-separated, so the same test serves both.
     */
    private fun isInside(path: String, folder: String): Boolean =
        path.equals(folder, ignoreCase = true) ||
            path.startsWith("$folder${FolderPath.SEPARATOR}", ignoreCase = true)
}
