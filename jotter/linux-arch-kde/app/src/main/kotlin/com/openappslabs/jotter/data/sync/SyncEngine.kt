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

package com.openappslabs.jotter.data.sync

import androidx.compose.runtime.Immutable
import com.openappslabs.jotter.data.model.Category
import com.openappslabs.jotter.data.source.CategoryDao
import com.openappslabs.jotter.data.source.JotterDatabase
import com.openappslabs.jotter.data.source.SyncDao
import com.openappslabs.jotter.data.source.inTransaction
import com.openappslabs.jotter.platform.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

@Immutable
data class SyncState(
    val configured: Boolean = false,
    val running: Boolean = false,
    val cursor: Long = 0L,
    val lastSyncAt: Long = 0L,
    val pushed: Int = 0,
    val pulled: Int = 0,
    val error: SyncErrorKind? = null,
    val errorDetail: String? = null,
    /** Server clock minus this device's clock, in milliseconds. */
    val clockSkewMs: Long = 0L,
    /** The automatic pull is running on a mobile link, so it is backing off. */
    val metered: Boolean = false,
    /** There is no default route right now; the pull is paused instead of failing. */
    val offline: Boolean = false,
    /** Atena Sync has a live LAN peer — pull cadence drops to ~1 s. */
    val lanPeerActive: Boolean = false,
    /** Preferred link kind from Atena Sync (`lan`, `hub`, …), if known. */
    val preferredLink: String? = null
) {
    /** LWW is decided by client clocks, so a big difference is worth warning about. */
    val hasClockSkew: Boolean get() = abs(clockSkewMs) > CLOCK_SKEW_WARNING_MS

    private companion object {
        const val CLOCK_SKEW_WARNING_MS = 5 * 60 * 1000L
    }
}

/**
 * Runs the sync loop for the desktop app.
 *
 * Triggers: local changes (the pending counter is observed, debounced),
 * a periodic pull so notes written on the phone show up, and the manual
 * button. Everything goes through one mutex, so two triggers never overlap.
 *
 * The cadence follows the connection: on a mobile link it is six times slower
 * and never waits less than the capped interval, and with no default route the
 * pull pauses instead of failing every five minutes.
 */
