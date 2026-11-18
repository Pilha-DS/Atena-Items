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
 * XDG base directories.
 *
 * The document files are never stored here: the library root lives in the
 * user's Documents folder (see [DocsPaths]). This holds only what belongs to
 * the app — the index database, settings and the runtime handshake files.
 */
object Xdg {

    val home: Path = Paths.get(System.getenv("HOME") ?: System.getProperty("user.home"))

    private fun base(env: String, fallback: String): Path {
        val value = System.getenv(env)?.takeIf { it.isNotBlank() }
        return if (value != null) Paths.get(value) else home.resolve(fallback)
    }

    val dataHome: Path = base("XDG_DATA_HOME", ".local/share")
    val configHome: Path = base("XDG_CONFIG_HOME", ".config")
    val cacheHome: Path = base("XDG_CACHE_HOME", ".cache")

    val dataDir: Path = dataHome.resolve("docs")
    val configDir: Path = configHome.resolve("docs")
    val cacheDir: Path = cacheHome.resolve("docs")

    /** Index of the library: paths, tags and the versions seen. */
    val databaseFile: Path = dataDir.resolve("docs.db")

    /** One line: a library root chosen by the user instead of the default. */
    val rootOverrideFile: Path = configDir.resolve("root")

    /**
     * Optional model for tag suggestions: `provider=`, `url=`, `model=` and
     * `key=` lines. No file means the default Ollama address.
     *
     * Read-only by design: the app never writes this file, so the key never
     * passes through the window. Editing it is a deliberate act of the user.
     */
    val aiConfigFile: Path = configDir.resolve("ai")

    /** Published so the toggle helper and the plasmoid do not guess. */
    val ipcDirFile: Path = configDir.resolve("ipc-dir")

    /** KDE's palette, read once at startup. */
    val kdeGlobalsFile: Path = configHome.resolve("kdeglobals")

    /** Maps `XDG_DOCUMENTS_DIR` to the real folder, localized by the distro. */
    val userDirsFile: Path = configHome.resolve("user-dirs.dirs")

    fun ensureDirectories() {
        listOf(dataDir, configDir, cacheDir).forEach { Files.createDirectories(it) }
    }
}
