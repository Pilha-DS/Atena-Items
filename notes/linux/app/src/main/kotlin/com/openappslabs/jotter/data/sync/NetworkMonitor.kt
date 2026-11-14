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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Answers "may this request cost the user money?" for the desktop sync loop.
 *
 * The question is really "which interface carries the default route, and is it
 * a mobile link?". `/proc/net/route` answers the first half without spawning
 * anything, and the interface name answers the second. A Wi-Fi or Ethernet link
 * is treated as free: a phone hotspot shows up as Wi-Fi here and there is no
 * reliable way to tell it apart, so the desktop is generous where the phone is
 * strict.
 */
class NetworkMonitor(
    private val routeFile: File = File("/proc/net/route"),
    private val ipv6RouteFile: File = File("/proc/net/ipv6_route")
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _cost = MutableStateFlow(current())
    val cost: StateFlow<NetworkCost> = _cost.asStateFlow()

    init {
        // Nothing to subscribe to: the interface changes every so often, and
        // re-reading two small files is cheaper than a D-Bus round trip.
        scope.launch {
            while (isActive) {
                delay(POLL_MS)
                _cost.value = current()
            }
        }
    }

    /** Reads the cost of the active connection now. */
    fun current(): NetworkCost {
        val interfaceName = defaultRouteInterface() ?: return NetworkCost.OFFLINE
        return if (isMobileLink(interfaceName)) NetworkCost.METERED else NetworkCost.UNMETERED
    }

    /**
     * The interface carrying the default route, or `null` when there is none.
     *
     * IPv4 first — that is what almost every home network uses — then IPv6, so
     * an IPv6-only connection is not mistaken for "offline" and does not pause
     * the sync for nothing.
     */
    private fun defaultRouteInterface(): String? = ipv4DefaultRoute() ?: ipv6DefaultRoute()

    /**
     * The IPv4 table lists the default as destination `00000000` with the
     * gateway flag (`0x2`) set, which is what tells it apart from a connected
     * route for the local subnet.
     */
    private fun ipv4DefaultRoute(): String? {
        val lines = runCatching { routeFile.readLines() }.getOrNull() ?: return null
        return lines.drop(1).firstNotNullOfOrNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 4 || fields[1] != "00000000") return@firstNotNullOfOrNull null
            val flags = fields[3].toIntOrNull(radix = 16) ?: return@firstNotNullOfOrNull null
            if (flags and GATEWAY_FLAG == 0) return@firstNotNullOfOrNull null
            fields[0].takeIf { it.isNotBlank() && it != "lo" }
        }
    }

    /**
     * In the IPv6 table the destination is a 32-character address and the
     * prefix length a two-character byte, so the default is `::/0`. The
     * interface name is the last column.
     */
    private fun ipv6DefaultRoute(): String? {
        val lines = runCatching { ipv6RouteFile.readLines() }.getOrNull() ?: return null
        return lines.firstNotNullOfOrNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 10) return@firstNotNullOfOrNull null
            if (fields[0].trimStart('0').isNotEmpty() || fields[1] != "00") return@firstNotNullOfOrNull null
            fields.last().takeIf { it.isNotBlank() && it != "lo" }
        }
    }

    /**
     * Mobile links are the ones whose name says so: WWAN modems, PPP and the
     * rmnet driver used by USB and Bluetooth tethering on Android. Everything
     * else (Wi-Fi, Ethernet, a VPN tunnel) rides on a connection that was
     * already paid for.
     */
    private fun isMobileLink(interfaceName: String): Boolean {
        val name = interfaceName.lowercase()
        return MOBILE_PREFIXES.any { name.startsWith(it) }
    }

    private companion object {
        const val POLL_MS = 30_000L

        /** `RTF_GATEWAY` in the flags column of `/proc/net/route`. */
        const val GATEWAY_FLAG = 0x2

        val MOBILE_PREFIXES = listOf("wwan", "wwp", "ppp", "rmnet", "cdc-wdm")
    }
}
