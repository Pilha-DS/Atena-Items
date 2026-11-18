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

package com.openappslabs.docs.data.ai

import com.openappslabs.docs.data.model.Doc
import com.openappslabs.docs.data.model.DocStatus
import com.openappslabs.docs.data.move.FolderPath
import com.openappslabs.docs.data.move.Inbox
import com.openappslabs.docs.data.source.DocDao
import com.openappslabs.docs.data.source.DocTextDao

/** What came back for one document, and how it was produced. */
data class FolderResult(
    val suggestions: List<FolderSuggestion>,
    /** True when a model answered; false means an existing folder matched. */
    val usedModel: Boolean,
    /** Set when the model was tried and did not produce an answer. */
    val trouble: ModelTrouble? = null,
    /**
     * True when the model answered the folder the document already is in.
     *
     * That is a real answer — "it is already where it belongs" — and the dialog
     * says it instead of silently swapping in a sibling folder the model never
     * mentioned. A new folder proposed from the name still follows, because
     * "already organized" does not mean "no place to create".
     */
    val staysHere: Boolean = false
) {
    val isEmpty: Boolean get() = suggestions.isEmpty()

    /** Kept for the callers that only ask "did the model fail?". */
    val modelUnavailable: Boolean get() = trouble != null
}

/**
 * Proposes a folder for a document: the model when there is one, the folders
 * that already exist when there is not — plus, when nothing fits, a folder that
 * does not exist yet, marked as new.
 *
 * Nothing is created or moved here. The suggestion is a candidate shown in a
 * dialog; only the user's confirmation reaches [com.openappslabs.docs.data.move.DocMover].
 */
