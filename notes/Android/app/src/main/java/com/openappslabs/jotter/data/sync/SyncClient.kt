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
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import javax.inject.Inject

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
 * The HTTP side of the sync.
 *
 * `HttpURLConnection` instead of OkHttp/Ktor on purpose: the app has no HTTP
 * dependency, and one POST does not justify adding one (and its ~1 MB).
 */
class SyncClient @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    suspend fun ping(config: SyncConfig): Long = withContext(Dispatchers.IO) {
        val body = execute(config = config, path = "/api/v1/ping", body = null)
        runCatching { json.decodeFromString<PingDto>(body).serverTime }
            .getOrDefault(System.currentTimeMillis())
    }

    suspend fun sync(config: SyncConfig, request: SyncRequestDto): SyncResponseDto =
        withContext(Dispatchers.IO) {
            val payload = json.encodeToString(SyncRequestDto.serializer(), request)
            val body = execute(config = config, path = "/api/v1/sync", body = payload)
            try {
                json.decodeFromString(SyncResponseDto.serializer(), body)
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
            val payload = json.encodeToString(SyncLinksRequestDto.serializer(), request)
            val body = execute(
                config = config,
                path = "/api/v1/tools/jotter/links/sync",
                body = payload
            )
            try {
                json.decodeFromString(SyncLinksResponseDto.serializer(), body)
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

        val url = try {
            URL(base + path)
        } catch (error: MalformedURLException) {
            throw SyncException(SyncErrorKind.NOT_CONFIGURED, "invalid server URL: $base", error)
        }

        val connection = try {
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = if (body == null) "GET" else "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer ${config.token}")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
        } catch (error: IOException) {
            throw SyncException(SyncErrorKind.NETWORK, error.message ?: "network error", error)
        }

        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            when (code) {
                in 200..299 -> responseBody
                401, 403 -> throw SyncException(
                    SyncErrorKind.UNAUTHORIZED,
                    "the server rejected the token"
                )
                else -> {
                    val detail = runCatching {
                        json.decodeFromString(SyncErrorDto.serializer(), responseBody).error
                    }.getOrNull()
                    throw SyncException(
                        SyncErrorKind.SERVER,
                        detail?.takeIf { it.isNotBlank() } ?: "HTTP $code"
                    )
                }
            }
        } catch (error: IOException) {
            // A broken exchange is not worth keeping in the keep-alive pool.
            connection.disconnect()
            throw SyncException(SyncErrorKind.NETWORK, error.message ?: "network error", error)
        }
        // No `disconnect()` on the happy path on purpose: the response was read
        // to the end, so HttpURLConnection returns the socket to its pool and
        // the second request of the round — the link stream, same host — rides
        // the TLS session already negotiated. Closing it here would pay for a
        // new handshake every time, which is the bulk of an idle round's bytes.
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 60_000

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
            var value = raw.trim()
            if (value.isEmpty()) return ""
            if (!value.contains("://")) {
                val host = value.substringBefore('/').substringBefore(':')
                value = if (isLocalHost(host)) "http://$value" else "https://$value"
            }
            value = value.trimEnd('/')
            value = value.removeSuffix("/api/v1/sync").removeSuffix("/api/v1")
            return value.trimEnd('/')
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
