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
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

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

/** Talks to the local Atena Sync daemon (setup, link ranking, hub fallback). */
class AtenaSyncLocal @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun fetchSetup(baseUrl: String = ATENA_SYNC_LOOPBACK): AtenaSyncSetup =
        withContext(Dispatchers.IO) {
            val body = request(baseUrl.trimEnd('/') + "/v1/setup", method = "GET", token = null)
            json.decodeFromString(AtenaSyncSetup.serializer(), body)
        }

    suspend fun fetchLinks(config: SyncConfig): AtenaSyncLinksResponse =
        withContext(Dispatchers.IO) {
            val body = request(baseUrl(config) + "/v1/links", method = "GET", token = config.token)
            json.decodeFromString(AtenaSyncLinksResponse.serializer(), body)
        }

    suspend fun fetchHub(config: SyncConfig): AtenaSyncHubStatus =
        withContext(Dispatchers.IO) {
            val body = request(baseUrl(config) + "/v1/hub", method = "GET", token = config.token)
            json.decodeFromString(AtenaSyncHubStatus.serializer(), body)
        }

    suspend fun putHub(config: SyncConfig, hubUrl: String, hubToken: String): AtenaSyncHubStatus =
        withContext(Dispatchers.IO) {
            val payload = json.encodeToString(
                AtenaSyncHubConfig.serializer(),
                AtenaSyncHubConfig(url = hubUrl, token = hubToken)
            )
            val body = request(
                baseUrl(config) + "/v1/hub",
                method = "PUT",
                token = config.token,
                payload = payload
            )
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

    private fun request(
        url: String,
        method: String,
        token: String?,
        payload: String? = null
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3_000
            readTimeout = 5_000
            setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
            if (payload != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                OutputStreamWriter(outputStream, Charsets.UTF_8).use { it.write(payload) }
            }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw SyncException(SyncErrorKind.NETWORK, "HTTP $code from $url")
        }
        return body
    }
}
