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

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

fun Application.configureSyncApi(
    database: Database,
    @Suppress("UNUSED_PARAMETER") syncService: SyncService,
    orchestrator: SyncOrchestrator,
    connections: ConnectionManager,
    peers: PeerRegistry,
    config: Config,
    loopbackOnlySetup: Boolean
) {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    install(ContentNegotiation) { json(json) }
    install(CallLogging)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("request failed", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(cause.message ?: "error"))
        }
    }

    routing {
        get("/health") {
            call.respond(
                HealthResponse(
                    loopbackPort = config.loopbackPort,
                    lanPort = config.lanPort,
                    deviceId = database.deviceId(),
                    deviceName = config.deviceName,
                    peers = peers.all().size,
                    hubConfigured = !database.hubUrl().isNullOrBlank() && !database.hubToken().isNullOrBlank()
                )
            )
        }

        get("/v1/setup") {
            if (loopbackOnlySetup && call.request.local.remoteHost !in LOOPBACK_HOSTS) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("setup only on loopback"))
                return@get
            }
            call.respond(
                SetupResponse(
                    token = database.authToken(),
                    loopbackUrl = "http://127.0.0.1:${config.loopbackPort}",
                    deviceId = database.deviceId(),
                    deviceName = config.deviceName
                )
            )
        }

        get("/v1/links") {
            if (!authorize(call, database)) return@get
            val links = connections.all().map { it.toDto() }
            call.respond(
                LinksResponse(
                    links = links,
                    preferred = connections.preferredId(SyncOperation.JOTTER_TEXT)
                )
            )
        }

        get("/v1/peers") {
            if (!authorize(call, database)) return@get
            call.respond(PeersResponse(peers = peers.all().map { it.toDto() }))
        }

        get("/v1/hub") {
            if (!authorize(call, database)) return@get
            val url = database.hubUrl().orEmpty()
            call.respond(HubConfigResponse(url = url, configured = url.isNotBlank() && !database.hubToken().isNullOrBlank()))
        }

        put("/v1/hub") {
            if (!authorize(call, database)) return@put
            val body = call.receive<HubConfigRequest>()
            database.setHub(body.url, body.token)
            orchestrator.refreshHubLink()
            val url = database.hubUrl().orEmpty()
            call.respond(HubConfigResponse(url = url, configured = url.isNotBlank()))
        }

        get("/api/v1/ping") {
            if (!authorize(call, database)) return@get
            call.respond(PingResponse(serverTime = System.currentTimeMillis()))
        }

        post("/api/v1/sync") {
            if (!authorize(call, database)) return@post
            val request = call.receive<SyncRequest>()
            val response = orchestrator.handleLocalSync(request, fromPeer = !loopbackOnlySetup)
            call.respond(response)
        }

        post("/v1/streams/jotter/sync") {
            if (!authorize(call, database)) return@post
            val request = call.receive<SyncRequest>()
            val response = orchestrator.handleLocalSync(request, fromPeer = !loopbackOnlySetup)
            call.respond(response)
        }
    }
}

private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")

private suspend fun authorize(call: io.ktor.server.application.ApplicationCall, database: Database): Boolean {
    val header = call.request.header(io.ktor.http.HttpHeaders.Authorization).orEmpty()
    val token = when {
        header.startsWith("Bearer ", ignoreCase = true) -> header.removePrefix("Bearer ").trim()
        else -> call.request.header("X-Atena-Token")?.trim().orEmpty()
    }
    if (!database.tokenMatches(token)) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid or missing token"))
        return false
    }
    return true
}
