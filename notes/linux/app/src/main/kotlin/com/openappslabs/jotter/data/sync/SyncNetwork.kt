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

/**
 * How the current connection is paid for, which is all the sync loop needs to
 * know about the network.
 */
enum class NetworkCost {
    /** Wi-Fi or Ethernet: a request costs nothing, so the normal cadence applies. */
    UNMETERED,

    /** A mobile link: every request leaves a data plan, so the loop backs off. */
    METERED,

    /** No default route: there is nobody to talk to. */
    OFFLINE
}

/**
 * The sync cadence, kept apart from the engine so the arithmetic can be tested
 * on its own.
 */
object SyncCadence {

    /** Pull cadence when the connection is free. */
    const val PERIODIC_SYNC_MS = 5 * 60 * 1000L

    /** On paid data the automatic pull runs this many times less often… */
    const val METERED_SLOWDOWN = 6

    /** …but never further apart than this, or the desktop would look stale. */
    const val METERED_MAX_DELAY_MS = 10 * 60 * 1000L

    /** A burst of auto-saves becomes one push. */
    const val PUSH_DEBOUNCE_MS = 4_000L

    /**
     * How long to wait before the next automatic pull.
     *
     * Six times less often on paid data, capped at ten minutes: 5 min × 6 would
     * be half an hour of silence, and the cap is what keeps the app feeling
     * alive. Offline keeps the normal cadence because a skipped attempt sends
     * no bytes either way — the wait is only there to avoid spinning.
     */
    fun periodicDelay(cost: NetworkCost): Long = when (cost) {
        NetworkCost.METERED ->
            (PERIODIC_SYNC_MS * METERED_SLOWDOWN).coerceAtMost(METERED_MAX_DELAY_MS)

        NetworkCost.UNMETERED, NetworkCost.OFFLINE -> PERIODIC_SYNC_MS
    }
}
