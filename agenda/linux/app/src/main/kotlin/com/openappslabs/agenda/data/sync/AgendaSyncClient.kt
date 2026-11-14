/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Agenda
 *
 * Agenda is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Agenda is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Agenda.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.agenda.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** What went wrong, so the UI can show a localized sentence. */
enum class SyncErrorKind {
    NOT_CONFIGURED,
    UNAUTHORIZED,
    NETWORK,
    SERVER,
    INVALID_RESPONSE
}

class SyncException(
    val kind: SyncErrorKind,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * The HTTP side of the agenda sync: one POST that pushes and pulls, plus the
 * ping used by the "test connection" button.
 */
class AgendaSyncClient(
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    suspend fun ping(config: SyncConfig): Long = withContext(Dispatchers.IO) {
        val response = execute(config = config, path = "/api/v1/ping", body = null)
        runCatching { json.decodeFromString<PingDto>(response).serverTime }
            .getOrDefault(System.currentTimeMillis())
    }

    suspend fun sync(config: SyncConfig, request: AgendaSyncRequestDto): AgendaSyncResponseDto =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(AgendaSyncRequestDto.serializer(), request)
            val response = execute(config = config, path = SYNC_PATH, body = body)
            try {
                json.decodeFromString(AgendaSyncResponseDto.serializer(), response)
            } catch (error: Exception) {
                throw SyncException(
                    SyncErrorKind.INVALID_RESPONSE,
                    "could not read the server response: ${error.message}",
                    error
                )
            }
        }

    private fun execute(config: SyncConfig, path: String, body: String?): String {
        val base = baseUrl(config.serverUrl)
        if (base.isEmpty()) {
            throw SyncException(SyncErrorKind.NOT_CONFIGURED, "server URL is empty")
        }
        val request = try {
            HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer ${config.token}")
                .apply {
                    if (body != null) {
                        header("Content-Type", "application/json; charset=utf-8")
                        POST(HttpRequest.BodyPublishers.ofString(body))
                    } else {
                        GET()
                    }
                }
                .build()
        } catch (error: IllegalArgumentException) {
            throw SyncException(SyncErrorKind.NOT_CONFIGURED, "invalid server URL: $base", error)
        }

        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (error: IOException) {
            throw SyncException(SyncErrorKind.NETWORK, error.message ?: "network error", error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SyncException(SyncErrorKind.NETWORK, "interrupted", error)
        }

        return when (response.statusCode()) {
            in 200..299 -> response.body()
            401, 403 -> throw SyncException(
                SyncErrorKind.UNAUTHORIZED,
                "the server rejected the token"
            )
            else -> {
                val detail = runCatching {
                    json.decodeFromString(SyncErrorDto.serializer(), response.body()).error
                }.getOrNull()
                throw SyncException(
                    SyncErrorKind.SERVER,
                    detail?.takeIf { it.isNotBlank() } ?: "HTTP ${response.statusCode()}"
                )
            }
        }
    }

    companion object {
        const val SYNC_PATH = "/api/v1/tools/agenda/sync"

        /**
         * Accepts what people type: a domain, an IP, a trailing slash, or a
         * full endpoint copied from the docs. A LAN address almost always
         * speaks plain HTTP; a public domain almost always sits behind TLS.
         */
        fun baseUrl(raw: String): String {
            var url = raw.trim()
            if (url.isEmpty()) return ""
            if (!url.contains("://")) {
                val host = url.substringBefore('/').substringBefore(':')
                url = if (isLocalHost(host)) "http://$url" else "https://$url"
            }
            url = url.trimEnd('/')
            url = url.removeSuffix(SYNC_PATH).removeSuffix("/api/v1")
            return url.trimEnd('/')
        }

        private fun isLocalHost(host: String): Boolean {
            val name = host.lowercase()
            if (name == "localhost" || name.endsWith(".local") || name.endsWith(".lan")) return true
            if (name == "::1" || name == "[::1]") return true
            val parts = name.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size != 4) return false
            return when {
                parts[0] == 127 -> true
                parts[0] == 10 -> true
                parts[0] == 192 && parts[1] == 168 -> true
                parts[0] == 172 && parts[1] in 16..31 -> true
                else -> false
            }
        }
    }
}

@kotlinx.serialization.Serializable
private data class PingDto(
    val status: String = "ok",
    val serverTime: Long = 0L
)
