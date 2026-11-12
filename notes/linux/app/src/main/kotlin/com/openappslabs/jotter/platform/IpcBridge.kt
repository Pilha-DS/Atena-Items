/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Jotter is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Jotter.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.jotter.platform

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
 * shell through plain files:
 *
 *  - `jotter.toggle` : touched by `jotter-toggle` / the plasmoid / a second
 *    launch. The running app watches its mtime and flips visibility.
 *  - `jotter.status.json` : note count and last title, read by the plasmoid.
 *  - `jotter.lock` : single-instance lock, so launching again toggles instead
 *    of opening a second window.
 *
 * The directory used is published in `~/.config/jotter/ipc-dir` so the helper
 * script and the plasmoid do not have to guess (XDG_RUNTIME_DIR is not always
 * writable, e.g. inside containers).
 */
object IpcBridge {

    private val userName: String = System.getProperty("user.name") ?: "user"

    private val runtimeDir: Path = resolveRuntimeDir()

    private val ipcDirFile: Path = Xdg.configDir.resolve("ipc-dir")

    val toggleFile: Path = runtimeDir.resolve("jotter.toggle")
    val statusFile: Path = runtimeDir.resolve("jotter.status.json")
    private val anchorFile: Path = runtimeDir.resolve("jotter.anchor")
    private val lockFile: Path = runtimeDir.resolve("jotter.lock")

    private var heldLock: FileLock? = null

    /** Screen rectangle of the panel icon that asked for the window. */
    data class Anchor(val x: Int, val y: Int, val width: Int, val height: Int)

    private fun resolveRuntimeDir(): Path {
        val candidates = buildList {
            System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }
                ?.let { add(Paths.get(it).resolve("jotter")) }
            add(Xdg.cacheDir.resolve("run"))
            add(Paths.get(System.getProperty("java.io.tmpdir"), "jotter-$userName"))
        }
        for (candidate in candidates) {
            val ok = runCatching {
                Files.createDirectories(candidate)
                val probe = candidate.resolve(".jotter-write-test")
                Files.writeString(probe, "1")
                Files.deleteIfExists(probe)
                true
            }.getOrDefault(false)
            if (ok) return candidate
        }
        return candidates.last()
    }

    /** Called once at startup: records where the helper script should look. */
    fun publishRuntimeDir() {
        runCatching {
            Files.createDirectories(Xdg.configDir)
            Files.writeString(ipcDirFile, runtimeDir.toString())
        }
    }

    /**
     * Returns true when this process is the first (and only) Jotter instance.
     * The lock is kept for the lifetime of the process.
     */
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

    /** Asks the already running instance to show/hide, used by `jotter-toggle`. */
    fun requestToggle() {
        runCatching {
            Files.createDirectories(runtimeDir)
            Files.writeString(toggleFile, System.currentTimeMillis().toString())
        }
    }

    /** Last modification stamp of the toggle file; 0 when it does not exist yet. */
    fun toggleStamp(): Long = runCatching {
        Files.getLastModifiedTime(toggleFile).toMillis()
    }.getOrDefault(0L)

    /**
     * Geometry of the panel icon, written right before the toggle request.
     * Ignored when stale, so opening Jotter from the menu does not suddenly
     * jump the window next to a panel icon.
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

    /** Publishes a small JSON blob so the plasmoid can show the note counter. */
    fun publishStatus(count: Int, lastTitle: String?) {
        val escaped = (lastTitle ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
        val json = "{\"count\":$count,\"last\":\"$escaped\"}"
        runCatching { Files.writeString(statusFile, json) }
    }
}
