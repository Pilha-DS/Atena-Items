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

package com.openappslabs.docs.data.search

import com.openappslabs.docs.data.model.Doc
import com.openappslabs.docs.data.tags.TagPaths

/** One document and its launcher-style score (higher wins). */
data class ScoredDoc(val doc: Doc, val score: Double)

/**
 * Filters and ranks documents with weighted fuzzy fields + frecency.
 *
 * Weights mirror the launcher: name/tag/alias 1.0, metadata 0.5, content 0.6,
 * plus up to [Frecency.WEIGHT] from open habit.
 */
object UniversalSearch {

    private const val WEIGHT_NAME = 1.0
    private const val WEIGHT_TAG = 1.0
    private const val WEIGHT_ALIAS = 1.0
    private const val WEIGHT_META = 0.5
    private const val WEIGHT_CONTENT = 0.6

    /**
     * Ranks [docs] for [query].
     *
     * Blank query keeps every document and sorts by frecency (then [secondary]).
     * Non-blank query drops documents below the quality gate.
     */
    fun rank(
        query: String,
        docs: List<Doc>,
        tagsOf: Map<Long, List<String>>,
        aliasesByTag: Map<String, List<String>>,
        texts: Map<Long, String>,
        metaOf: (Doc) -> Iterable<String>,
        secondary: Comparator<Doc>,
        nowMs: Long = System.currentTimeMillis()
    ): List<Doc> = score(
        query = query,
        docs = docs,
        tagsOf = tagsOf,
        aliasesByTag = aliasesByTag,
        texts = texts,
        metaOf = metaOf,
        nowMs = nowMs
    )
        .sortedWith(compareByDescending<ScoredDoc> { it.score }.thenBy(secondary) { it.doc })
        .map { it.doc }

    fun score(
        query: String,
        docs: List<Doc>,
        tagsOf: Map<Long, List<String>>,
        aliasesByTag: Map<String, List<String>>,
        texts: Map<Long, String>,
        metaOf: (Doc) -> Iterable<String>,
        nowMs: Long = System.currentTimeMillis()
    ): List<ScoredDoc> {
        val needle = query.trim()
        if (needle.isEmpty()) {
            return docs.map { doc ->
                val frec = Frecency.score(doc.visitCount, doc.lastVisitedAt, nowMs)
                // Same empty-query shape as the launcher: frecency on a 100 base.
                ScoredDoc(doc, 100.0 - Frecency.WEIGHT + Frecency.WEIGHT * frec)
            }
        }
        return docs.mapNotNull { doc ->
            val match = fuzzyFor(
                needle = needle,
                doc = doc,
                tags = tagsOf[doc.id].orEmpty(),
                aliasesByTag = aliasesByTag,
                text = texts[doc.id],
                meta = metaOf(doc)
            )
            if (!match.accepted()) return@mapNotNull null
            val frec = Frecency.score(doc.visitCount, doc.lastVisitedAt, nowMs)
            ScoredDoc(doc, match.score + Frecency.WEIGHT * frec)
        }
    }

    fun fuzzyFor(
        needle: String,
        doc: Doc,
        tags: List<String>,
        aliasesByTag: Map<String, List<String>>,
        text: String?,
        meta: Iterable<String>
    ): FuzzyMatch.Match {
        val fields = ArrayList<FuzzyMatch.WeightedField>()
        fields += FuzzyMatch.WeightedField(doc.name, WEIGHT_NAME)
        for (path in tags) {
            fields += FuzzyMatch.WeightedField(path, WEIGHT_TAG)
            fields += FuzzyMatch.WeightedField(TagPaths.name(path), WEIGHT_TAG)
            for (alias in aliasesByTag[path].orEmpty()) {
                fields += FuzzyMatch.WeightedField(alias, WEIGHT_ALIAS)
            }
        }
        for (m in meta) {
            if (m.isNotBlank()) fields += FuzzyMatch.WeightedField(m, WEIGHT_META)
        }
        if (!text.isNullOrBlank()) {
            // Cap the haystack: ranking on a whole OCR dump is wasteful and
            // noisy; the first chunk still catches the usual keywords.
            val excerpt = if (text.length > 4_000) text.substring(0, 4_000) else text
            fields += FuzzyMatch.WeightedField(excerpt, WEIGHT_CONTENT)
        }
        return FuzzyMatch.scoreWeighted(fields, needle)
    }
}
