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
import android.util.Log
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Paths

/** Boots loopback + LAN Ktor CIO engines and NSD discovery on Android. */
class AndroidServer(private val context: Context) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var database: Database? = null
    private var orchestrator: SyncOrchestrator? = null
    private var remote: RemoteSyncClient? = null
    private var nsd: NsdDiscovery? = null
    private var loopback: EmbeddedServer<*, *>? = null
    private var lan: EmbeddedServer<*, *>? = null

    val token: String get() = database?.authToken().orEmpty()
    val deviceId: String get() = database?.deviceId().orEmpty()

    fun start(): String {
        Class.forName("org.sqlite.JDBC")
        val dataDir = Paths.get(context.filesDir.absolutePath, "atena-sync")
        ensureDir(dataDir)
        val config = Config(
            loopbackPort = DEFAULT_LOOPBACK_PORT,
            lanPort = DEFAULT_LAN_PORT,
            dataDir = dataDir,
            tokenOverride = null,
            hubUrl = null,
            hubToken = null,
            deviceName = android.os.Build.MODEL?.take(40) ?: "Android",
            deviceIdOverride = null,
            disableMdns = false
        )
        val db = Database(config.databaseFile)
        db.init(null, null)
        database = db

        val syncService = SyncService(db)
        val connections = ConnectionManager()
        val peers = PeerRegistry()
        val client = RemoteSyncClient()
        remote = client
        val orch = SyncOrchestrator(
            database = db,
            syncService = syncService,
            connections = connections,
            peers = peers,
            remote = client,
            deviceName = config.deviceName,
            scope = scope
        )
        orch.start()
        orchestrator = orch

        nsd = NsdDiscovery(
            context = context,
            deviceId = db.deviceId(),
            deviceName = config.deviceName,
            lanPort = config.lanPort,
            peers = peers,
            connections = connections
        ).also { it.start() }

        loopback = embeddedServer(CIO, port = config.loopbackPort, host = "127.0.0.1") {
            configureSyncApi(db, syncService, orch, connections, peers, config, loopbackOnlySetup = true)
        }.also { it.start(wait = false) }

        lan = embeddedServer(CIO, port = config.lanPort, host = "0.0.0.0") {
            configureSyncApi(db, syncService, orch, connections, peers, config, loopbackOnlySetup = false)
        }.also { it.start(wait = false) }

        Log.i(TAG, "Atena Sync listening loopback=${config.loopbackPort} lan=${config.lanPort}")
        return db.authToken()
    }

    override fun close() {
        orchestrator?.stop()
        nsd?.close()
        remote?.close()
        scope.cancel()
        runCatching { loopback?.stop(500, 1000) }
        runCatching { lan?.stop(500, 1000) }
    }

    companion object {
        private const val TAG = "AtenaSyncAndroid"
    }
}
