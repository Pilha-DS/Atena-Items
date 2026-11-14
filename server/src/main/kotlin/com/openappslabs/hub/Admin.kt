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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.security.MessageDigest
import java.util.Base64

/**
 * The admin screen: one page, server-rendered, no JavaScript and no accounts.
 *
 * Regular clients never see it — they keep authenticating with a token. The
 * login exists only for the person running the hub, and the credentials come
 * from `ADMIN_USER` / `ADMIN_PASSWORD`. Without both variables the route
 * answers 404, so the page cannot exist with an empty password.
 *
 * What it answers, which is why it exists:
 *
 *  - which devices are syncing, when they did it last, and from where;
 *  - how much each one sent and received, per stream and in total;
 *  - which tokens are valid, and the button that creates and revokes them.
 */
fun Route.adminRoutes(config: Config, database: Database) {

    get("/admin") {
        if (!call.requireAdmin(config)) return@get
        call.respondHtml(renderDashboard(config, database))
    }
    post("/admin/tokens") {
        if (!call.requireAdmin(config)) return@post
        // A form posted by another origin is refused: the browser would send
        // the cached basic-auth credentials along with it.
        if (call.request.header("Sec-Fetch-Site") == "cross-site") {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("cross-site request refused"))
            return@post
        }
        val label = call.receiveParameters()["label"].orEmpty()
        val created = database.createToken(label)
        call.respondHtml(renderCreatedToken(created))
    }

    post("/admin/tokens/{id}/revoke") {
        if (!call.requireAdmin(config)) return@post
        if (call.request.header("Sec-Fetch-Site") == "cross-site") {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("cross-site request refused"))
            return@post
        }
        val id = call.parameters["id"]?.toLongOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid token id"))
            return@post
        }
        database.revokeToken(id)
        call.respondHtml(renderDashboard(config, database, notice = "Token revogado."))
    }

    /**
     * Only clears the credentials the browser cached: basic auth has no session
     * to destroy, so answering 401 with a challenge makes the browser ask again.
     */
    get("/admin/logout") {
        call.response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"Atena Hub\", charset=\"UTF-8\"")
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("logged out"))
    }
}

/**
 * Basic auth against the environment. Both fields are compared in constant
 * time with `MessageDigest.isEqual`, so a wrong password cannot be discovered
 * by timing, and the realm is answered by [respond] itself.
 */
private suspend fun ApplicationCall.requireAdmin(config: Config): Boolean {
    if (!config.adminEnabled) {
        respond(HttpStatusCode.NotFound, ErrorResponse("not found"))
        return false
    }
    val header = request.headers[HttpHeaders.Authorization]
    val credentials = header?.takeIf { it.startsWith("Basic ", ignoreCase = true) }
        ?.let { decodeBasic(it.substring(6).trim()) }

    // Same answer for "no header" and "wrong password": the client learns
    // nothing about which half was wrong.
    val userOk = credentials != null && matches(config.adminUser.orEmpty(), credentials.first)
    val passOk = credentials != null && matches(config.adminPassword.orEmpty(), credentials.second)
    if (userOk && passOk) return true

    response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"Atena Hub\", charset=\"UTF-8\"")
    respond(HttpStatusCode.Unauthorized, ErrorResponse("admin login required"))
    return false
}

private fun decodeBasic(value: String): Pair<String, String>? = runCatching {
    val decoded = String(Base64.getDecoder().decode(value))
    val separator = decoded.indexOf(':')
    if (separator < 0) null else decoded.substring(0, separator) to decoded.substring(separator + 1)
}.getOrNull()

private fun matches(expected: String, provided: String): Boolean =
    MessageDigest.isEqual(expected.toByteArray(), provided.toByteArray())

private suspend fun ApplicationCall.respondHtml(html: String) {
    // The page lists tokens and device addresses: never let a proxy keep it.
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(html, ContentType.Text.Html)
}

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------

private class Dashboard(
    val notes: Int,
    val trashed: Int,
    val links: Int,
    val events: Int,
    val categories: Int,
    val devices: List<DeviceSummaryDto>,
    val activity: List<ActivityDto>,
    val tokens: List<TokenDto>
)

