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

package com.openappslabs.docs.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where the library lives.
 *
 * The real tree is the source of truth: the app indexes files that are already
 * there and never moves, renames or copies them. The default root is a folder
 * of its own inside Documents (`~/Documentos/AtenaData/Docs`) so it does not
 * mix with the user's files; a `root` file in the config directory overrides
 * it with any absolute path.
 *
 * Resolution order, all of it testable without touching the real home:
 *  1. the override file, when it holds a non-blank path;
 *  2. `XDG_DOCUMENTS_DIR` from `~/.config/user-dirs.dirs`;
 *  3. `~/Documentos`, or `~/Documents` when that is the only one that exists.
 */
object DocsPaths {

    const val LIBRARY_SUBDIR = "AtenaData/Docs"

    private const val DOCUMENTS_KEY = "XDG_DOCUMENTS_DIR"

    /** The folder the user picked, or the default one, always resolved. */
    fun libraryRoot(home: Path, userDirsFile: Path, override: String? = null): Path {
        val base = override?.let { expand(it, home) } ?: documentsDir(home, userDirsFile)
        return base.resolve(LIBRARY_SUBDIR)
    }

    fun documentsDir(home: Path, userDirsFile: Path): Path {
        val declared = readUserDirs(userDirsFile)[DOCUMENTS_KEY]
        val fromFile = declared?.let { expand(it, home) }
        if (fromFile != null) return fromFile

        // No user-dirs.dirs (or no key): prefer the localized folder that
        // really exists before creating a new one next to it.
        val english = home.resolve("Documents")
        val portuguese = home.resolve("Documentos")
        return if (Files.isDirectory(english) && !Files.isDirectory(portuguese)) english else portuguese
    }

    /** Key/value pairs of `user-dirs.dirs`, quotes kept as they are. */
    fun readUserDirs(file: Path): Map<String, String> = runCatching {
        if (!Files.isRegularFile(file)) return@runCatching emptyMap()
        Files.readAllLines(file)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .mapNotNull { line ->
                val key = line.substringBefore('=').trim()
                val value = line.substringAfter('=').trim()
                if (key.isEmpty()) null else key to value
            }
            .toMap()
    }.getOrDefault(emptyMap())

    /**
     * Turns an XDG value into an absolute path.
     *
     * `user-dirs.dirs` writes `"$HOME/Documentos"`, and people type `~/Docs`
     * in the override file. Anything still relative is rejected instead of
     * resolved against the process working directory, which would make the
     * library depend on where the app happened to be launched from.
     */
    fun expand(value: String, home: Path): Path? {
        val trimmed = value.trim().removeSurrounding("\"")
        if (trimmed.isEmpty()) return null
        val expanded = when {
            trimmed == "\$HOME" -> home.toString()
            trimmed.startsWith("\$HOME/") -> home.toString() + trimmed.removePrefix("\$HOME")
            trimmed == "~" -> home.toString()
            trimmed.startsWith("~/") -> home.toString() + trimmed.removePrefix("~")
            else -> trimmed
        }
        val path = runCatching { Paths.get(expanded) }.getOrNull() ?: return null
        return if (path.isAbsolute) path.normalize() else null
    }

    /** Reads the override, creating the default root when it is missing. */
    fun ensureLibraryRoot(): Path {
        val root = libraryRoot(Xdg.home, Xdg.userDirsFile, readOverride())
        Files.createDirectories(root)
        return root
    }

    fun readOverride(): String? = runCatching {
        val file = Xdg.rootOverrideFile
        if (!Files.isRegularFile(file)) return@runCatching null
        Files.readString(file).trim().ifEmpty { null }
    }.getOrNull()
}
