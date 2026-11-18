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

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/** Android Network Service Discovery for `_atena-sync._tcp`. */
class NsdDiscovery(
    context: Context,
    private val deviceId: String,
    private val deviceName: String,
    private val lanPort: Int,
    private val peers: PeerRegistry,
    private val connections: ConnectionManager
) : AutoCloseable {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registered = false

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            registered = true
            Log.i(TAG, "NSD registered ${serviceInfo.serviceName}")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "NSD register failed: $errorCode")
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            registered = false
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "NSD discover start failed: $errorCode")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (serviceInfo.serviceType.contains("atena-sync")) {
                nsd.resolveService(serviceInfo, resolveListener)
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            val peerId = serviceInfo.attributes["deviceId"]?.toString(Charsets.UTF_8) ?: return
            if (peerId == deviceId) return
            peers.remove(peerId)
            connections.remove(ConnectionManager.lanId(peerId))
        }
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "NSD resolve failed: $errorCode")
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val attrs = serviceInfo.attributes
            val peerId = attrs["deviceId"]?.toString(Charsets.UTF_8) ?: return
            if (peerId == deviceId) return
            val host = serviceInfo.host?.hostAddress ?: return
            val name = attrs["deviceName"]?.toString(Charsets.UTF_8) ?: peerId
            val port = if (serviceInfo.port > 0) serviceInfo.port else lanPort
            peers.upsert(
                DiscoveredPeer(
                    deviceId = peerId,
                    deviceName = name,
                    host = host,
                    port = port
                )
            )
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
            Log.i(TAG, "peer found: $name at $host:$port")
        }
    }

    fun start() {
        val info = NsdServiceInfo().apply {
            serviceName = "atena-sync-$deviceId"
            serviceType = SERVICE_TYPE
            port = lanPort
            setAttribute("deviceId", deviceId)
            setAttribute("deviceName", deviceName.take(40))
            setAttribute("version", BuildInfo.VERSION)
            setAttribute("caps", "jotter")
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    override fun close() {
        runCatching { nsd.stopServiceDiscovery(discoveryListener) }
        if (registered) {
            runCatching { nsd.unregisterService(registrationListener) }
        }
    }

    companion object {
        private const val TAG = "AtenaSyncNsd"
        const val SERVICE_TYPE = "_atena-sync._tcp."
    }
}
