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

enum class LinkKind {
    LAN,
    WIFI_DIRECT,
    BLUETOOTH,
    HUB,
    CELLULAR
}

enum class SyncOperation {
    /** Light text/notes sync — may fall back to hub; never prefers cellular. */
    JOTTER_TEXT,
    /** Heavy media — never uses cellular or metered hub paths. */
    HEAVY
}

data class LinkCandidate(
    val id: String,
    val kind: LinkKind,
    val available: Boolean,
    val latencyMs: Long? = null,
    val successCount: Long = 0,
    val failCount: Long = 0,
    val batteryImpact: Int = 0,
    val label: String = "",
    val peerHost: String? = null,
    val peerPort: Int? = null,
    val peerDeviceId: String? = null
) {
    val stability: Double
        get() {
            val total = successCount + failCount
            if (total == 0L) return 1.0
            return successCount.toDouble() / total.toDouble()
        }

    /** Lower is cheaper. Cellular is always last. */
    val cost: Int
        get() = when (kind) {
            LinkKind.LAN -> 0
            LinkKind.WIFI_DIRECT -> 1
            LinkKind.BLUETOOTH -> 2
            LinkKind.HUB -> 8
            LinkKind.CELLULAR -> 100
        }

    /**
     * Higher is better. Prefers low cost, then low latency, then stability.
     * Unavailable links score as null.
     */
    fun score(): Double? {
        if (!available) return null
        val latencyScore = latencyMs?.let { 1_000.0 / (1_000.0 + it) } ?: 0.5
        val batteryPenalty = batteryImpact / 100.0
        return (100.0 - cost * 10.0) + latencyScore * 20.0 + stability * 10.0 - batteryPenalty
    }

    fun toDto(): LinkDto = LinkDto(
        id = id,
        kind = kind.name.lowercase(),
        available = available,
        latencyMs = latencyMs,
        stability = stability,
        cost = cost,
        batteryImpact = batteryImpact,
        label = label,
        score = score() ?: -1.0
    )
}

/**
 * Ranks transports for each operation. Phase 1 only has LAN peers and hub;
 * Wi-Fi Direct / Bluetooth / cellular slots exist for the policy but stay empty.
 */
class ConnectionManager {

    private val links = ConcurrentHashMap<String, LinkCandidate>()

    fun upsert(link: LinkCandidate) {
        links[link.id] = link
    }

    fun remove(id: String) {
        links.remove(id)
    }

    fun recordSuccess(id: String, latencyMs: Long?) {
        links.computeIfPresent(id) { _, current ->
            current.copy(
                available = true,
                latencyMs = latencyMs ?: current.latencyMs,
                successCount = current.successCount + 1
            )
        }
    }

    fun recordFailure(id: String) {
        links.computeIfPresent(id) { _, current ->
            current.copy(
                failCount = current.failCount + 1,
                available = current.kind == LinkKind.HUB || current.failCount + 1 < 3
            )
        }
    }

    fun all(): List<LinkCandidate> = links.values.sortedByDescending { it.score() ?: Double.NEGATIVE_INFINITY }

    fun bestFor(operation: SyncOperation): LinkCandidate? {
        val ranked = all().mapNotNull { link ->
            val score = link.score() ?: return@mapNotNull null
            if (!allowed(operation, link.kind)) return@mapNotNull null
            link to score
        }.sortedByDescending { it.second }
        return ranked.firstOrNull()?.first
    }

    fun preferredId(operation: SyncOperation): String? = bestFor(operation)?.id

    private fun allowed(operation: SyncOperation, kind: LinkKind): Boolean = when (operation) {
        SyncOperation.HEAVY -> kind != LinkKind.CELLULAR && kind != LinkKind.HUB
        SyncOperation.JOTTER_TEXT -> {
            // Never pick cellular when any unmetered local link exists.
            if (kind == LinkKind.CELLULAR) {
                all().none { it.available && it.kind in LOCAL_KINDS }
            } else {
                true
            }
        }
    }

    companion object {
        private val LOCAL_KINDS = setOf(LinkKind.LAN, LinkKind.WIFI_DIRECT, LinkKind.BLUETOOTH)

        fun lanId(peerDeviceId: String) = "lan:$peerDeviceId"
        const val HUB_ID = "hub"
    }
}
