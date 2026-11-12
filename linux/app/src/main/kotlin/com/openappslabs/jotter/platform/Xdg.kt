/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.jotter.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * XDG base directories. Jotter for Linux keeps its database in XDG_DATA_HOME,
 * its settings in XDG_CONFIG_HOME and any temporary data in XDG_CACHE_HOME.
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

    val dataDir: Path = dataHome.resolve("jotter")
    val configDir: Path = configHome.resolve("jotter")
    val cacheDir: Path = cacheHome.resolve("jotter")

    /** Same file name used by the Android app, so backups stay compatible. */
    val databaseFile: Path = dataDir.resolve("jotter_db")
    val preferencesFile: Path = configDir.resolve("user_settings.preferences_pb")
    val windowStateFile: Path = configDir.resolve("window.properties")
    val pinFile: Path = configDir.resolve("lock.properties")

    val kdeGlobalsFile: Path = configHome.resolve("kdeglobals")

    fun ensureDirectories() {
        listOf(dataDir, configDir, cacheDir).forEach { Files.createDirectories(it) }
    }
}
