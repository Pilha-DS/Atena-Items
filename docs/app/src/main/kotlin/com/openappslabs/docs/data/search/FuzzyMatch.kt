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

/**
 * Lightweight fuzzy match for the Docs library.
 *
 * Not a port of fzf: same *shape* as the launcher (score + quality 0–100,
 * quality gate) so ranking stays familiar, without linking C++.
 */
object FuzzyMatch {

    /** Worst matched word must clear this, like Vicinae's MIN_QUALITY. */
    const val MIN_QUALITY = 60

    data class Match(val score: Int = 0, val quality: Int = 0) {
        fun accepted(): Boolean = quality >= MIN_QUALITY
    }

    data class WeightedField(val text: String, val weight: Double)

    /**
     * Best field-weighted score for [query] against [fields].
     *
     * Quality is the worst word's unweighted quality (filter); score is the
     * best field-weighted match averaged over query words (rank).
     */
    fun scoreWeighted(fields: List<WeightedField>, query: String): Match {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || fields.isEmpty()) return Match()

        var weightedSum = 0.0
        var worstQuality = 100
        for (word in words) {
            var bestWeighted = 0.0
            var bestQuality = 0
            for (field in fields) {
                if (field.text.isEmpty() || field.weight <= 0.0) continue
                val raw = scoreOne(field.text, word)
                if (raw > bestQuality) bestQuality = raw
                val w = raw * field.weight
                if (w > bestWeighted) bestWeighted = w
            }
            if (bestQuality < worstQuality) worstQuality = bestQuality
            weightedSum += bestWeighted
        }
        val score = (weightedSum / words.size).toInt().coerceIn(0, 100)
        return Match(score = score, quality = worstQuality)
    }

    /**
     * 0–100 quality of matching [needle] inside [haystack] (case-insensitive).
     *
     * Exact / prefix / substring get high scores; subsequence (fuzzy) is
     * lower but still accepted when compact.
     */
    fun scoreOne(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        val h = haystack.lowercase()
        val n = needle.lowercase()
        if (h == n) return 100
        if (h.startsWith(n)) return 96
        val idx = h.indexOf(n)
        if (idx >= 0) {
            val boundary = idx == 0 || !h[idx - 1].isLetterOrDigit()
            return if (boundary) 90 else 82
        }
        return subsequenceScore(h, n)
    }

    /**
     * Greedy subsequence: every needle char must appear in order. Compact
     * runs score higher than scattered ones.
     */
    private fun subsequenceScore(haystack: String, needle: String): Int {
        var hi = 0
        var consecutive = 0
        var maxRun = 0
        var first = -1
        var last = -1
        for (ch in needle) {
            var found = -1
            var i = hi
            while (i < haystack.length) {
                if (haystack[i] == ch) {
                    found = i
                    break
                }
                i++
            }
            if (found < 0) return 0
            if (first < 0) first = found
            if (last >= 0 && found == last + 1) {
                consecutive++
                if (consecutive > maxRun) maxRun = consecutive
            } else {
                consecutive = 1
                if (maxRun < 1) maxRun = 1
            }
            last = found
            hi = found + 1
        }
        val span = (last - first + 1).coerceAtLeast(needle.length)
        val density = needle.length.toDouble() / span
        val runBonus = maxRun.toDouble() / needle.length
        val raw = (55.0 + 30.0 * density + 15.0 * runBonus).toInt()
        return raw.coerceIn(0, 78)
    }
}
