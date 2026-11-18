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

import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Advertises this daemon on the LAN and watches for other `_atena-sync._tcp` peers.
 * Pairing is the shared token; discovery alone does not grant access.
 */
class MdnsDiscovery(
    private val deviceId: String,
    private val deviceName: String,
    private val lanPort: Int,
    private val peers: PeerRegistry,
    private val connections: ConnectionManager
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(MdnsDiscovery::class.java)
    private val running = AtomicBoolean(false)
    private var jmdns: JmDNS? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val address = InetAddress.getLocalHost()
            val dns = JmDNS.create(address, deviceName)
            jmdns = dns
            val info = ServiceInfo.create(
                SERVICE_TYPE,
                "atena-sync-$deviceId",
                lanPort,
                0,
                0,
                mapOf(
                    "deviceId" to deviceId,
                    "deviceName" to deviceName,
                    "version" to BuildInfo.VERSION,
                    "caps" to "jotter"
                )
            )
            dns.registerService(info)
            dns.addServiceListener(SERVICE_TYPE, listener)
            log.info("mDNS advertising {} on {}:{}", deviceName, address.hostAddress, lanPort)
        } catch (error: Exception) {
            running.set(false)
            log.warn("mDNS unavailable: {}", error.message)
        }
    }

    private val listener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            jmdns?.requestServiceInfo(event.type, event.name, 1500)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            val info = event.info ?: return
            val peerId = info.getPropertyString("deviceId") ?: return
            if (peerId == deviceId) return
            peers.remove(peerId)
            connections.remove(ConnectionManager.lanId(peerId))
            log.info("peer left: {}", peerId)
        }

        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info ?: return
            val peerId = info.getPropertyString("deviceId") ?: return
            if (peerId == deviceId) return
            val host = info.inetAddresses.firstOrNull()?.hostAddress
                ?: info.hostAddresses.firstOrNull()
                ?: return
            val port = if (info.port > 0) info.port else lanPort
            val name = info.getPropertyString("deviceName") ?: peerId
            val peer = DiscoveredPeer(
                deviceId = peerId,
                deviceName = name,
                host = host,
                port = port
            )
            peers.upsert(peer)
            connections.upsert(
                LinkCandidate(
                    id = ConnectionManager.lanId(peerId),
                    kind = LinkKind.LAN,
                    available = true,
                    label = name,
                    peerHost = host,
                    peerPort = port,
                    peerDeviceId = peerId,
                    batteryImpact = 5
                )
            )
            log.info("peer found: {} at {}:{}", name, host, port)
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching {
            jmdns?.unregisterAllServices()
            jmdns?.close()
        }
        jmdns = null
    }

    companion object {
        const val SERVICE_TYPE = "_atena-sync._tcp.local."
    }
}
