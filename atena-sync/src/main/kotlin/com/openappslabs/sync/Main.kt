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

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import kotlin.concurrent.thread

fun main() {
    val log = LoggerFactory.getLogger("AtenaSync")
    val config = Config.fromEnv()
    ensureDir(config.dataDir)

    val database = Database(config.databaseFile)
    database.init(config.tokenOverride, config.deviceIdOverride)

    if (!config.hubUrl.isNullOrBlank() && !config.hubToken.isNullOrBlank()) {
        database.setHub(config.hubUrl, config.hubToken)
    }

    val syncService = SyncService(database)
    val connections = ConnectionManager()
    val peers = PeerRegistry()
    val remote = RemoteSyncClient()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val orchestrator = SyncOrchestrator(
        database = database,
        syncService = syncService,
        connections = connections,
        peers = peers,
        remote = remote,
        deviceName = config.deviceName,
        scope = scope
    )
    orchestrator.start()

    val mdns = if (config.disableMdns) {
        null
    } else {
        MdnsDiscovery(
            deviceId = database.deviceId(),
            deviceName = config.deviceName,
            lanPort = config.lanPort,
            peers = peers,
            connections = connections
        ).also { it.start() }
    }

    val loopback = embeddedServer(Netty, port = config.loopbackPort, host = "127.0.0.1") {
        configureSyncApi(
            database = database,
            syncService = syncService,
            orchestrator = orchestrator,
            connections = connections,
            peers = peers,
            config = config,
            loopbackOnlySetup = true
        )
    }

    val lan = embeddedServer(Netty, port = config.lanPort, host = "0.0.0.0") {
        configureSyncApi(
            database = database,
            syncService = syncService,
            orchestrator = orchestrator,
            connections = connections,
            peers = peers,
            config = config,
            loopbackOnlySetup = false
        )
    }

    Runtime.getRuntime().addShutdownHook(thread(start = false) {
        log.info("shutting down")
        orchestrator.stop()
        mdns?.close()
        remote.close()
        scope.cancel()
        runCatching { loopback.stop(1_000, 2_000) }
        runCatching { lan.stop(1_000, 2_000) }
    })

    loopback.start(wait = false)
    lan.start(wait = false)

    log.info(
        """
        |
        |================================================================
        | ${BuildInfo.NAME} ${BuildInfo.VERSION}
        | loopback: http://127.0.0.1:${config.loopbackPort}
        | lan:      0.0.0.0:${config.lanPort}
        | data:     ${config.databaseFile}
        | device:   ${config.deviceName} (${database.deviceId()})
        | hub:      ${database.hubUrl() ?: "(none)"}
        |
        | pairing token (share with peers; treat like a password):
        |   ${database.authToken()}
        |
        | apps: point Sync URL at http://127.0.0.1:${config.loopbackPort}
        |       or GET /v1/setup on loopback for token + URL
        |================================================================
        """.trimMargin()
    )

    Thread.currentThread().join()
}
