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

import com.openappslabs.jotter.data.source.DatabaseFactory
import com.openappslabs.jotter.di.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Persists the general note (scratchpad) safely.
 *
 * Typing is submitted on every change instead of waiting for a debounce to
 * expire: a debounce that restarts on each keystroke can leave the whole text
 * unsaved, which is how the note was lost before. Writes are serialized on a
 * single thread and coalesced, so a burst of keystrokes produces one write of
 * the newest text instead of a queue of stale ones.
 *
 * The same text is mirrored to `scratchpad-recovery.txt`. If the database ever
 * comes back empty (corrupted file, wiped schema) the note can still be
 * restored on the next start.
 */
object ScratchpadSaver {

    private data class Write(val noteId: Int, val text: String)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jotter-scratchpad-save").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

    private val queue = Channel<Write>(Channel.CONFLATED)

    /** Text known to be in the database, used to skip redundant writes. */
    private val persisted = AtomicReference<String?>(null)

    /** Text that could not be written; retried instead of being dropped. */
    private val failed = AtomicReference<Write?>(null)

    private var writesSinceCheckpoint = 0

    init {
        scope.launch {
            for (write in queue) {
                if (store(write)) {
                    failed.set(null)
                    maybeCheckpoint()
                } else {
                    failed.set(write)
                    delay(RETRY_DELAY_MS)
                    queue.trySend(write)
                }
            }
        }
        // A checkpoint copies the WAL back into the main database file, so the
        // latest text does not live only in a side file that a hard reset can
        // drop. Runs on the same single thread as the writes.
        scope.launch {
            while (isActive) {
                delay(CHECKPOINT_INTERVAL_MS)
                maybeCheckpoint()
            }
        }
    }

    /** Queues the newest text; identical consecutive values are ignored. */
    fun submit(noteId: Int, text: String) {
        val write = Write(noteId, text)
        if (persisted.get() == text && failed.get() == null) return
        queue.trySend(write)
    }

    /**
     * Records text that is already in the database, such as right after the
     * editor opened and read it. Without this, opening Jotter wrote the whole
     * note back (an fsync with `synchronous = FULL`) even though nothing was
     * typed.
     */
    fun markPersisted(text: String) {
        persisted.set(text)
    }

    /**
     * Writes [text] and waits (bounded) for it to reach SQLite, then
     * checkpoints. Used when the note is hidden or the app is closing.
     */
    fun flushBlocking(noteId: Int?, text: String, timeoutMs: Long = 2_500L): Boolean {
        val id = noteId ?: return true
        val write = Write(id, text)
        val job = scope.async {
            val ok = store(write)
            if (ok) {
                failed.set(null)
                DatabaseFactory.checkpoint()
            }
            ok
        }
        val done = runBlocking { withTimeoutOrNull(timeoutMs) { job.await() } }
        if (done != true) {
            failed.set(write)
            Diagnostics.log("scratchpad flush timed out or failed")
        }
        return done == true
    }

    /** Checkpoints the WAL without writing (used on shutdown). */
    fun checkpointBlocking() {
        runBlocking { withTimeoutOrNull(2_000L) { DatabaseFactory.checkpoint() } }
    }

    private suspend fun store(write: Write): Boolean {
        val result = runCatching { AppGraph.notesRepository.saveScratchpad(write.noteId, write.text) }
        return result.fold(
            onSuccess = {
                persisted.set(write.text)
                writesSinceCheckpoint++
                ScratchpadRecovery.write(write.text)
                true
            },
            onFailure = { error ->
                Diagnostics.log("scratchpad write failed: ${error.message}")
                false
            }
        )
    }

    private suspend fun maybeCheckpoint() {
        if (writesSinceCheckpoint == 0) return
        writesSinceCheckpoint = 0
        DatabaseFactory.checkpoint()
    }

    private const val RETRY_DELAY_MS = 1_000L
    private const val CHECKPOINT_INTERVAL_MS = 30_000L
}

/**
 * Plain-text copy of the general note, kept beside the database so an empty or
 * unreadable database never means losing what was written.
 */
object ScratchpadRecovery {

    private val file: Path by lazy { Xdg.dataDir.resolve("scratchpad-recovery.txt") }

    /** Returns the last mirrored text, or an empty string when there is none. */
    fun read(): String = runCatching {
        file.takeIf { Files.exists(it) }?.let { Files.readString(it) } ?: ""
    }.getOrDefault("")

    fun write(text: String) {
        runCatching {
            if (text.isBlank()) {
                Files.deleteIfExists(file)
                return@runCatching
            }
            Files.createDirectories(file.parent)
            val temp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(temp, text)
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { Diagnostics.log("scratchpad recovery mirror failed: ${it.message}") }
    }
}
