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

package com.openappslabs.jotter.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private val log = LoggerFactory.getLogger("JotterServer")

fun main() {
    val config = Config.fromEnv()
    val database = Database(config.databaseFile)
    database.init(config.tokenOverride)
    val syncService = SyncService(database)

    printBanner(config, database)

    embeddedServer(Netty, port = config.port, host = config.host) {
        jotterModule(database, syncService, config)
    }.start(wait = true)
}

/**
 * Prints the token once at boot. A container has no other way to show a
 * generated secret, and `docker compose logs` is where the user looks for it.
 */
private fun printBanner(config: Config, database: Database) {
    val token = database.authToken()
    val line = "=".repeat(64)
    log.info(line)
    log.info(" Jotter sync server {} listening on {}:{}", BuildInfo.VERSION, config.host, config.port)
    log.info(" data file: {}", config.databaseFile.toAbsolutePath())
    log.info("")
    log.info(" sync token (treat it like a password):")
    log.info("   {}", token)
    log.info("")
    log.info(" clients: server URL = http://<this-machine>:{}", config.port)
    config.publicUrl?.let { log.info(" public URL: {}", it) }
    log.info(line)
}

private fun Application.jotterModule(
    database: Database,
    syncService: SyncService,
    config: Config
) {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = true
            }
        )
    }
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/api/") }
    }
    install(StatusPages) {
        // A client sending malformed JSON is a client bug, not a server crash.
        exception<BadRequestException> { call, cause ->
            log.warn("bad request on ${call.request.path()}: ${cause.message}")
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("invalid request: ${cause.message ?: "malformed body"}")
            )
        }
        exception<Throwable> { call, cause ->
            log.error("request failed: ${call.request.path()}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(cause.message ?: "internal error")
            )
        }
    }

    routing {
        get("/health") {
            val notes = database.read { connection ->
                connection.query("SELECT COUNT(*) FROM notes") { it.getInt(1) } ?: 0
            }
            call.respond(HealthResponse(status = "ok", notes = notes))
        }

        get("/") {
            call.respondText(indexPage(config, database), ContentType.Text.Html)
        }

        route("/api/v1") {
            get("/ping") {
                if (!call.authorize(database)) return@get
                call.respond(PingResponse(serverTime = System.currentTimeMillis()))
            }

            get("/stats") {
                if (!call.authorize(database)) return@get
                val stats = database.read { connection ->
                    StatsResponse(
                        notes = connection.query("SELECT COUNT(*) FROM notes WHERE is_trashed = 0") { it.getInt(1) } ?: 0,
                        trashed = connection.query("SELECT COUNT(*) FROM notes WHERE is_trashed = 1") { it.getInt(1) } ?: 0,
                        tombstones = connection.query("SELECT COUNT(*) FROM tombstones") { it.getInt(1) } ?: 0,
                        categories = connection.query("SELECT COUNT(*) FROM categories") { it.getInt(1) } ?: 0,
                        cursor = connection.query(
                            "SELECT value FROM meta WHERE key = ?", Database.KEY_SEQ
                        ) { it.getString(1)?.toLongOrNull() ?: 0L } ?: 0L,
                        serverTime = System.currentTimeMillis(),
                        devices = database.devices(connection)
                    )
                }
                call.respond(stats)
            }

            post("/sync") {
                if (!call.authorize(database)) return@post
                val request = call.receive<SyncRequest>()
                val remoteIp = call.request.origin.remoteHost
                call.respond(syncService.sync(request, remoteIp))
            }
        }
    }
}

/**
 * Bearer token check. There is a single token per server instance, generated on
 * first start (or fixed with `JOTTER_SERVER_TOKEN`), which is exactly the
 * "only my devices may sync" rule without an account system to maintain.
 */
private suspend fun ApplicationCall.authorize(database: Database): Boolean {
    val header = request.headers["Authorization"]
    val provided = when {
        header != null && header.startsWith("Bearer ", ignoreCase = true) ->
            header.substring("Bearer ".length).trim()
        else -> request.headers["X-Jotter-Token"]?.trim()
    }
    if (database.tokenMatches(provided)) return true
    respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid or missing sync token"))
    return false
}

private fun indexPage(config: Config, database: Database): String {
    val stats = runCatching {
        database.read { connection ->
            val notes = connection.query("SELECT COUNT(*) FROM notes") { it.getInt(1) } ?: 0
            val cursor = connection.query(
                "SELECT value FROM meta WHERE key = ?", Database.KEY_SEQ
            ) { it.getString(1) } ?: "0"
            notes to (cursor to database.devices(connection))
        }
    }.getOrDefault(0 to ("0" to emptyList()))

    val deviceRows = stats.second.second.joinToString("") { device ->
        val name = escapeHtml(device.name.ifBlank { "unnamed device" })
        val seen = java.time.Instant.ofEpochMilli(device.lastSeen)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        "<tr><td>$name</td><td class=\"muted\">${escapeHtml(device.lastIp)}</td><td class=\"muted\">$seen</td></tr>"
    }.ifEmpty {
        "<tr><td colspan=\"3\" class=\"muted\">No device has synced yet.</td></tr>"
    }

    return """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>Jotter sync server</title>
          <style>
            body { font-family: system-ui, sans-serif; margin: 0; padding: 32px;
                   background: #17191c; color: #e8eaed; }
            main { max-width: 720px; margin: 0 auto; }
            code, pre { background: #24272b; border-radius: 6px; padding: 2px 6px; }
            pre { padding: 14px; overflow-x: auto; }
            h1 { font-size: 1.4rem; }
            table { border-collapse: collapse; width: 100%; }
            th, td { text-align: left; padding: 6px 8px; border-bottom: 1px solid #2c3034; }
            th { font-weight: 600; color: #9aa0a6; }
            .muted { color: #9aa0a6; }
          </style>
        </head>
        <body>
          <main>
            <h1>Jotter sync server ${BuildInfo.VERSION}</h1>
            <p class="muted">Running. This endpoint only serves the Jotter apps.</p>
            <p>Notes stored: <strong>${stats.first}</strong> · cursor: <strong>${stats.second.first}</strong></p>
            <h2>Devices</h2>
            <table>
              <tr><th>Name</th><th>Last IP</th><th>Last sync</th></tr>
              $deviceRows
            </table>
            <h2>Connect a device</h2>
            <p>Open <em>Settings → Sync</em> in the app and enter only:</p>
            <pre>Server URL: ${config.publicUrl ?: "http://<this-machine>:${config.port}"}
Token:      (printed in the container log at startup)</pre>
            <p>The app asks for a name for this device, then syncs right away.</p>
            <p class="muted">Token: check <code>docker compose logs</code>.
            To pin it yourself, set <code>JOTTER_SERVER_TOKEN</code>.</p>
          </main>
        </body>
        </html>
    """.trimIndent()
}

/** Device names are user input, so they must not break the status page. */
private fun escapeHtml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
