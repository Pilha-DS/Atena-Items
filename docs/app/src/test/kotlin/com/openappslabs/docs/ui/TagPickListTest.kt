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

import com.openappslabs.docs.data.tree.PathNode
import kotlin.test.Test
import kotlin.test.assertEquals

class TagPickListTest {

    private fun node(path: String, depth: Int = 0) = PathNode(
        path = path,
        name = path.substringAfterLast('/'),
        depth = depth,
        count = 0,
        children = emptyList()
    )

    @Test
    fun `selected tags float above years so they stay visible`() {
        val prepared = TagPickList.prepare(
            nodes = listOf(
                node("2018"),
                node("2026"),
                node("contrato"),
                node("nfe"),
                node("unip")
            ),
            extras = emptyList(),
            selected = setOf("contrato"),
            filter = ""
        )

        assertEquals(
            listOf("contrato", "2018", "2026", "nfe", "unip"),
            prepared.nodes.map { it.path }
        )
        // Tree order of the unchecked rows is preserved (years stay before nfe).
        assertEquals(
            listOf("2018", "2026", "nfe", "unip"),
            prepared.nodes.drop(1).map { it.path }
        )
    }

    @Test
    fun `filter keeps matching tags and extras`() {
        val prepared = TagPickList.prepare(
            nodes = listOf(node("2018"), node("contrato"), node("nfe")),
            extras = listOf("nfe/2026"),
            selected = emptySet(),
            filter = "nf"
        )

        assertEquals(listOf("nfe"), prepared.nodes.map { it.path })
        assertEquals(listOf("nfe/2026"), prepared.extras)
    }

    @Test
    fun `filter still shows a selected tag that does not match the query`() {
        val prepared = TagPickList.prepare(
            nodes = listOf(node("contrato"), node("termo"), node("unip")),
            extras = listOf("novo"),
            selected = setOf("termo"),
            filter = "contrato"
        )

        assertEquals(listOf("termo", "contrato"), prepared.nodes.map { it.path })
        assertEquals(listOf("novo"), prepared.extras)
    }
}
