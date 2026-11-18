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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Default loopback of the Atena Sync daemon (phase 1). */
const val ATENA_SYNC_LOOPBACK = "http://127.0.0.1:18765"

@Serializable
data class AtenaSyncSetup(
    val token: String = "",
    val loopbackUrl: String = ATENA_SYNC_LOOPBACK,
    val deviceId: String = "",
    val deviceName: String = ""
)

@Serializable
data class AtenaSyncLink(
    val id: String = "",
    val kind: String = "",
    val available: Boolean = false,
    val label: String = "",
    val score: Double = 0.0
)

@Serializable
data class AtenaSyncLinksResponse(
    val links: List<AtenaSyncLink> = emptyList(),
    val preferred: String? = null
)

@Serializable
data class AtenaSyncHubConfig(
    val url: String = "",
    val token: String = ""
)

@Serializable
data class AtenaSyncHubStatus(
    val url: String = "",
    val configured: Boolean = false
)

/**
 * Talks to the local Atena Sync daemon (setup, link ranking, hub fallback).
 * Kept separate from [SyncClient] so the note wire format stays untouched.
 */
class AtenaSyncLocal(
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun fetchSetup(baseUrl: String = ATENA_SYNC_LOOPBACK): AtenaSyncSetup =
        withContext(Dispatchers.IO) {
            val body = get(baseUrl.trimEnd('/') + "/v1/setup", token = null)
            json.decodeFromString(AtenaSyncSetup.serializer(), body)
        }

    suspend fun fetchLinks(config: SyncConfig): AtenaSyncLinksResponse =
        withContext(Dispatchers.IO) {
            val body = get(baseUrl(config) + "/v1/links", token = config.token)
            json.decodeFromString(AtenaSyncLinksResponse.serializer(), body)
        }

    suspend fun fetchHub(config: SyncConfig): AtenaSyncHubStatus =
        withContext(Dispatchers.IO) {
            val body = get(baseUrl(config) + "/v1/hub", token = config.token)
            json.decodeFromString(AtenaSyncHubStatus.serializer(), body)
        }

    suspend fun putHub(config: SyncConfig, hubUrl: String, hubToken: String): AtenaSyncHubStatus =
        withContext(Dispatchers.IO) {
            val payload = json.encodeToString(
                AtenaSyncHubConfig.serializer(),
                AtenaSyncHubConfig(url = hubUrl, token = hubToken)
            )
            val body = put(baseUrl(config) + "/v1/hub", token = config.token, payload)
            json.decodeFromString(AtenaSyncHubStatus.serializer(), body)
        }

    fun isLocalDaemon(url: String): Boolean {
        val host = url.trim().removePrefix("http://").removePrefix("https://")
            .substringBefore('/').substringBefore(':').lowercase()
        return host == "127.0.0.1" || host == "localhost" || host == "::1"
    }

    private fun baseUrl(config: SyncConfig): String {
        var url = config.serverUrl.trim().trimEnd('/')
        if (url.isEmpty()) return ATENA_SYNC_LOOPBACK
        if (!url.contains("://")) url = "http://$url"
        return url.trimEnd('/')
    }

    private fun get(url: String, token: String?): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/json")
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw SyncException(SyncErrorKind.NETWORK, "HTTP ${response.statusCode()} from $url")
        }
        return response.body()
    }

    private fun put(url: String, token: String?, payload: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json; charset=utf-8")
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .PUT(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw SyncException(SyncErrorKind.NETWORK, "HTTP ${response.statusCode()} from $url")
        }
        return response.body()
    }
}
