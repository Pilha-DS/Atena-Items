/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Atena Hub
 *
 * Atena Hub is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Atena Hub is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Atena Hub.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.hub

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentLength
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

private val log = LoggerFactory.getLogger("AtenaHub")

fun main() {
    val config = Config.fromEnv()
    val database = Database(config.databaseFile)
    database.init(config.tokenOverride)
    val syncService = SyncService(database)
    val agendaSyncService = AgendaSyncService(database)
    val linksSyncService = LinksSyncService(database)
    val storeService = StoreService(database, config.maxRecordBytes)
    val blobs = BlobStore(config.blobDir, config.maxBlobBytes)
    blobs.purgeStaleTemporaries()

    printBanner(config, database)

    embeddedServer(Netty, port = config.port, host = config.host) {
        hubModule(
            database = database,
            syncService = syncService,
            agendaSyncService = agendaSyncService,
            linksSyncService = linksSyncService,
            storeService = storeService,
            blobs = blobs,
            config = config
        )
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
    log.info(" Atena Hub {} listening on {}:{}", BuildInfo.VERSION, config.host, config.port)
    log.info(" data file: {}", config.databaseFile.toAbsolutePath())
    if (config.isUsingLegacyDatabase) {
        log.info(" using the previous Jotter database (nothing to migrate)")
    }
    log.info(" tools: {}", Tools.all.joinToString { "${it.id} ${it.version}" })
    log.info(" store: universal (records + files), no release needed for a new app")
    log.info("")
    log.info(" hub token (treat it like a password):")
    log.info("   {}", token)
    log.info("")
    log.info(" clients: server URL = http://<this-machine>:{}", config.port)
    config.publicUrl?.let { log.info(" public URL: {}", it) }
    if (config.adminEnabled) {
        log.info(" admin screen: /admin (login \"{}\", password from ADMIN_PASSWORD)", config.adminUser)
    } else {
        log.info(" admin screen: disabled (set ADMIN_USER and ADMIN_PASSWORD to enable it)")
    }
    log.info(line)
}

private fun Application.hubModule(
    database: Database,
    syncService: SyncService,
    agendaSyncService: AgendaSyncService,
    linksSyncService: LinksSyncService,
    storeService: StoreService,
    blobs: BlobStore,
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
            call.respond(
                HealthResponse(status = "ok", notes = notes, tools = Tools.all.size)
            )
        }

        get("/") {
            call.respondText(indexPage(config, database), ContentType.Text.Html)
        }

        // The admin screen is HTML and lives outside /api: it authenticates
        // with ADMIN_USER/ADMIN_PASSWORD, not with a client token.
        adminRoutes(config, database, storeService)

        route("/api/v1") {
            get("/ping") {
                if (!call.requireToken(database)) return@get
                call.respond(PingResponse(serverTime = System.currentTimeMillis()))
            }

            /** Discovery: what this hub hosts, so clients can adapt. */
            get("/tools") {
                if (!call.requireToken(database)) return@get
                call.respond(ToolsResponse(tools = Tools.all.map(ToolInfo::toDto)))
            }

            get("/stats") {
                if (!call.requireToken(database)) return@get
                val stats = database.read { connection ->
                    StatsResponse(
                        notes = connection.query("SELECT COUNT(*) FROM notes WHERE is_trashed = 0") { it.getInt(1) } ?: 0,
                        trashed = connection.query("SELECT COUNT(*) FROM notes WHERE is_trashed = 1") { it.getInt(1) } ?: 0,
                        tombstones = connection.query("SELECT COUNT(*) FROM tombstones") { it.getInt(1) } ?: 0,
                        categories = connection.query("SELECT COUNT(*) FROM categories") { it.getInt(1) } ?: 0,
                        links = connection.query("SELECT COUNT(*) FROM links") { it.getInt(1) } ?: 0,
                        cursor = connection.query(
                            "SELECT value FROM meta WHERE key = ?", Database.KEY_SEQ
                        ) { it.getString(1)?.toLongOrNull() ?: 0L } ?: 0L,
                        serverTime = System.currentTimeMillis(),
                        devices = database.devices(connection)
                    )
                }
                call.respond(stats)
            }

            /**
             * Legacy path, kept exactly as it was: the Jotter apps on Android
             * and Linux are already deployed and must keep syncing.
             */
            post("/sync") {
                if (!call.requireToken(database)) return@post
                val bytesIn = call.request.contentLength() ?: 0L
                val request = call.receive<SyncRequest>()
                val remoteIp = call.request.origin.remoteHost
                call.respond(syncService.sync(request, remoteIp, call.caller(), bytesIn))
            }

            // Read API used by other tools (read-only consumers of the notes).
            jotterNotesApi(database)

            /**
             * Agenda: events have their own stream and cursor. The path is
             * namespaced by tool, unlike the legacy `/sync` kept for Jotter.
             */
            post("/tools/agenda/sync") {
                if (!call.requireToken(database)) return@post
                val bytesIn = call.request.contentLength() ?: 0L
                val request = call.receive<AgendaSyncRequest>()
                call.respond(
                    agendaSyncService.sync(
                        request,
                        call.request.origin.remoteHost,
                        call.caller(),
                        bytesIn
                    )
                )
            }

            /**
             * Jotter link inbox: a third stream, so a links cursor can never be
             * confused with a notes one. Lives under the tool that owns it.
             */
            post("/tools/jotter/links/sync") {
                if (!call.requireToken(database)) return@post
                val bytesIn = call.request.contentLength() ?: 0L
                val request = call.receive<LinksSyncRequest>()
                call.respond(
                    linksSyncService.sync(
                        request,
                        call.request.origin.remoteHost,
                        call.caller(),
                        bytesIn
                    )
                )
            }

            /**
             * The universal store. It is not a tool: it is what a *new* tool
             * uses instead of asking for a new endpoint here. Records have a
             * cursor per namespace/collection; files are content-addressed.
             */
            storeApi(config, database, storeService, blobs)
        }
    }
}