class SyncEngine(
    private val database: JotterDatabase,
    private val syncDao: SyncDao,
    private val categoryDao: CategoryDao,
    private val preferences: SyncPreferencesRepository,
    private val client: SyncClient,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope,
    private val atenaSync: AtenaSyncLocal = AtenaSyncLocal()
) {

    private val initialCost = network.current()
    private val _state = MutableStateFlow(
        SyncState(
            metered = initialCost == NetworkCost.METERED,
            offline = initialCost == NetworkCost.OFFLINE
        )
    )
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return

        scope.launch {
            preferences.configFlow.collect { config ->
                _state.update {
                    it.copy(
                        configured = config.isConfigured,
                        cursor = config.cursor,
                        lastSyncAt = config.lastSyncAt
                    )
                }
            }
        }

        // Track the cost of the connection so the UI can say "mobile link" and
        // the loop can pick the right cadence.
        scope.launch {
            var previous = network.cost.value
            network.cost.collect { cost ->
                _state.update {
                    it.copy(
                        metered = cost == NetworkCost.METERED,
                        offline = cost == NetworkCost.OFFLINE
                    )
                }
                // Back online after an outage: the edits are already on disk, so
                // one round delivers them instead of waiting for the (possibly
                // capped) periodic tick.
                if (previous == NetworkCost.OFFLINE && cost != NetworkCost.OFFLINE) {
                    runSync(manual = false)
                }
                previous = cost
            }
        }

        // Something changed locally: push it soon, but coalesce a burst of
        // auto-saves from the editor into one request. Deletions count too, so
        // emptying the trash pushes even when no row is left to upload.
        scope.launch {
            combine(
                syncDao.observePendingCount(),
                syncDao.observePendingDeletionCount()
            ) { notes, deletions ->
                notes + deletions
            }
                .debounce(SyncCadence.PUSH_DEBOUNCE_MS)
                .collect { pending ->
                    if (pending > 0) runSync(manual = false)
                }
        }

        // Startup pull, then keep pulling so the other device's edits arrive
        // even when this device is idle. The wait is recomputed every round,
        // because the machine may have moved to a mobile link (or lost the
        // default route) since the last one.
        scope.launch {
            if (preferences.read().isConfigured) runSync(manual = false)
            while (true) {
                refreshLinkState()
                waitForNextPull(network.current(), _state.value.lanPeerActive)
                // Offline there is nobody to talk to, and a failed request every
                // five minutes would only paint an error over a device that is
                // doing nothing wrong. The cost flow also triggers a round as
                // soon as the route comes back.
                if (network.current() != NetworkCost.OFFLINE) runSync(manual = false)
            }
        }
    }

    /**
     * Sleeps until the next automatic pull, cutting the wait short the moment
     * the connection becomes free again: sitting out the whole capped interval
     * with Wi-Fi or Ethernet available would save nothing.
     */
    private suspend fun waitForNextPull(cost: NetworkCost, lanPeerActive: Boolean) {
        val wait = SyncCadence.periodicDelay(cost, lanPeerActive)
        if (cost == NetworkCost.UNMETERED || lanPeerActive) {
            delay(wait)
            return
        }
        withTimeoutOrNull(wait) { network.cost.first { it == NetworkCost.UNMETERED } }
    }

    private suspend fun refreshLinkState() {
        val config = preferences.read()
        if (!config.isConfigured || !atenaSync.isLocalDaemon(config.serverUrl)) {
            _state.update { it.copy(lanPeerActive = false, preferredLink = null) }
            return
        }
        runCatching { atenaSync.fetchLinks(config) }
            .onSuccess { links ->
                val lanActive = links.links.any {
                    it.available && it.kind.equals("lan", ignoreCase = true)
                }
                val preferredKind = links.links.firstOrNull { it.id == links.preferred }?.kind
                    ?: links.preferred
                _state.update {
                    it.copy(lanPeerActive = lanActive, preferredLink = preferredKind)
                }
            }
            .onFailure {
                _state.update { state -> state.copy(lanPeerActive = false) }
            }
    }

    /** The "sync now" button and the first sync right after connecting. */
    suspend fun syncNow() = runSync(manual = true)

    /**
     * Validates a URL + token before saving anything, so a typo never leaves a
     * half-configured device behind. Returns the server clock on success.
     */
    suspend fun testConnection(serverUrl: String, token: String): Result<Long> = runCatching {
        client.ping(SyncConfig(serverUrl = serverUrl.trim(), token = token.trim()))
    }

    /** Discovers the local Atena Sync daemon and returns URL + pairing token. */
    suspend fun discoverLocalSync(): Result<AtenaSyncSetup> = runCatching {
        atenaSync.fetchSetup()
    }

    suspend fun configureHubFallback(hubUrl: String, hubToken: String): Result<AtenaSyncHubStatus> =
        runCatching {
            val config = preferences.read()
            atenaSync.putHub(config, hubUrl, hubToken)
        }

    /** Background trigger: local edit, app start or the periodic pull. */
    suspend fun requestSync() = runSync(manual = false)

    /** After the local database is replaced, the next sync must pull everything. */
    suspend fun resetCursor() = preferences.resetCursor()

    fun clearError() {
        _state.update { it.copy(error = null, errorDetail = null) }
    }

    private suspend fun runSync(manual: Boolean) {
        mutex.withLock {
            val config = preferences.read()
            if (!config.isConfigured) {
                // Nothing to say: the Sync screen shows a neutral "not
                // connected" state instead of an error on every tick.
                _state.update { it.copy(configured = false, running = false) }
                return
            }

            // A background trigger with no route would spend nothing but would
            // paint a network error over a machine that is fine. The manual
            // button still tries: the user asked, and the error is the answer
            // to "why is nothing arriving?".
            if (!manual && network.current() == NetworkCost.OFFLINE) {
                _state.update { it.copy(configured = true, running = false, offline = true) }
                return
            }

            _state.update {
                it.copy(configured = true, running = true, error = null, errorDetail = null)
            }

            try {
                val pendingNotes = syncDao.getPendingNotes()
                val pendingDeletions = syncDao.getPendingDeletions()

                val request = SyncRequestDto(
                    cursor = config.cursor,
                    deviceId = config.deviceId,
                    deviceName = config.deviceName,
                    notes = pendingNotes.map { it.toSyncDto() },
                    deletions = pendingDeletions.map {
                        SyncDeletionDto(uuid = it.uuid, deletedAt = it.deletedAt)
                    }
                )

                val response = client.sync(config, request)
                applyResponse(response, pendingNotes.map { it.uuid to it.updatedTime }, pendingDeletions.map { it.uuid })

                val now = System.currentTimeMillis()
                preferences.saveCursor(cursor = response.cursor, syncedAt = now)

                _state.update {
                    it.copy(
                        running = false,
                        cursor = response.cursor,
                        lastSyncAt = now,
                        pushed = pendingNotes.size + pendingDeletions.size,
                        pulled = response.notes.size + response.deletions.size,
                        clockSkewMs = response.serverTime - now,
                        error = null,
                        errorDetail = null,
                        offline = false
                    )
                }
                Diagnostics.log(
                    "sync ok: pushed ${pendingNotes.size}/${pendingDeletions.size} " +
                        "pulled ${response.notes.size}/${response.deletions.size} cursor=${response.cursor}"
                )
            } catch (error: SyncException) {
                Diagnostics.log("sync failed (${error.kind}): ${error.message}")
                _state.update {
                    it.copy(
                        running = false,
                        error = error.kind,
                        errorDetail = error.message,
                        offline = error.kind == SyncErrorKind.NETWORK &&
                            network.current() == NetworkCost.OFFLINE
                    )
                }
            } catch (error: Exception) {
                Diagnostics.log("sync failed: ${error.message}")
                _state.update {
                    it.copy(
                        running = false,
                        error = SyncErrorKind.NETWORK,
                        errorDetail = error.message
                    )
                }
            }
        }
    }

    /**
     * Applies the pull and then acknowledges the push, in a single transaction:
     * either the whole step lands or the cursor is not advanced, so a crash in
     * the middle cannot lose a change.
     */
    private suspend fun applyResponse(
        response: SyncResponseDto,
        pushedNotes: List<Pair<String, Long>>,
        pushedDeletions: List<String>
    ) {
        database.inTransaction {
            for (dto in response.notes) {
                if (dto.uuid.isBlank()) continue
                val local = syncDao.getNoteByUuid(dto.uuid)
                when {
                    local == null -> syncDao.insertFromServer(dto.toNote())
                    dto.updatedTime > local.updatedTime -> syncDao.updateFromServer(
                        dto.toNote(id = local.id, isArchived = local.isArchived)
                    )
                    // Local is newer or equal: it stays dirty and wins on the
                    // next push (LWW by updatedTime).
                }
            }

            for (deletion in response.deletions) {
                val local = syncDao.getNoteByUuid(deletion.uuid) ?: continue
                // A note edited after the deletion is newer than it: the edit
                // wins and the note comes back on the next push.
                if (deletion.deletedAt >= local.updatedTime) {
                    syncDao.deleteByUuid(deletion.uuid)
                }
            }

            for (name in response.categories) {
                if (name.isNotBlank()) categoryDao.insertCategory(Category(name = name))
            }

            // Only acknowledge what the server actually kept. When the server
            // holds a newer version of a pushed note, it is not confirmed here;
            // it was just overwritten above (or wins on the next round).
            val serverUpdated = response.notes.associate { it.uuid to it.updatedTime }
            val confirmed = pushedNotes
                .filterNot { (uuid, localUpdated) -> (serverUpdated[uuid] ?: -1L) > localUpdated }
                .map { it.first }
            if (confirmed.isNotEmpty()) syncDao.markSynced(confirmed)
            if (pushedDeletions.isNotEmpty()) syncDao.clearDeletions(pushedDeletions)
        }
    }

}