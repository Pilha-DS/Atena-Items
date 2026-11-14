/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Agenda
 *
 * Agenda is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Agenda is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Agenda.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.agenda.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * XDG base directories. Agenda for Linux keeps its database in XDG_DATA_HOME,
 * its sync settings in XDG_CONFIG_HOME and temporary data in XDG_CACHE_HOME.
 */
object Xdg {

    private val home: Path = Paths.get(System.getenv("HOME") ?: System.getProperty("user.home"))

    private fun base(env: String, fallback: String): Path {
        val value = System.getenv(env)?.takeIf { it.isNotBlank() }
        return if (value != null) Paths.get(value) else home.resolve(fallback)
    }

    val dataHome: Path = base("XDG_DATA_HOME", ".local/share")
    val configHome: Path = base("XDG_CONFIG_HOME", ".config")
    val cacheHome: Path = base("XDG_CACHE_HOME", ".cache")

    val dataDir: Path = dataHome.resolve("agenda")
    val configDir: Path = configHome.resolve("agenda")
    val cacheDir: Path = cacheHome.resolve("agenda")

    val databaseFile: Path = dataDir.resolve("agenda.db")
    val configFile: Path = configDir.resolve("config.json")
    val notifyFile: Path = configDir.resolve("notify.json")
    val windowFile: Path = configDir.resolve("window.json")

    /** Entry that starts the app hidden at login (notifications, warm window). */
    val autostartFile: Path = configHome.resolve("autostart/agenda.desktop")

    /** KDE's palette and accent color, used when present. */
    val kdeGlobalsFile: Path = configHome.resolve("kdeglobals")

    fun ensureDirectories() {
        listOf(dataDir, configDir, cacheDir).forEach { Files.createDirectories(it) }
    }
}
