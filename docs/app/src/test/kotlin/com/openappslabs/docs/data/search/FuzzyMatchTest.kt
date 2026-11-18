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

import kotlin.test.Test
import kotlin.test.assertTrue

class FuzzyMatchTest {

    @Test
    fun `exact match beats a scattered subsequence`() {
        val exact = FuzzyMatch.scoreOne("nota-fiscal", "nota-fiscal")
        val fuzzy = FuzzyMatch.scoreOne("nota-fiscal-setembro-2024", "nfs")
        assertTrue(exact > fuzzy)
        assertTrue(exact >= FuzzyMatch.MIN_QUALITY)
    }

    @Test
    fun `weighted name beats a weak content field`() {
        val match = FuzzyMatch.scoreWeighted(
            listOf(
                FuzzyMatch.WeightedField("relatorio-anual.pdf", 1.0),
                FuzzyMatch.WeightedField("texto longo sem a palavra do nome", 0.6)
            ),
            "relatorio"
        )
        assertTrue(match.accepted())
        assertTrue(match.score >= 80)
    }
}
