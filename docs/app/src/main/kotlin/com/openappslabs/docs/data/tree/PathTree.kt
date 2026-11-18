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

import androidx.compose.runtime.Immutable

/** One node of a `a/b/c` hierarchy, with the count of its whole subtree. */
@Immutable
data class PathNode(
    val path: String,
    val name: String,
    val depth: Int,
    val count: Int,
    val children: List<PathNode>
)

/**
 * Turns a flat list of slash-separated paths into a tree.
 *
 * Used twice with different data: the tag tree in the sidebar and the folder
 * tree of the library. Both are "path with `/`", so one implementation, tested
 * once, serves both.
 *
 * Two rules that matter:
 *  - a parent that has no row of its own still exists in the tree (a tag can be
 *    deleted while its children stay, and a folder has no row at all);
 *  - the count of a node includes its descendants, because selecting
 *    `academico` is supposed to show what is under `academico/faculdade`, and
 *    selecting a folder is supposed to show its subfolders.
 */
object PathTree {

    fun build(paths: Collection<String>, exactCounts: Map<String, Int> = emptyMap()): List<PathNode> {
        val nodes = LinkedHashMap<String, MutableNode>()

        fun ensure(path: String): MutableNode {
            nodes[path]?.let { return it }
            val node = MutableNode(
                path = path,
                name = path.substringAfterLast('/'),
                depth = path.count { it == '/' }
            )
            nodes[path] = node
            val parentPath = path.substringBeforeLast('/', "")
            if (parentPath.isNotEmpty()) ensure(parentPath).children.add(node)
            return node
        }

        paths.filter { it.isNotEmpty() }.forEach(::ensure)

        // Deepest first, so a parent already sees the totals of its children.
        nodes.values.sortedByDescending { it.depth }.forEach { node ->
            node.count = (exactCounts[node.path] ?: 0) + node.children.sumOf { it.count }
        }

        fun freeze(node: MutableNode): PathNode = PathNode(
            path = node.path,
            name = node.name,
            depth = node.depth,
            count = node.count,
            children = node.children.sortedBy { it.name }.map(::freeze)
        )

        return nodes.values
            .filter { it.depth == 0 }
            .sortedBy { it.name }
            .map(::freeze)
    }

    /** Depth-first order: exactly the order a lazy list needs. */
    fun flatten(nodes: List<PathNode>): List<PathNode> = buildList {
        fun visit(node: PathNode) {
            add(node)
            node.children.forEach(::visit)
        }
        nodes.forEach(::visit)
    }

    /**
     * The same depth-first order, but a node only shows its children when its
     * path is in [expanded].
     *
     * The sidebar uses this one: it starts with every branch closed, so a
     * library with deep folders shows one line per root instead of the whole
     * tree. [flatten] stays for the places that want everything (the tag
     * picker lists all tags to choose from).
     */
    fun visible(nodes: List<PathNode>, expanded: Set<String>): List<PathNode> = buildList {
        fun visit(node: PathNode) {
            add(node)
            if (node.path in expanded) node.children.forEach(::visit)
        }
        nodes.forEach(::visit)
    }

    /**
     * The folders directly below [path]; the roots when [path] is empty.
     *
     * This is what a folder browser shows at one level: the children of the
     * place it is standing in, not the whole tree.
     */
    fun childrenOf(nodes: List<PathNode>, path: String): List<PathNode> =
        if (path.isEmpty()) nodes else flatten(nodes).firstOrNull { it.path == path }?.children.orEmpty()

    /** True when [path] is a folder of the tree; the root always is. */
    fun contains(nodes: List<PathNode>, path: String): Boolean =
        path.isEmpty() || flatten(nodes).any { it.path == path }

    /**
     * The branches that have to be open for [path] to be visible, outermost
     * first (`a/b/c` -> `a`, `a/b`). The node itself is not included.
     */
    fun ancestors(path: String): List<String> = buildList {
        var cut = path.lastIndexOf('/')
        while (cut > 0) {
            add(path.substring(0, cut))
            cut = path.lastIndexOf('/', cut - 1)
        }
    }.asReversed()

    private class MutableNode(
        val path: String,
        val name: String,
        val depth: Int,
        var count: Int = 0,
        val children: MutableList<MutableNode> = mutableListOf()
    )
}
