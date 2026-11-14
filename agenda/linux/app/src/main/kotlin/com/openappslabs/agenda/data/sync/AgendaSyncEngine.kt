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

package com.openappslabs.agenda.data.sync

import androidx.compose.runtime.Immutable
import com.openappslabs.agenda.data.repository.EventRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

@Immutable
data class SyncUiState(
    val configured: Boolean = false,
    val running: Boolean = false,
    val cursor: Long = 0L,
    val lastSyncAt: Long = 0L,
    val pushed: Int = 0,
    val pulled: Int = 0,
    val error: SyncErrorKind? = null,
    val errorDetail: String? = null,
    /** Server clock minus this device's clock, in milliseconds. */
    val clockSkewMs: Long = 0L
) {
    val hasClockSkew: Boolean get() = abs(clockSkewMs) > CLOCK_SKEW_WARNING_MS

    private companion object {
        const val CLOCK_SKEW_WARNING_MS = 5 * 60 * 1000L
    }
}

/**
 * The sync loop of the desktop app.
 *
 * Triggers: a local change (debounced, so a burst of edits is one request),
 * a periodic pull so the other device's events show up, and the manual button.
 * One mutex keeps two triggers from overlapping.
 */
class AgendaSyncEngine(
    private val repository: EventRepository,
    private val preferences: SyncPreferences,
    private val scope: CoroutineScope,
    private val client: AgendaSyncClient = AgendaSyncClient(),
    private val onChange: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val initialConfig = preferences.read()

    private val _state = MutableStateFlow(
        SyncUiState(
            configured = initialConfig.isConfigured,
            cursor = initialConfig.cursor,
            lastSyncAt = initialConfig.lastSyncAt
        )
    )
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val started = AtomicBoolean(false)

    /** CONFLATED: a burst of saves becomes one push. */
    private val triggers = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun start() {
        if (!started.compareAndSet(false, true)) return
        subscribe()
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun subscribe() {
        scope.launch {
            triggers.debounce(PUSH_DEBOUNCE_MS).collect { runSync() }
        }

        // One pull at startup, then keep pulling while the app is open.
        scope.launch {
            val config = preferences.read()
            if (config.isConfigured) runSync()
            while (isActive) {
                delay(PERIODIC_SYNC_MS)
                runSync()
            }
        }
    }

    /** Called after every local write; the actual push is debounced. */
    fun notifyLocalChange() {
        triggers.tryEmit(Unit)
    }

    suspend fun syncNow() = runSync()

    /** Validates URL + token before saving; returns the server clock on success. */
    suspend fun testConnection(serverUrl: String, token: String): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                client.ping(SyncConfig(serverUrl = serverUrl.trim(), token = token.trim()))
            }
        }

    fun clearError() {
        _state.update { it.copy(error = null, errorDetail = null) }
    }

    private suspend fun runSync() = withContext(Dispatchers.IO) {
        syncUnderLock()
    }

    /**
     * Runs off the UI thread on purpose: the HTTP request and the SQLite
     * writes inside would otherwise freeze the window for the whole timeout.
     */
    private suspend fun syncUnderLock() {
        mutex.withLock {
            val config = preferences.read()
            if (!config.isConfigured) {
                _state.update { it.copy(configured = false, running = false) }
                return
            }

            _state.update {
                it.copy(configured = true, running = true, error = null, errorDetail = null)
            }

            try {
                val pendingEvents = repository.pendingEvents()
                val pendingDeletions = repository.pendingDeletions()

                val request = AgendaSyncRequestDto(
                    cursor = config.cursor,
                    deviceId = config.deviceId,
                    deviceName = config.deviceName,
                    events = pendingEvents.map { it.toSyncDto() },
                    deletions = pendingDeletions.map {
                        AgendaDeletionDto(uuid = it.uuid, deletedAt = it.deletedAt)
                    }
                )

                val response = client.sync(config, request)
                repository.applyPulled(response.events, response.deletions)

                val pushed = pendingEvents.map { it.uuid to it.updatedTime }
                val serverVersions = response.events.associate { it.uuid to it.updatedTime }
                repository.markSynced(SyncMerge.confirmedPushes(pushed, serverVersions))
                repository.clearDeletions(pendingDeletions.map { it.uuid })

                val now = clock()
                preferences.saveCursor(cursor = response.cursor, syncedAt = now)

                _state.update {
                    it.copy(
                        running = false,
                        configured = true,
                        cursor = response.cursor,
                        lastSyncAt = now,
                        pushed = pendingEvents.size + pendingDeletions.size,
                        pulled = response.events.size + response.deletions.size,
                        clockSkewMs = response.serverTime - now,
                        error = null,
                        errorDetail = null
                    )
                }
            } catch (error: SyncException) {
                _state.update {
                    it.copy(running = false, error = error.kind, errorDetail = error.message)
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        running = false,
                        error = SyncErrorKind.NETWORK,
                        errorDetail = error.message
                    )
                }
            } finally {
                onChange()
            }
        }
    }

    private companion object {
        const val PUSH_DEBOUNCE_MS = 2_000L
        const val PERIODIC_SYNC_MS = 5 * 60 * 1000L
    }
}
