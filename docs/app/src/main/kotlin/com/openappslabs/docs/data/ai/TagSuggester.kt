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
import com.openappslabs.docs.data.source.DocTextDao
import com.openappslabs.docs.data.source.TagDao

/** What came back for one document, and how it was produced. */
data class SuggestionResult(
    val suggestions: List<TagSuggestion>,
    /** True when a model answered; false means the text was enough. */
    val usedModel: Boolean,
    /** Set when the model was tried and did not produce an answer. */
    val trouble: ModelTrouble? = null,
    /**
     * True when the model answered, but only with tags the document already
     * carries.
     *
     * Nothing new came from it, and the dialog says so: an empty list next to a
     * working model looks like a bug, while "it only repeated what is already
     * there" is an answer.
     */
    val onlyApplied: Boolean = false
) {
    val isEmpty: Boolean get() = suggestions.isEmpty()

    /** Kept for the callers that only ask "did the model fail?". */
    val modelUnavailable: Boolean get() = trouble != null
}

/**
 * Proposes tags for a document: the model when it is configured, the text when
 * it is not (or when it fails).
 *
 * Everything here is a *suggestion*: nothing is applied and no tag is created
 * until the user confirms in the dialog. The text never leaves the machine with
 * the default provider: it is an Ollama instance on localhost, unless the user
 * chooses a remote one (DeepSeek) in the AI dialog.
 *
 * The model arrives as a function, not as a value: the settings can be changed
 * while the window is open, and the next suggestion must use the new client.
 */
