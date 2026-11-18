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

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class RemoteSyncClient {

    private val log = LoggerFactory.getLogger(RemoteSyncClient::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    private val http = HttpClient(CIO) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        engine {
            requestTimeout = 30_000
        }
    }

    suspend fun sync(
        baseUrl: String,
        token: String,
        request: SyncRequest
    ): Pair<SyncResponse, Long> {
        val started = System.nanoTime()
        val url = baseUrl.trimEnd('/') + "/api/v1/sync"
        val response = http.post(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        val latencyMs = (System.nanoTime() - started) / 1_000_000L
        if (response.status == HttpStatusCode.Unauthorized) {
            throw RemoteSyncException("unauthorized")
        }
        if (response.status.value >= 400) {
            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            throw RemoteSyncException("remote sync failed: HTTP ${response.status.value} $body")
        }
        val payload = response.body<SyncResponse>()
        log.debug("remote sync ok in {} ms cursor={}", latencyMs, payload.cursor)
        return payload to latencyMs
    }

    fun close() {
        http.close()
    }
}

class RemoteSyncException(message: String) : Exception(message)
