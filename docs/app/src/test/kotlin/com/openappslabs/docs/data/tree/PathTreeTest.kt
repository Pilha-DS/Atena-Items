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

package com.openappslabs.docs.data.tree

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PathTreeTest {

    @Test
    fun `a single path produces its whole chain`() {
        val tree = PathTree.build(listOf("academico/faculdade/2026"))

        val root = tree.single()
        assertEquals("academico", root.path)
        assertEquals("faculdade", root.children.single().name)
        assertEquals("2026", root.children.single().children.single().name)
        assertEquals(2, root.children.single().children.single().depth)
    }

    @Test
    fun `roots are sorted by name`() {
        val tree = PathTree.build(listOf("pessoal", "academico", "meu"))

        assertEquals(listOf("academico", "meu", "pessoal"), tree.map { it.name })
    }

    @Test
    fun `a parent without a row still exists`() {
        val tree = PathTree.build(listOf("a/b/c"))

        assertEquals("a", tree.single().path)
        assertTrue(tree.single().children.isNotEmpty())
    }

    @Test
    fun `counts of children add up to the parent`() {
        val tree = PathTree.build(
            listOf("academico", "academico/faculdade", "academico/escola"),
            exactCounts = mapOf(
                "academico" to 1,
                "academico/faculdade" to 3,
                "academico/escola" to 2
            )
        )

        val root = tree.single()
        assertEquals(6, root.count)
        assertEquals(
            mapOf("escola" to 2, "faculdade" to 3),
            root.children.associate { it.name to it.count }
        )
    }

    @Test
    fun `a node with no document has count zero`() {
        val tree = PathTree.build(listOf("pessoal", "pessoal/rg"))

        assertEquals(0, tree.single().count)
    }

    @Test
    fun `childrenOf shows one level at a time`() {
        val tree = PathTree.build(listOf("a/b", "a/c/d", "e"))

        assertEquals(listOf("a", "e"), PathTree.childrenOf(tree, "").map { it.path })
        assertEquals(listOf("a/b", "a/c"), PathTree.childrenOf(tree, "a").map { it.path })
        assertEquals(listOf("a/c/d"), PathTree.childrenOf(tree, "a/c").map { it.path })
        assertTrue(PathTree.childrenOf(tree, "a/b").isEmpty())
    }

    @Test
    fun `childrenOf of a folder that is not in the tree is empty`() {
        val tree = PathTree.build(listOf("a"))

        assertTrue(PathTree.childrenOf(tree, "x/y").isEmpty())
    }

    @Test
    fun `contains knows the root and the folders of the disk`() {
        val tree = PathTree.build(listOf("a/b", "e"))

        assertTrue(PathTree.contains(tree, ""))
        assertTrue(PathTree.contains(tree, "a/b"))
        assertFalse(PathTree.contains(tree, "a/c"))
    }

    @Test
    fun `flatten is depth first`() {
        val tree = PathTree.build(listOf("a/b", "a/c", "d"))

        assertEquals(
            listOf("a", "a/b", "a/c", "d"),
            PathTree.flatten(tree).map { it.path }
        )
    }

    @Test
    fun `duplicated and empty paths are harmless`() {
        val tree = PathTree.build(listOf("", "a", "a", "a/b"))

        assertEquals(listOf("a"), tree.map { it.path })
        assertEquals(listOf("a", "a/b"), PathTree.flatten(tree).map { it.path })
    }

    @Test
    fun `an empty list is an empty tree`() {
        assertTrue(PathTree.build(emptyList()).isEmpty())
        assertTrue(PathTree.flatten(emptyList()).isEmpty())
    }

    @Test
    fun `visible hides the children of a closed branch`() {
        val tree = PathTree.build(listOf("a/b", "a/c", "d"))

        assertEquals(listOf("a", "d"), PathTree.visible(tree, emptySet()).map { it.path })
    }

    @Test
    fun `visible opens one level per expanded path`() {
        val tree = PathTree.build(listOf("a/b/c", "a/d", "e"))

        assertEquals(
            listOf("a", "a/b", "a/d", "e"),
            PathTree.visible(tree, setOf("a")).map { it.path }
        )
        assertEquals(
            listOf("a", "a/b", "a/b/c", "a/d", "e"),
            PathTree.visible(tree, setOf("a", "a/b")).map { it.path }
        )
    }

    @Test
    fun `visible keeps the depth first order of flatten`() {
        val tree = PathTree.build(listOf("a/b", "a/c", "d/e"))

        assertEquals(
            PathTree.flatten(tree).map { it.path },
            PathTree.visible(tree, setOf("a", "d")).map { it.path }
        )
    }

    @Test
    fun `visible ignores paths that are not in the tree`() {
        val tree = PathTree.build(listOf("a/b"))

        assertEquals(listOf("a"), PathTree.visible(tree, setOf("x", "a/b")).map { it.path })
    }

    @Test
    fun `ancestors are outermost first and leave out the node`() {
        assertEquals(listOf("a", "a/b"), PathTree.ancestors("a/b/c"))
        assertEquals(listOf("a"), PathTree.ancestors("a/b"))
        assertTrue(PathTree.ancestors("a").isEmpty())
        assertTrue(PathTree.ancestors("").isEmpty())
    }

    @Test
    fun `folder shaped paths count files of the folder itself`() {
        // `faculdade` has one file directly and `faculdade/2026` has two.
        val tree = PathTree.build(
            listOf("faculdade", "faculdade/2026"),
            exactCounts = mapOf("faculdade" to 1, "faculdade/2026" to 2)
        )

        assertEquals(3, tree.single().count)
        assertEquals(2, tree.single().children.single().count)
    }
}