class TagSuggester(
    private val textDao: DocTextDao,
    private val tagDao: TagDao,
    private val modelSource: () -> TagModel? = { null },
    /**
     * Reads a document that was never read, at the moment of the suggestion.
     *
     * Without it the prompt says "no text" for a file the batch simply had not
     * reached yet, which reads as "the app never opened the file". The read is
     * stored like any other, so it is paid once.
     */
    private val textSource: (suspend (Doc) -> String?)? = null,
    /** Date the file was created on disk; null when the filesystem has none. */
    private val createdTimeSource: (suspend (Doc) -> Long?)? = null
) {
    constructor(textDao: DocTextDao, tagDao: TagDao, model: TagModel?) :
        this(textDao, tagDao, { model })

    suspend fun suggest(doc: Doc, limit: Int = MAX_SUGGESTIONS): SuggestionResult {
        val text = readText(doc)
        val tags = runCatching { tagDao.all() }.getOrDefault(emptyList())
        // Every tag that exists, years and months included: the model has to
        // see `2025` and `mes-11` to reuse them instead of inventing `2025/11`.
        val allPaths = tags.map { it.path }
        val existing = allPaths
        // Word heuristics stay off bare date tags: `mes` matches inside unrelated
        // words. Compound dates (`2025/mes-4`) still score like any other tag.
        val heuristicVocab = allPaths.filterNot {
            DateTag.isDateOnly(it) && !DateTag.isReusable(it)
        }
        val notes = tags
            .filter { it.description.isNotBlank() }
            .associate { it.path to it.description }
        val applied = runCatching { tagDao.tagIdsFor(doc.id) }.getOrDefault(emptyList()).toSet()
        val appliedPaths = tags.filter { it.id in applied }.map { it.path }.toSet()
        val examples = runCatching { tagDao.taggedNames(DocStatus.PRESENT) }
            .getOrDefault(emptyList())
            .groupBy { it.path }
            .mapValues { (_, rows) ->
                rows.map { it.name }.distinct().take(TagPrompt.MAX_EXAMPLE_DOCS)
            }

        val model = modelSource()
        var trouble: ModelTrouble? = null
        var onlyApplied = false
        var fromModel = emptyList<String>()
        // The date inside the document, not the one on the disk. Used both to
        // tell the model what to reuse and to map answers onto year/month tags.
        val documentDate = DocDate.of(text)
        if (model != null) {
            // No availability probe in front of this on purpose: the probe and
            // the completion are different endpoints, and a service that answers
            // the completion while 404ing its model list would never be used.
            val facts = factsFor(doc)
            val answer = runCatching {
                model.suggest(
                    TagPrompt.build(doc, text, existing, examples, notes, facts, documentDate)
                )
            }.getOrDefault(emptyList())
            if (answer.isEmpty()) {
                trouble = modelTrouble(model)
            } else {
                val parsed = TagPrompt.parse(answer)
                    .flatMap { DateTag.remap(it, allPaths, documentDate) }
                    .distinct()
                fromModel = parsed
                    // A date tag only survives when the text backs it: this is
                    // what kept `mes-11` coming back from the day the file was
                    // copied into the library.
                    .filter { it !in appliedPaths && DateTag.allowedIn(it, text, documentDate) }
                    .take(limit)
                // A list made only of tags the document already has is a valid
                // answer — there is nothing new — not a failure.
                onlyApplied = parsed.isNotEmpty() && parsed.all { it in appliedPaths }
                // A polite "nenhuma" is an answer, and so is a list of tags the
                // document already has. Only an answer that parsed to nothing
                // new at all is worth reporting as a bad answer.
                if (fromModel.isEmpty() && parsed.isEmpty() && !TagPrompt.isRefusal(answer)) {
                    trouble = ModelTrouble(ModelIssue.BAD_ANSWER)
                }
            }
        }

        // Existing subject tags that match the document first — reuse beats
        // invention even when the model answered.
        val known = HeuristicTagger.suggest(doc, text, heuristicVocab, limit, notes)
            .filter { it.path !in appliedPaths }
        // The document date, remapped onto year/month tags the library already
        // has (`2025` + `mes-11`) instead of always inventing `2025/mes-11`.
        val fromDate = documentDate
            ?.let { DateTag.resolveExisting(it, allPaths) }
            .orEmpty()
            .filter { it !in appliedPaths && DateTag.allowedIn(it, text, documentDate) }
            .map { path ->
                TagSuggestion(
                    path = path,
                    reason = "a data do documento",
                    fromModel = false,
                    isNew = path !in allPaths
                )
            }
        val merged = LinkedHashMap<String, TagSuggestion>()
        for (suggestion in known) merged[suggestion.path] = suggestion
        for (suggestion in fromDate) merged.putIfAbsent(suggestion.path, suggestion)
        for (path in fromModel) {
            if (path in merged) continue
            merged[path] = TagSuggestion(
                path = path,
                reason = "sugerida pela IA",
                fromModel = true,
                isNew = path !in allPaths
            )
        }
        // New tags from the text only fill the gaps when the model did not
        // answer: inventing on top of an IA list duplicates its job.
        if (merged.size < limit && fromModel.isEmpty()) {
            // Paths already chosen (including the remapped document date) count
            // as existing so proposeNew does not spend its limit re-offering them.
            val already = allPaths + merged.keys
            for (proposal in HeuristicTagger.proposeNew(doc, text, already, limit - merged.size)) {
                val expanded = if (DateTag.isDateOnly(proposal.path) && documentDate != null) {
                    DateTag.resolveExisting(documentDate, allPaths).map { path ->
                        proposal.copy(
                            path = path,
                            isNew = path !in allPaths,
                            reason = if (path in allPaths) "a data do documento" else proposal.reason
                        )
                    }
                } else {
                    listOf(proposal)
                }
                for (item in expanded) merged.putIfAbsent(item.path, item)
            }
        }
        return SuggestionResult(
            suggestions = merged.values.take(limit).toList(),
            usedModel = fromModel.isNotEmpty(),
            trouble = trouble,
            onlyApplied = onlyApplied
        )
    }

    /**
     * The stored text, or a read of this one file when it was never read.
     *
     * A row with empty text is a real answer ("nothing inside") and is trusted:
     * re-running OCR on the same photo at every suggestion would make the
     * dialog take a minute to say what it already knew.
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

/**
 * Why the model produced nothing: its own report when it has one, the health
 * probe when it does not.
 *
 * The probe is only the fallback. Clients that talk over HTTP know exactly what
 * failed and say so; a client that just returns an empty list (a fake, an older
 * implementation) gets the old behaviour instead of a silent "nothing".
 */
internal suspend fun modelTrouble(model: TagModel): ModelTrouble? {
    val reported = runCatching { model.trouble() }.getOrNull()
    if (reported != null) return reported
    val available = runCatching { model.isAvailable() }.getOrDefault(false)
    return if (available) null else ModelTrouble(ModelIssue.UNREACHABLE)
}
