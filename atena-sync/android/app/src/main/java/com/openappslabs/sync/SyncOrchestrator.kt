/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Atena Sync
 *
 * Atena Sync is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Atena Sync is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Atena Sync.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * Applies local app sync to the relay replica, then converges with the best
 * remote link (LAN peer first, hub last).
 */
class SyncOrchestrator(
    private val database: Database,
    private val syncService: SyncService,
    private val connections: ConnectionManager,
    private val peers: PeerRegistry,
    private val remote: RemoteSyncClient,
    private val deviceName: String,
    private val scope: CoroutineScope
) {
    private val log = LoggerFactory.getLogger(SyncOrchestrator::class.java)
    private val mutex = Mutex()
    private var loopJob: Job? = null

    fun start() {
        refreshHubLink()
        loopJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { converge() }
                    .onFailure { log.debug("periodic converge failed: {}", it.message) }
                peers.prune()
                delay(LAN_PERIOD_MS)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    fun refreshHubLink() {
        val url = database.hubUrl()
        val token = database.hubToken()
        if (url.isNullOrBlank() || token.isNullOrBlank()) {
            connections.remove(ConnectionManager.HUB_ID)
            return
        }
        connections.upsert(
            LinkCandidate(
                id = ConnectionManager.HUB_ID,
                kind = LinkKind.HUB,
                available = true,
                label = url,
                batteryImpact = 15
            )
        )
    }

    /**
     * Local app (or LAN peer hitting our replica) push+pull. After applying the
     * push we converge with remotes so the pull response includes peer/hub news.
     */
    suspend fun handleLocalSync(request: SyncRequest, fromPeer: Boolean = false): SyncResponse =
        mutex.withLock {
            if (fromPeer) {
                return@withLock syncService.sync(request)
            }
            syncService.sync(request)
            runCatching { convergeLocked() }
                .onFailure { log.warn("converge after local sync: {}", it.message) }
            // Rebuild pull so the app sees anything the converge just imported.
            syncService.sync(
                SyncRequest(
                    cursor = request.cursor,
                    deviceId = request.deviceId,
                    deviceName = request.deviceName
                )
            )
        }

    suspend fun converge() = mutex.withLock { convergeLocked() }

    private suspend fun convergeLocked() {
        val link = connections.bestFor(SyncOperation.JOTTER_TEXT) ?: return
        when (link.kind) {
            LinkKind.LAN -> syncWithLan(link)
            LinkKind.HUB -> syncWithHub()
            LinkKind.WIFI_DIRECT, LinkKind.BLUETOOTH, LinkKind.CELLULAR -> Unit
        }
    }

    private suspend fun syncWithLan(link: LinkCandidate) {
        val peerId = link.peerDeviceId ?: return
        val peer = peers.get(peerId) ?: return
        val pullCursor = database.peerPullCursor(peerId)
        val pushCursor = database.peerPushCursor(peerId)
        val pushUpTo = syncService.currentSeq()
        val changes = syncService.changesSince(pushCursor)
        val outgoing = SyncRequest(
            cursor = pullCursor,
            deviceId = database.deviceId(),
            deviceName = deviceName,
            notes = changes.notes,
            deletions = changes.deletions,
            categories = changes.categories
        )
        try {
            val (response, latencyMs) = remote.sync(
                baseUrl = peer.baseUrl(),
                token = database.authToken(),
                request = outgoing
            )
            database.setPeerCursors(peerId, pullCursor = response.cursor, pushCursor = pushUpTo)
            applyRemotePull(response)
            peers.touchLatency(peerId, latencyMs)
            connections.recordSuccess(link.id, latencyMs)
        } catch (error: Exception) {
            connections.recordFailure(link.id)
            log.warn("LAN sync with {} failed: {}", peer.deviceName, error.message)
        }
    }

    private suspend fun syncWithHub() {
        val url = database.hubUrl() ?: return
        val token = database.hubToken() ?: return
        val pullCursor = database.hubCursor()
        val pushCursor = database.hubPushCursor()
        val pushUpTo = syncService.currentSeq()
        val changes = syncService.changesSince(pushCursor)
        val outgoing = SyncRequest(
            cursor = pullCursor,
            deviceId = database.deviceId(),
            deviceName = deviceName,
            notes = changes.notes,
            deletions = changes.deletions,
            categories = changes.categories
        )
        try {
            val (response, latencyMs) = remote.sync(url, token, outgoing)
            database.setHubCursor(response.cursor)
            database.setHubPushCursor(pushUpTo)
            applyRemotePull(response)
            connections.recordSuccess(ConnectionManager.HUB_ID, latencyMs)
        } catch (error: Exception) {
            connections.recordFailure(ConnectionManager.HUB_ID)
            log.warn("hub sync failed: {}", error.message)
        }
    }

    private fun applyRemotePull(response: SyncResponse) {
        if (response.notes.isEmpty() && response.deletions.isEmpty() && response.categories.isEmpty()) {
            return
        }
        syncService.sync(
            SyncRequest(
                cursor = syncService.currentSeq(),
                deviceId = database.deviceId(),
                deviceName = deviceName,
                notes = response.notes,
                deletions = response.deletions,
                categories = response.categories
            )
        )
    }

    companion object {
        const val LAN_PERIOD_MS = 1_000L
    }
}
