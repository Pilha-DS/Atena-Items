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

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Desktop IPC for the KDE widget integration.
 *
 * A Plasma plasmoid (QML) cannot call into the JVM, so the widget talks to the
 * app through plain files inside the runtime directory:
 *
 *  - `agenda.toggle` : touched by `agenda-toggle` or the plasmoid. The running
 *    app watches its mtime and flips visibility.
 *  - `agenda.sync` : touched by the plasmoid's "sync" action.
 *  - `agenda.status.json` : upcoming events and sync state, read by the widget.
 *  - `agenda.anchor` : screen rectangle of the panel icon, so the window opens
 *    right below it.
 *  - `agenda.lock` : single-instance lock, so launching again toggles instead
 *    of opening a second window.
 *
 * The directory is published in `~/.config/agenda/ipc-dir` so the helper script
 * and the plasmoid do not have to guess.
 */
object IpcBridge {

    private val userName: String = System.getProperty("user.name") ?: "user"

    private val runtimeDir: Path = resolveRuntimeDir()

    private val ipcDirFile: Path = Xdg.configDir.resolve("ipc-dir")

    val toggleFile: Path = runtimeDir.resolve("agenda.toggle")
    val syncFile: Path = runtimeDir.resolve("agenda.sync")
    val statusFile: Path = runtimeDir.resolve("agenda.status.json")
    private val anchorFile: Path = runtimeDir.resolve("agenda.anchor")
    private val toggleAckFile: Path = runtimeDir.resolve("agenda.toggle.ack")
    private val lockFile: Path = runtimeDir.resolve("agenda.lock")

    private var heldLock: FileLock? = null

    /** Screen rectangle of the panel icon that asked for the window. */
    data class Anchor(val x: Int, val y: Int, val width: Int, val height: Int)

    private fun resolveRuntimeDir(): Path {
        val candidates = buildList {
            System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }
                ?.let { add(Paths.get(it).resolve("agenda")) }
            add(Xdg.cacheDir.resolve("run"))
            add(Paths.get(System.getProperty("java.io.tmpdir"), "agenda-$userName"))
        }
        for (candidate in candidates) {
            val ok = runCatching {
                Files.createDirectories(candidate)
                val probe = candidate.resolve(".agenda-write-test")
                Files.writeString(probe, "1")
                Files.deleteIfExists(probe)
                true
            }.getOrDefault(false)
            if (ok) return candidate
        }
        return candidates.last()
    }

    fun publishRuntimeDir() {
        runCatching {
            Files.createDirectories(Xdg.configDir)
            Files.writeString(ipcDirFile, runtimeDir.toString())
        }
    }

    /** True when this process is the first (and only) Agenda instance. */
    fun acquireSingleInstanceLock(): Boolean {
        if (heldLock?.isValid == true) return true
        return runCatching {
            Files.createDirectories(runtimeDir)
            val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = channel.tryLock()
            if (lock == null) {
                channel.close()
                false
            } else {
                heldLock = lock
                true
            }
        }.getOrDefault(false)
    }

    /** Asks the running instance to show/hide. */
    fun requestToggle() {
        runCatching {
            Files.createDirectories(runtimeDir)
            Files.writeString(toggleFile, System.nanoTime().toString())
        }
    }

    fun toggleStamp(): Long = stampOf(toggleFile)

    /**
     * Content of the toggle file, which is unique per click.
     *
     * A timestamp of the file is not enough to tell "the user just clicked"
     * from "the last run already handled this click": a click that lands while
     * the JVM is still starting is only a few milliseconds newer than the
     * process. Comparing contents against the last one we acknowledged does
     * not have that ambiguity.
     */
    fun toggleToken(): String = runCatching {
        if (!Files.exists(toggleFile)) "" else Files.readString(toggleFile).trim()
    }.getOrDefault("")

    private fun toggleAck(): String = runCatching {
        if (!Files.exists(toggleAckFile)) "" else Files.readString(toggleAckFile).trim()
    }.getOrDefault("")

    /** True when [token] is a request this app has not handled yet. */
    fun isFreshToggle(token: String): Boolean = token.isNotEmpty() && token != toggleAck()

    fun acknowledgeToggle(token: String) {
        if (token.isEmpty()) return
        runCatching { Files.writeString(toggleAckFile, token) }
    }

    fun syncStamp(): Long = stampOf(syncFile)

    private fun stampOf(file: Path): Long = runCatching {
        Files.getLastModifiedTime(file).toMillis()
    }.getOrDefault(0L)

    /**
     * Geometry of the panel icon, written right before the toggle request.
     * Ignored when stale, so opening from the menu does not jump the window.
     */
    fun loadAnchor(maxAgeMillis: Long = 20_000): Anchor? = runCatching {
        if (!Files.exists(anchorFile)) return null
        val age = System.currentTimeMillis() - Files.getLastModifiedTime(anchorFile).toMillis()
        if (age > maxAgeMillis) return null
        val parts = Files.readString(anchorFile).trim().split(Regex("\\s+"))
        if (parts.size < 4) return null
        Anchor(
            x = parts[0].toInt(),
            y = parts[1].toInt(),
            width = parts[2].toInt(),
            height = parts[3].toInt()
        )
    }.getOrNull()

    /** Publishes the JSON the plasmoid reads (and `agenda-toggle status` prints). */
    fun publishStatus(json: String) {
        runCatching { Files.writeString(statusFile, json) }
    }

    fun readStatus(): String? = runCatching {
        if (!Files.exists(statusFile)) null else Files.readString(statusFile)
    }.getOrNull()
}