private fun renderDashboard(config: Config, database: Database, notice: String? = null): String {
    val data = runCatching {
        database.read { connection ->
            Dashboard(
                notes = connection.query("SELECT COUNT(*) FROM notes WHERE is_trashed = 0") { it.getInt(1) } ?: 0,
                trashed = connection.query("SELECT COUNT(*) FROM notes WHERE is_trashed = 1") { it.getInt(1) } ?: 0,
                links = connection.query("SELECT COUNT(*) FROM links") { it.getInt(1) } ?: 0,
                events = connection.query("SELECT COUNT(*) FROM agenda_events") { it.getInt(1) } ?: 0,
                categories = connection.query("SELECT COUNT(*) FROM categories") { it.getInt(1) } ?: 0,
                devices = database.deviceSummaries(connection),
                activity = database.recentActivity(connection),
                tokens = listOf(database.masterTokenDto(connection)) + database.tokens(connection)
            )
        }
    }.getOrElse { error ->
        return messagePage("Erro ao ler o banco", error.message ?: "erro desconhecido")
    }

    val deviceRows = data.devices.joinToString("") { device ->
        val name = escapeHtml(device.name.ifBlank { "sem nome" })
        val id = escapeHtml(device.id)
        val stream = streamBadge(device.lastStream)
        val created = if (device.rounds == 0) {
            """<span class="muted">nunca sincronizou</span>"""
        } else {
            "${relative(device.lastSync)} <span class=\"muted\">(${formatTime(device.lastSync)})</span>"
        }
        """
        <tr>
          <td><strong>$name</strong><br><span class="muted mono">$id</span></td>
          <td class="muted mono">${escapeHtml(device.ip)}</td>
          <td>$created</td>
          <td>$stream</td>
          <td class="num">${device.pushed}</td>
          <td class="num">${device.pulled}</td>
          <td class="num muted">${formatBytes(device.bytesIn)} ↑ · ${formatBytes(device.bytesOut)} ↓</td>
          <td class="muted">${escapeHtml(device.tokenLabel.ifBlank { "—" })}</td>
          <td class="num muted">${device.rounds}</td>
        </tr>
        """.trimIndent()
    }.ifEmpty {
        """<tr><td colspan="9" class="muted">Nenhum dispositivo sincronizou ainda.</td></tr>"""
    }

    val activityRows = data.activity.joinToString("") { entry ->
        val stream = streamBadge(entry.stream)
        val device = escapeHtml(entry.deviceName.ifBlank { entry.deviceId })
        """
        <tr>
          <td class="nowrap">${formatTime(entry.at)} <span class="muted">(${relative(entry.at)})</span></td>
          <td>$device</td>
          <td>$stream</td>
          <td class="num">${entry.pushed}</td>
          <td class="num">${entry.pulled}</td>
          <td class="num muted">${formatBytes(entry.bytesIn)}</td>
          <td class="num muted">${formatBytes(entry.bytesOut)}</td>
          <td class="muted mono">${escapeHtml(entry.ip)}</td>
          <td class="muted">${escapeHtml(entry.tokenLabel.ifBlank { "—" })}</td>
        </tr>
        """.trimIndent()
    }.ifEmpty {
        """<tr><td colspan="9" class="muted">Nada sincronizou ainda. Abra um app e mande um sync.</td></tr>"""
    }

    val tokenRows = data.tokens.joinToString("") { token ->
        val state = if (token.revoked) {
            """<span class="tag bad">revogado</span>"""
        } else {
            """<span class="tag ok">ativo</span>"""
        }
        val action = when {
            token.revoked -> ""
            token.id == 0L -> """<span class="muted">token do boot</span>"""
            else -> """
                <form method="post" action="/admin/tokens/${token.id}/revoke" class="inline">
                  <button type="submit" class="danger">Revogar</button>
                </form>
            """.trimIndent()
        }
        """
        <tr>
          <td>${escapeHtml(token.label)}</td>
          <td class="mono">${escapeHtml(token.prefix)}</td>
          <td class="muted nowrap">${formatTime(token.createdAt)}</td>
          <td>$state</td>
          <td>$action</td>
        </tr>
        """.trimIndent()
    }

    val noticeHtml = notice?.let { """<p class="notice">${escapeHtml(it)}</p>""" } ?: ""

    return """
        <!doctype html>
        <html lang="pt-BR">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <meta http-equiv="refresh" content="30">
          <title>Atena Hub · Admin</title>
          <style>
            body { font-family: system-ui, sans-serif; margin: 0; padding: 32px 24px;
                   background: #17191c; color: #e8eaed; }
            main { max-width: 1080px; margin: 0 auto; }
            h1 { font-size: 1.35rem; margin: 0 0 4px; }
            h2 { font-size: 1.05rem; margin: 32px 0 10px; }
            a { color: #8ab4f8; }
            code, .mono { background: #24272b; border-radius: 6px; padding: 1px 5px; font-size: .86em; }
            .muted { color: #9aa0a6; }
            .num { text-align: right; font-variant-numeric: tabular-nums; }
            .nowrap { white-space: nowrap; }
            .top { display: flex; justify-content: space-between; align-items: baseline; gap: 16px; }
            .cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
                     gap: 10px; margin-top: 18px; }
            .card { background: #1d2024; border: 1px solid #2c3034; border-radius: 10px; padding: 12px 14px; }
            .card b { display: block; font-size: 1.4rem; font-variant-numeric: tabular-nums; }
            .card span { color: #9aa0a6; font-size: .8rem; }
            table { border-collapse: collapse; width: 100%; }
            .scroll { overflow-x: auto; }
            .scroll table { min-width: 700px; }
            th, td { text-align: left; padding: 7px 8px; border-bottom: 1px solid #2c3034;
                     vertical-align: top; font-size: .9rem; }
            th { font-weight: 600; color: #9aa0a6; font-size: .78rem; text-transform: uppercase;
                 letter-spacing: .03em; }
            tr:hover td { background: #1d2024; }
            .tag { display: inline-block; padding: 1px 8px; border-radius: 99px; font-size: .75rem;
                   border: 1px solid #3a3f45; color: #c9ced4; }
            .tag.ok { border-color: #2e5c3a; color: #86d99a; }
            .tag.bad { border-color: #5c2e2e; color: #e08c8c; }
            .notice { background: #232b25; border: 1px solid #2e5c3a; border-radius: 8px;
                      padding: 10px 14px; color: #a8e0b6; }
            form.inline { display: inline; margin: 0; }
            button { background: #2b2f34; color: #e8eaed; border: 1px solid #3a3f45;
                     border-radius: 8px; padding: 6px 12px; font: inherit; font-size: .85rem;
                     cursor: pointer; }
            button:hover { background: #343941; }
            button.danger { border-color: #5c2e2e; color: #e08c8c; }
            button.primary { background: #2b4a78; border-color: #3b64a0; }
            .create { display: flex; gap: 10px; align-items: center; margin-top: 12px; }
            .create input { background: #1d2024; border: 1px solid #3a3f45; color: #e8eaed;
                            border-radius: 8px; padding: 7px 10px; font: inherit; min-width: 240px; }
            footer { margin-top: 40px; color: #9aa0a6; font-size: .82rem; }
          </style>
        </head>
        <body>
          <main>
            <div class="top">
              <div>
                <h1>Atena Hub · Admin</h1>
                <span class="muted">versão ${BuildInfo.VERSION} · monitor de sincronização</span>
              </div>
              <div class="muted">
                <a href="/">início</a> · <a href="/admin/logout">sair</a>
              </div>
            </div>

            $noticeHtml

            <div class="cards">
              <div class="card"><b>${data.devices.size}</b><span>dispositivos</span></div>
              <div class="card"><b>${data.notes}</b><span>notas</span></div>
              <div class="card"><b>${data.links}</b><span>links</span></div>
              <div class="card"><b>${data.events}</b><span>eventos</span></div>
              <div class="card"><b>${data.categories}</b><span>categorias</span></div>
              <div class="card"><b>${data.trashed}</b><span>na lixeira</span></div>
            </div>

            <h2>Dispositivos</h2>
            <div class="scroll">
            <table>
              <tr>
                <th>Dispositivo</th><th>IP</th><th>Último sync</th><th>Stream</th>
                <th class="num">Enviou</th><th class="num">Baixou</th><th class="num">Volume</th>
                <th>Token</th><th class="num">Rodadas</th>
              </tr>
              $deviceRows
            </table>
            </div>

            <h2>Últimas sincronizações</h2>
            <div class="scroll">
            <table>
              <tr>
                <th>Quando</th><th>Dispositivo</th><th>Stream</th>
                <th class="num">Enviou</th><th class="num">Baixou</th>
                <th class="num">Recebeu</th><th class="num">Devolveu</th>
                <th>IP</th><th>Token</th>
              </tr>
              $activityRows
            </table>
            </div>

            <h2>Tokens</h2>
            <p class="muted">Cada app usa um token. O token completo só aparece uma vez,
            no momento em que é criado; se perder, revogue e crie outro.</p>
            <div class="scroll">
            <table>
              <tr><th>Rótulo</th><th>Token</th><th>Criado</th><th>Estado</th><th></th></tr>
              $tokenRows
            </table>
            </div>

            <h2>Novo token</h2>
            <form method="post" action="/admin/tokens" class="create">
              <input type="text" name="label" maxlength="64" placeholder="ex.: celular do João" required>
              <button type="submit" class="primary">Criar token</button>
            </form>

            <footer>
              Login definido por <code>ADMIN_USER</code> e <code>ADMIN_PASSWORD</code> no
              ambiente do servidor · apps apontam para
              <code>${escapeHtml(config.publicUrl ?: "http://<esta-máquina>:${config.port}")}</code>.
              Se estiver acessando por HTTP puro, use HTTPS (ou um túnel) para não
              mandar a senha em claro.
            </footer>
          </main>
        </body>
        </html>
    """.trimIndent()
}