class FolderSuggester(
    private val textDao: DocTextDao,
    private val dao: DocDao,
    private val modelSource: () -> TagModel? = { null },
    /**
     * The tree as it is on disk, empty folders included.
     *
     * The index cannot answer this: it has one row per file, so a folder that
     * was just created (or is waiting for its first document) does not exist
     * there. Without this the model was shown a partial tree and confidently
     * proposed "new" folders that were already there.
     *
     * Defaults to the index so a test that only has a database still works.
     */
    private val folderSource: (suspend () -> List<String>)? = null,
    /**
     * What the user said each folder is for.
     *
     * The default returns nothing, so a test with only a database still runs;
     * the window wires it to [com.openappslabs.docs.data.repository.DescriptionRepository].
     * Read on every suggestion and not cached: the person may describe a
     * folder while the window is open, and the next question must see it.
     */
    private val descriptionSource: (suspend () -> Map<String, String>)? = null,
    /** Reads a document that the batch had not reached yet, at suggestion time. */
    private val textSource: (suspend (Doc) -> String?)? = null,
    /** Date the file was created on disk; null when the filesystem has none. */
    private val createdTimeSource: (suspend (Doc) -> Long?)? = null
) {
    constructor(textDao: DocTextDao, dao: DocDao, model: TagModel?) :
        this(textDao, dao, { model })

    suspend fun suggest(doc: Doc, limit: Int = MAX_SUGGESTIONS): FolderResult {
        val text = readText(doc)
        val tree = runCatching { readTree() }.getOrDefault(Tree(emptyList(), emptyMap()))
        val folders = tree.folders
        val notes = descriptionSource?.let { source ->
            runCatching { source() }.getOrNull()
        }.orEmpty()

        val model = modelSource()
        var trouble: ModelTrouble? = null
        if (model != null) {
            // Same reasoning as the tag suggester: ask for the completion, do
            // not gate it on a separate health endpoint.
            val facts = factsFor(doc)
            val answer = runCatching {
                model.suggest(FolderPrompt.build(doc, text, folders, tree.contents, notes, facts))
            }.getOrDefault(emptyList())
            if (answer.isEmpty()) {
                trouble = modelTrouble(model)
            } else {
                val raw = FolderPrompt.parse(answer)
                val parsed = raw
                    .map { FolderPath.reuseCase(it, folders) }
                    .filterNot { it.equals(doc.folder, ignoreCase = true) }
                    .filterNot { Inbox.holds(it) }
                    // A date folder (`2026`, `mes-11`) is the same mistake as a
                    // date tag: the file's date is not where the document lives.
                    .filterNot { DateTag.isDateOnly(it) }
                    .distinct()
                val fromModel = parsed.take(limit)
                if (fromModel.isNotEmpty()) {
                    return FolderResult(
                        suggestions = fromModel.map { target ->
                            FolderSuggestion(
                                target = target,
                                reason = if (target.isEmpty()) {
                                    "o modelo sugere o topo da biblioteca"
                                } else {
                                    "sugerida pela IA"
                                },
                                isNew = target.isNotEmpty() &&
                                    folders.none { it.equals(target, ignoreCase = true) },
                                fromModel = true
                            )
                        },
                        usedModel = true
                    )
                }
                // The model pointed at where the document already is. Honor it:
                // the text heuristic would look for another folder with a word
                // of the name and could move the file to an unrelated sibling.
                if (raw.any { it.equals(doc.folder, ignoreCase = true) }) {
                    return FolderResult(
                        suggestions = HeuristicFolders.proposeNew(doc, folders + raw, limit),
                        usedModel = true,
                        staysHere = true
                    )
                }
                // "Stay where you are" and "the inbox" are real answers that the
                // dialog cannot use; only an answer with nothing parseable at all
                // is a bad answer.
                if (raw.isEmpty() && !FolderPrompt.isRefusal(answer)) {
                    trouble = ModelTrouble(ModelIssue.BAD_ANSWER)
                }
            }
        }

        // Existing folders that match, then a folder that does not exist yet:
        // an empty library still gets a concrete suggestion to accept.
        val known = HeuristicFolders.suggest(doc, text, folders, limit, notes)
        val proposals = if (known.size < limit) {
            val taken = folders + known.map { it.target }
            HeuristicFolders.proposeNew(doc, taken, limit - known.size)
        } else {
            emptyList()
        }
        return FolderResult(
            suggestions = known + proposals,
            usedModel = false,
            trouble = trouble
        )
    }

    /** What the prompt needs: the folders and a hint of what each one holds. */
    private data class Tree(
        val folders: List<String>,
        val contents: Map<String, List<String>>
    )

    /**
     * Every folder that exists, deepest paths included, plus the documents when
     * the index knows them.
     *
     * [folderSource] reads the disk; without it the index stands in, which only
     * knows folders that already hold a document. The [Inbox] and everything
     * below it stay out: it is a waiting room, not a destination, and suggesting
     * it would only send documents back to the pile they came from.
     *
     * The contents are what makes the prompt coherent: two sibling folders can
     * have equally plausible names, and only their files tell them apart.
     */
    private suspend fun readTree(): Tree {
        val indexed = runCatching { dao.paths(DocStatus.PRESENT) }.getOrDefault(emptyList())
        val raw = folderSource?.let { source -> runCatching { source() }.getOrNull() }
            ?: indexed.map { FolderPath.of(it) }
        val folders = raw
            .mapNotNull { FolderPath.normalize(it) }
            .filter { it.isNotEmpty() && !Inbox.holds(it) }
            .distinct()
            .sorted()
        val contents = indexed
            .filterNot { Inbox.holds(FolderPath.of(it)) }
            .groupBy({ FolderPath.normalize(FolderPath.of(it)).orEmpty() }, { it.substringAfterLast('/') })
            .filterKeys { it.isNotEmpty() }
        return Tree(folders, contents)
    }

    /**
     * The stored text, or a read of this one file when it was never read.
     *
     * A row with empty text is trusted: the file was read and has no words, and
     * re-running OCR at every suggestion would be worse than answering from the
     * name.
     */
    private suspend fun readText(doc: Doc): String {
        val stored = runCatching { textDao.get(doc.id) }.getOrNull()
        if (stored != null) return stored.text
        val source = textSource ?: return ""
        return runCatching { source(doc) }.getOrNull().orEmpty()
    }

    /** Tipo, tamanho e datas; sem a data de criação quando o disco não a tem. */
    private suspend fun factsFor(doc: Doc): DocFacts {
        val created = createdTimeSource
            ?.let { source -> runCatching { source(doc) }.getOrNull() }
        return DocFacts.of(doc, created)
    }

    companion object {
        const val MAX_SUGGESTIONS = 3
    }
}
