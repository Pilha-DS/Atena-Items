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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lets the shell ask every open editor to persist what the user typed.
 *
 * The widget hides itself as soon as it loses focus, and it can be hidden
 * while a note is still being written, so the debounced auto-save needs a
 * way to be flushed immediately instead of waiting for its timer.
 *
 * [flushAllBlocking] exists for the moments where the process is about to
 * disappear (hiding the general note, quitting, logout/reboot). A plain
 * `launch` there would be killed before the write reached SQLite, which is
 * exactly how a long note can be typed and still never be stored.
 */
object AutoSave {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val flushers = CopyOnWriteArrayList<suspend () -> Unit>()

    /** Registers a flush callback and returns the function that removes it. */
    fun register(flusher: suspend () -> Unit): () -> Unit {
        flushers.add(flusher)
        return { flushers.remove(flusher) }
    }

    /** Persists pending changes of every registered editor in the background. */
    fun flushAll() {
        if (flushers.isEmpty()) return
        scope.launch { flushNow() }
    }

    /**
     * Persists pending changes and only returns when they are written (or the
     * timeout expires). Safe to call from the AWT thread, a shutdown hook or
     * from a window close handler.
     */
    fun flushAllBlocking(timeoutMs: Long = 3_000L) {
        if (flushers.isEmpty()) return
        runBlocking {
            withTimeoutOrNull(timeoutMs) { flushNow() }
        }
    }

    private suspend fun flushNow() {
        for (flusher in flushers.toList()) {
            runCatching { flusher() }
                .onFailure { Diagnostics.log("autosave flush failed: ${it.message}") }
        }
    }
}
