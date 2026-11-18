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

package com.openappslabs.docs.data.repository

import com.openappslabs.docs.data.model.DocTag
import com.openappslabs.docs.data.model.Tag
import com.openappslabs.docs.data.model.TagAlias
import com.openappslabs.docs.data.source.FolderTagDao
import com.openappslabs.docs.data.source.TagAliasDao
import com.openappslabs.docs.data.source.TagDao
import com.openappslabs.docs.data.tags.TagAliases
import com.openappslabs.docs.data.tags.TagPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Creates tags and applies them to documents.
 *
 * Writing a tag creates its whole chain (`academico/faculdade` also creates
 * `academico`), so the tree is complete even when the user types only the leaf
 * and filtering by the parent keeps working.
 *
 * Every call comes from a click: the machine proposes, the person confirms.
 */
class TagRepository(
    private val dao: TagDao,
    /** Folder inheritance links; cleaned when a tag tree is deleted. */
    private val folderTags: FolderTagDao? = null,
    /** Search nicknames of a tag; cleaned with the tag tree. */
    private val aliases: TagAliasDao? = null
) {

    /**
     * Ensures every prefix of [path] exists and returns the created tag's id,
     * or null when no row could be found or created.
     */
    suspend fun ensure(path: String): Long? = withContext(Dispatchers.IO) {
        var id: Long? = null
        for (prefix in TagPaths.chain(path)) {
            val existing = dao.findByPath(prefix)
            if (existing != null) {
                id = existing.id
            } else {
                val inserted = dao.insert(Tag(path = prefix, name = TagPaths.name(prefix)))
                // insert with IGNORE returns -1 when another write beat us to it.
                id = if (inserted > 0) inserted else dao.findByPath(prefix)?.id
            }
        }
        id
    }

    /** Applies [path] to [docId], creating the tag if needed. */
    suspend fun apply(docId: Long, path: String): Boolean = withContext(Dispatchers.IO) {
        val tagId = ensure(path) ?: return@withContext false
        dao.assign(DocTag(docId = docId, tagId = tagId))
        true
    }

    suspend fun remove(docId: Long, path: String): Boolean = withContext(Dispatchers.IO) {
        val tag = dao.findByPath(path) ?: return@withContext false
        dao.unassign(docId, tag.id)
        true
    }

    /**
     * Deletes [path] and everything below it, from the tags and from the
     * documents that carry them.
     *
     * The dialog asks once for the whole subtree on purpose: a hierarchy whose
     * parent is gone but whose children survive is a tree the user cannot
     * explain, and `PathTree` would show the parent back as an empty row.
     * [TagRemoval.documents] is what the snackbar reports — "removida de 12
     * documentos" is the sentence that tells the person what changed, since the
     * tag itself is not visible anywhere else.
     */
    suspend fun removeTree(path: String): TagRemoval = withContext(Dispatchers.IO) {
        val normalized = TagPaths.normalize(path) ?: return@withContext TagRemoval(0, 0)
        val tags = dao.subtree(normalized, TagPaths.descendantPattern(normalized))
        if (tags.isEmpty()) return@withContext TagRemoval(0, 0)
        val ids = tags.map { it.id }
        val documents = dao.countAssignments(ids)
        // A deleted tag must not keep handing itself out through a folder.
        folderTags?.deleteByTagIds(ids)
        aliases?.deleteByTagIds(ids)
        dao.unassignTags(ids)
        dao.deleteTags(ids)
        TagRemoval(tags = ids.size, documents = documents)
    }

    /**
     * Renames [from] to [to], including every tag under it.
     *
     * Assignments and aliases stay: they hang on the tag id. Returns false when
     * the path is invalid, the source is missing, the target is already taken,
     * or [to] would sit under [from] (a cycle).
     */
    suspend fun rename(from: String, to: String): Boolean = withContext(Dispatchers.IO) {
        val oldPath = TagPaths.normalize(from) ?: return@withContext false
        val newPath = TagPaths.normalize(to) ?: return@withContext false
        if (oldPath == newPath) return@withContext true
        if (newPath.startsWith("$oldPath${TagPaths.SEPARATOR}")) return@withContext false
        if (dao.findByPath(oldPath) == null) return@withContext false

        val subtree = dao.subtree(oldPath, TagPaths.descendantPattern(oldPath))
        for (tag in subtree) {
            val mapped = remapPath(oldPath, newPath, tag.path)
            val clash = dao.findByPath(mapped)
            if (clash != null && clash.id != tag.id) return@withContext false
        }

        val parent = newPath.substringBeforeLast(TagPaths.SEPARATOR, "")
        if (parent.isNotEmpty()) ensure(parent)

        // Deepest first: renaming `a` → `b` must not briefly collide with `a/x`.
        for (tag in subtree.sortedByDescending { it.path.length }) {
            val mapped = remapPath(oldPath, newPath, tag.path)
            dao.updatePath(tag.id, mapped, TagPaths.name(mapped))
        }
        true
    }

    /** `academico/faculdade` renamed with `academico` → `estudos` becomes `estudos/faculdade`. */
    internal fun remapPath(oldPath: String, newPath: String, path: String): String = when {
        path == oldPath -> newPath
        path.startsWith("$oldPath${TagPaths.SEPARATOR}") -> newPath + path.removePrefix(oldPath)
        else -> path
    }

    /** Aliases of every tag, keyed by tag path — for the universal search. */
    fun observeAliasesByPath(): Flow<Map<String, List<String>>> {
        val aliases = aliases ?: return flowOf(emptyMap())
        return aliases.observeRows().map { rows ->
            rows.groupBy({ it.tagPath }, { it.alias })
        }
    }

    fun observeAliasesOf(tagPath: String): Flow<List<String>> {
        val aliases = aliases ?: return flowOf(emptyList())
        return aliases.observeOf(tagPath).map { rows -> rows.map { it.alias } }
    }

    suspend fun aliasesOf(tagPath: String): List<String> = withContext(Dispatchers.IO) {
        val path = TagPaths.normalize(tagPath) ?: return@withContext emptyList()
        aliases?.aliasesOf(path).orEmpty()
    }

    /**
     * Adds a search nickname to [tagPath].
     *
     * Returns false when the tag is missing, the text is invalid, the alias
     * only repeats the tag name, or the insert was ignored as a duplicate.
     */
    suspend fun addAlias(tagPath: String, raw: String): Boolean = withContext(Dispatchers.IO) {
        val path = TagPaths.normalize(tagPath) ?: return@withContext false
        val alias = TagAliases.normalize(raw) ?: return@withContext false
        if (TagAliases.redundant(alias, path)) return@withContext false
        val tag = dao.findByPath(path) ?: return@withContext false
        val aliases = aliases ?: return@withContext false
        val inserted = aliases.insert(TagAlias(tagId = tag.id, alias = alias))
        inserted > 0
    }

    suspend fun removeAlias(tagPath: String, raw: String): Boolean = withContext(Dispatchers.IO) {
        val path = TagPaths.normalize(tagPath) ?: return@withContext false
        val alias = TagAliases.normalize(raw) ?: return@withContext false
        val tag = dao.findByPath(path) ?: return@withContext false
        val aliases = aliases ?: return@withContext false
        aliases.delete(tag.id, alias)
        true
    }
}

/** What [TagRepository.removeTree] took out: tags of the subtree and assignments. */
data class TagRemoval(val tags: Int, val documents: Int)