/** After creating: the only moment the secret exists on screen. */
private fun renderCreatedToken(created: TokenDto): String {
    val secret = created.secret.orEmpty()
    return """
        <!doctype html>
        <html lang="pt-BR">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>Atena Hub · Token criado</title>
          <style>
            body { font-family: system-ui, sans-serif; margin: 0; padding: 32px 24px;
                   background: #17191c; color: #e8eaed; }
            main { max-width: 720px; margin: 0 auto; }
            a { color: #8ab4f8; }
            .muted { color: #9aa0a6; }
            .secret { background: #1d2024; border: 1px solid #3b64a0; border-radius: 10px;
                      padding: 16px; font-family: ui-monospace, monospace; font-size: 1rem;
                      word-break: break-all; user-select: all; }
            .warn { background: #2b2621; border: 1px solid #5c4b2e; border-radius: 8px;
                    padding: 10px 14px; color: #e0c98c; }
          </style>
        </head>
        <body>
          <main>
            <h1>Token criado</h1>
            <p>Rótulo: <strong>${escapeHtml(created.label)}</strong></p>
            <p class="muted">Cole este valor em <em>Configurações → Sync</em> no app:</p>
            <p class="secret">${escapeHtml(secret)}</p>
            <p class="warn">Ele não será mostrado de novo. Se perder, revogue este e crie outro.</p>
            <p><a href="/admin">Voltar ao painel</a></p>
          </main>
        </body>
        </html>
    """.trimIndent()
}

