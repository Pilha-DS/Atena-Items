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
 * The HTTP side of the sync. Small on purpose: one POST that both pushes and
 * pulls, plus a ping used by the "connect" button.
 */
class SyncClient(
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

    suspend fun ping(config: SyncConfig): Long = withContext(Dispatchers.IO) {        val response = execute(
            config = config,
            path = "/api/v1/ping",
            body = null
        )
        runCatching { json.decodeFromString<PingDto>(response).serverTime }
            .getOrDefault(System.currentTimeMillis())
    }

    suspend fun sync(config: SyncConfig, request: SyncRequestDto): SyncResponseDto =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(SyncRequestDto.serializer(), request)
            val response = execute(config = config, path = "/api/v1/sync", body = body)
            try {
                json.decodeFromString(SyncResponseDto.serializer(), response)
            } catch (error: Exception) {
                throw SyncException(
                    SyncErrorKind.INVALID_RESPONSE,
                    "could not read the server response: ${error.message}",
                    error
                )
            }
        }

    /**
     * The link inbox uses its own endpoint and cursor. Batching both streams in
     * one request would mean one slow stream delays the other, and the links
     * are tiny and independent from the notes.
     */
    suspend fun syncLinks(config: SyncConfig, request: SyncLinksRequestDto): SyncLinksResponseDto =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(SyncLinksRequestDto.serializer(), request)
            val response = execute(
                config = config,
                path = "/api/v1/tools/jotter/links/sync",
                body = body
            )
            try {
                json.decodeFromString(SyncLinksResponseDto.serializer(), response)
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

    private companion object {
        /**
         * Accepts what people actually type: a domain, an IP, a trailing slash,
         * or a full endpoint copied from the docs.
         *
         * No scheme? A private/LAN address almost always speaks plain HTTP,
         * while a public domain is almost always behind TLS, so the guess is
         * `http` for those and `https` for everything else. Typing the scheme
         * explicitly always wins.
         */
        fun baseUrl(raw: String): String {
            var url = raw.trim()
            if (url.isEmpty()) return ""
            if (!url.contains("://")) {
                val host = url.substringBefore('/').substringBefore(':')
                url = if (isLocalHost(host)) "http://$url" else "https://$url"
            }
            url = url.trimEnd('/')
            url = url.removeSuffix("/api/v1/sync").removeSuffix("/api/v1")
            return url.trimEnd('/')
        }

        /** localhost, 127.x, 10.x, 192.168.x, 172.16-31.x and *.local. */
        fun isLocalHost(host: String): Boolean {
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