private class HubTotals(val notes: Int, val links: Int, val cursor: String, val devices: Int)

private fun indexPage(config: Config, database: Database): String {
    val stats = runCatching {
        database.read { connection ->
            HubTotals(
                notes = connection.query("SELECT COUNT(*) FROM notes") { it.getInt(1) } ?: 0,
                links = connection.query("SELECT COUNT(*) FROM links") { it.getInt(1) } ?: 0,
                cursor = connection.query(
                    "SELECT value FROM meta WHERE key = ?", Database.KEY_SEQ
                ) { it.getString(1) } ?: "0",
                devices = database.devices(connection).size
            )
        }
    }.getOrElse { HubTotals(0, 0, "0", 0) }

    val toolRows = Tools.all.joinToString("") { tool ->
        "<tr><td><code>${escapeHtml(tool.id)}</code></td><td>${escapeHtml(tool.name)}</td>" +
            "<td class=\"muted\">${escapeHtml(tool.description)}</td></tr>"
    }

    // The device list (names and addresses) is not public: it lives behind the
    // admin login, so this page only shows anonymous totals.
    val adminHtml = if (config.adminEnabled) {
        """<h2>Administração</h2>
        <p>Quem está sincronizando, o que cada dispositivo enviou e baixou, e os
        tokens dos clientes ficam em <a href="/admin">/admin</a> (com login).</p>"""
    } else {
        """<h2>Administração</h2>
        <p class="muted">Defina <code>ADMIN_USER</code> e <code>ADMIN_PASSWORD</code>
        para habilitar a tela de acompanhamento em <code>/admin</code>.</p>"""
    }

    return """
        <!doctype html>
        <html lang="pt-BR">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>Atena Hub</title>
          <style>
            body { font-family: system-ui, sans-serif; margin: 0; padding: 32px;
                   background: #17191c; color: #e8eaed; }
            main { max-width: 760px; margin: 0 auto; }
            code, pre { background: #24272b; border-radius: 6px; padding: 2px 6px; }
            pre { padding: 14px; overflow-x: auto; }
            h1 { font-size: 1.4rem; }
            a { color: #8ab4f8; }
            table { border-collapse: collapse; width: 100%; }
            th, td { text-align: left; padding: 6px 8px; border-bottom: 1px solid #2c3034; }
            th { font-weight: 600; color: #9aa0a6; }
            .muted { color: #9aa0a6; }
          </style>
        </head>
        <body>
          <main>
            <h1>Atena Hub ${BuildInfo.VERSION}</h1>
            <p class="muted">Servidor de ferramentas. Hoje o dono das notas é o Jotter.</p>
            <p>Notas armazenadas: <strong>${stats.notes}</strong> · links:
            <strong>${stats.links}</strong> · cursor: <strong>${stats.cursor}</strong> ·
            dispositivos: <strong>${stats.devices}</strong></p>

            <h2>Ferramentas</h2>
            <table>
              <tr><th>ID</th><th>Nome</th><th>Descrição</th></tr>
              $toolRows
            </table>

            $adminHtml

            <h2>Conectar um aplicativo</h2>
            <p>No app, abra <em>Configurações → Sync</em> e informe:</p>
            <pre>Server URL: ${config.publicUrl ?: "http://<esta-máquina>:${config.port}"}
Token:      (impresso no log na inicialização)</pre>
            <p>O token fica gravado no banco do hub, então ele não muda a cada restart.
            Para fixá-lo, use <code>ATENA_TOKEN</code>. Tokens extras, um por cliente,
            são criados em <code>/admin</code>.</p>
          </main>
        </body>
        </html>
    """.trimIndent()
}

/** Names and addresses are user input, so they must not break the pages. */
internal fun escapeHtml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