private fun messagePage(title: String, message: String): String = """
    <!doctype html>
    <html lang="pt-BR">
    <head><meta charset="utf-8"><title>Atena Hub</title></head>
    <body style="font-family: system-ui, sans-serif; padding: 40px; background: #17191c; color: #e8eaed;">
      <h1>$title</h1>
      <p>${escapeHtml(message)}</p>
      <p><a href="/admin" style="color:#8ab4f8">voltar</a></p>
    </body>
    </html>
""".trimIndent()

/** The stream sizes are labelled the same way everywhere on the page. */
private fun streamBadge(stream: String): String = when (stream) {
    "notes" -> """<span class="tag">notas</span>"""
    "links" -> """<span class="tag">links</span>"""
    "agenda" -> """<span class="tag">agenda</span>"""
    else -> """<span class="muted">—</span>"""
}

private fun formatTime(epoch: Long): String {
    if (epoch <= 0) return "—"
    return java.time.Instant.ofEpochMilli(epoch)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm"))
}

/** "há quanto tempo", which is what tells the admin a device is alive. */
private fun relative(epoch: Long): String {
    if (epoch <= 0) return "nunca"
    val diff = System.currentTimeMillis() - epoch
    return when {
        diff < 60_000L -> "agora"
        diff < 3_600_000L -> "há ${diff / 60_000L} min"
        diff < 86_400_000L -> "há ${diff / 3_600_000L} h"
        else -> "há ${diff / 86_400_000L} d"
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format("%.1f KB", bytes / 1024.0)
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}
