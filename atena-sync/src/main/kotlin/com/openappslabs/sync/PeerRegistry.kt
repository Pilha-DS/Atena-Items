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

import java.util.concurrent.ConcurrentHashMap

data class DiscoveredPeer(
    val deviceId: String,
    val deviceName: String,
    val host: String,
    val port: Int,
    val lastSeenAt: Long = System.currentTimeMillis(),
    val latencyMs: Long? = null
) {
    fun toDto(): PeerDto = PeerDto(
        deviceId = deviceId,
        deviceName = deviceName,
        host = host,
        port = port,
        lastSeenAt = lastSeenAt,
        latencyMs = latencyMs,
        paired = true
    )

    fun baseUrl(): String = "http://$host:$port"
}

class PeerRegistry {

    private val peers = ConcurrentHashMap<String, DiscoveredPeer>()

    fun upsert(peer: DiscoveredPeer) {
        peers[peer.deviceId] = peer
    }

    fun touchLatency(deviceId: String, latencyMs: Long) {
        peers.computeIfPresent(deviceId) { _, current ->
            current.copy(latencyMs = latencyMs, lastSeenAt = System.currentTimeMillis())
        }
    }

    fun remove(deviceId: String) {
        peers.remove(deviceId)
    }

    fun get(deviceId: String): DiscoveredPeer? = peers[deviceId]

    fun all(): List<DiscoveredPeer> = peers.values
        .sortedByDescending { it.lastSeenAt }
        .toList()

    fun prune(olderThanMs: Long = 60_000L) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        peers.entries.removeIf { it.value.lastSeenAt < cutoff }
    }
}
