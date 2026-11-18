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

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Desktop IPC with the toggle helper and, later, the Plasma plasmoid.
 *
 * A plasmoid cannot call into the JVM, so both talk through plain files in the
 * runtime directory:
 *
 *  - `docs.toggle`: content is unique per click. The running app watches it and
 *    flips visibility; the ack file remembers the last token it handled, so a
 *    click that lands while the JVM is still starting is not lost (comparing
 *    modification times would be ambiguous there).
 *  - `docs.lock`: single-instance lock. A second launch becomes a show/hide
 *    request instead of a second window.
 *
 * The directory is published in `~/.config/docs/ipc-dir` so the helper script
 * does not have to repeat the fallback rules.
 */
object IpcBridge {

    private val userName: String = System.getProperty("user.name") ?: "user"

    private val runtimeDir: Path = resolveRuntimeDir()

    private val ipcDirFile: Path = Xdg.ipcDirFile

    val toggleFile: Path = runtimeDir.resolve("docs.toggle")
    private val toggleAckFile: Path = runtimeDir.resolve("docs.toggle.ack")
    private val lockFile: Path = runtimeDir.resolve("docs.lock")

    private var heldLock: FileLock? = null

    private fun resolveRuntimeDir(): Path {
        val candidates = buildList {
            System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }
                ?.let { add(Paths.get(it).resolve("docs")) }
            add(Xdg.cacheDir.resolve("run"))
            add(Paths.get(System.getProperty("java.io.tmpdir"), "docs-$userName"))
        }
        for (candidate in candidates) {
            val ok = runCatching {
                Files.createDirectories(candidate)
                val probe = candidate.resolve(".docs-write-test")
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

    /** True when this process is the first (and only) Docs instance. */
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

    /** Content of the toggle file: one distinct value per click. */
    fun toggleToken(): String = runCatching {
        if (!Files.isRegularFile(toggleFile)) "" else Files.readString(toggleFile).trim()
    }.getOrDefault("")

    private fun acknowledgedToken(): String = runCatching {
        if (!Files.isRegularFile(toggleAckFile)) "" else Files.readString(toggleAckFile).trim()
    }.getOrDefault("")

    /** True when [token] is a request this app has not handled yet. */
    fun isFreshToggle(token: String): Boolean =
        token.isNotEmpty() && token != acknowledgedToken()

    fun acknowledgeToggle(token: String) {
        if (token.isEmpty()) return
        runCatching { Files.writeString(toggleAckFile, token) }
    }
}
