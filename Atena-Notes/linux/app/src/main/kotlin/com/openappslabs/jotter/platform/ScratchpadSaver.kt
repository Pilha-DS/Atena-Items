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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Persists the general note (scratchpad) safely.
 *
 * Two rules keep the text from disappearing:
 *
 * 1. **Order.** Every write carries a sequence number and runs under one lock,
 *    on one thread. A write that was overtaken by a newer one is dropped
 *    instead of landing afterwards, so a slow (or retried) save can never put
 *    an older version of the note back into the database.
 * 2. **No accidental empty note.** Only a change that really came from the
 *    editor may erase the text. A blank value produced while the window is
 *    still loading, while it closes, or by a stale snapshot is refused, and
 *    the text stays where it is.
 *
 * The same text is mirrored to `scratchpad-recovery.txt`, which is only
 * cleared when you erase the note yourself. If the database ever comes back
 * empty (corrupted file, wiped schema, killed process), the note is restored
 * from that mirror on the next open.
 */
object ScratchpadSaver {

    private data class Write(
        val noteId: Int,
        val text: String,
        val seq: Long,
        /** True only when this text came from the editor that the user typed in. */
        val fromEditor: Boolean
    )

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jotter-scratchpad-save").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

    /** Newest text waiting to be written; older ones are simply discarded. */
    private val pending = AtomicReference<Write?>(null)

    /** Wakes the single writer thread. Conflated: one wake-up is enough. */
    private val signal = Channel<Unit>(Channel.CONFLATED)

    /** Serializes writes (and the flush) so the last text written is the newest. */
    private val writeLock = Mutex()

    private val seqCounter = AtomicLong(0)
    private val appliedSeq = AtomicLong(0)
    private val lastWriteOk = AtomicBoolean(true)

    /** Text known to be in the database, used to skip redundant writes. */
    private val persisted = AtomicReference<String?>(null)

    /** Last non-blank text that reached the database, seeded from the mirror. */
    private val lastNonBlank = AtomicReference<String?>(null)

    /** Last text the editor handed over; a retry is only worth it for this one. */
    private val editorText = AtomicReference<String?>(null)

    private var writesSinceCheckpoint = 0

    init {
        lastNonBlank.set(ScratchpadRecovery.read().takeIf { it.isNotBlank() })
        scope.launch {
            for (unit in signal) drain()
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

    /** Queues the newest text typed by the user; identical consecutive values are ignored. */
    fun submit(noteId: Int, text: String) {
        editorText.set(text)
        if (persisted.get() == text && pending.get() == null) return
        enqueue(Write(noteId, text, nextSeq(), fromEditor = true))
    }

    /**
     * Records text that is already in the database, such as right after the
     * editor opened and read it. Without this, opening Jotter wrote the whole
     * note back (an fsync with `synchronous = FULL`) even though nothing was
     * typed.
     */
    fun markPersisted(text: String) {
        persisted.set(text)
        if (text.isNotBlank()) lastNonBlank.set(text)
    }

    /**
     * Writes [text] and waits (bounded) for it to reach SQLite, then
     * checkpoints. Used when the note is hidden or the app is closing.
     *
     * [fromEditor] is false for values that are not the user's text (for
     * example a flush fired while the window is still loading); those can
     * never blank the note.
     */
    fun flushBlocking(
        noteId: Int?,
        text: String,
        fromEditor: Boolean = true,
        timeoutMs: Long = 2_500L
    ): Boolean {
        val id = noteId ?: return true
        if (fromEditor) editorText.set(text)
        val write = Write(id, text, nextSeq(), fromEditor)
        enqueue(write)
        val ok = runBlocking {
            withTimeoutOrNull(timeoutMs) {
                while (appliedSeq.get() < write.seq) delay(20)
                lastWriteOk.get()
            }
        }
        if (ok != true) {
            Diagnostics.log("scratchpad flush timed out or failed")
        }
        runBlocking { withTimeoutOrNull(2_000L) { DatabaseFactory.checkpoint() } }
        return ok == true
    }

    /** Checkpoints the WAL without writing (used on shutdown). */
    fun checkpointBlocking() {
        runBlocking { withTimeoutOrNull(2_000L) { DatabaseFactory.checkpoint() } }
    }

    private fun nextSeq(): Long = seqCounter.incrementAndGet()

    private fun enqueue(write: Write) {
        pending.set(write)
        signal.trySend(Unit)
    }

    /** Single writer: drains whatever is pending, newest first. */
    private suspend fun drain() {
        while (true) {
            val write = pending.getAndSet(null) ?: return
            writeLock.withLock { applyWrite(write) }
        }
    }

    private suspend fun applyWrite(write: Write) {
        // A newer text already reached the database: writing this one now
        // would resurrect an older version of the note.
        if (write.seq <= appliedSeq.get()) return

        if (write.text.isBlank() && !write.fromEditor && !lastNonBlank.get().isNullOrBlank()) {
            Diagnostics.log("scratchpad: empty write refused (${write.noteId})")
            appliedSeq.set(write.seq)
            lastWriteOk.set(false)
            return
        }

        val ok = runCatching { AppGraph.notesRepository.saveScratchpad(write.noteId, write.text) }
            .onFailure { Diagnostics.log("scratchpad write failed: ${it.message}") }
            .isSuccess

        if (ok) {
            persisted.set(write.text)
            writesSinceCheckpoint++
            updateMirror(write)
            maybeCheckpoint()
        } else if (pending.get() == null) {
            // Nothing newer is waiting: keep this text and try again shortly,
            // but only while it is still the text the editor holds.
            scope.launch {
                delay(RETRY_DELAY_MS)
                if (pending.get() == null && editorText.get() == write.text) {
                    enqueue(Write(write.noteId, write.text, nextSeq(), write.fromEditor))
                }
            }
        }

        appliedSeq.set(write.seq)
        lastWriteOk.set(ok)
    }

    /**
     * The mirror is the last line of defence, so it is only erased when the
     * user erased the note. A blank value coming from anywhere else keeps the
     * previous text available for the next restore.
     */
    private fun updateMirror(write: Write) {
        if (write.text.isNotBlank()) {
            lastNonBlank.set(write.text)
            ScratchpadRecovery.remember(write.text)
        } else if (write.fromEditor) {
            lastNonBlank.set(null)
            ScratchpadRecovery.clear()
        }
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

    /** Stores the latest non-blank text, atomically, for the next restore. */
    fun remember(text: String) {
        if (text.isBlank()) return
        runCatching {
            Files.createDirectories(file.parent)
            val temp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(temp, text)
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { Diagnostics.log("scratchpad recovery mirror failed: ${it.message}") }
    }

    /** Erases the mirror: only called when the user really cleared the note. */
    fun clear() {
        runCatching { Files.deleteIfExists(file) }
            .onFailure { Diagnostics.log("scratchpad mirror cleanup failed: ${it.message}") }
    }
}
