/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Atena Docs
 *
 * Atena Docs is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Atena Docs is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Atena Docs.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.docs.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * What the app asks a local model for.
 *
 * An interface, not a concrete client, so the suggestion flow is testable
 * against a fake model and the UI never depends on HTTP.
 */
interface TagModel {
    /** True when the model server answers; false when there is nothing there. */
    suspend fun isAvailable(): Boolean

    /** Tag paths suggested for the prompt, one per line, already trimmed. */
    suspend fun suggest(prompt: String): List<String>

    /**
     * Why the last [suggest] came back empty, when the cause was not simply
     * "the model had nothing to say".
     *
     * The default keeps fakes and test doubles simple: no trouble means the
     * empty answer is taken at face value. Clients that talk over HTTP fill it
     * in, so the dialog can name the real problem (wrong key, wrong model,
     * address missing `/v1`) instead of the generic "IA indisponível".
     */
    fun trouble(): ModelTrouble? = null
}

/**
 * Ollama on localhost, over the documented HTTP API.
 *
 * No dependency and no cloud: one `POST /api/generate` with `stream=false`
 * returns the whole answer in `response`. A model that is not running is not an
 * error — [isAvailable] returns false and the caller falls back to the text
 * alone.
 */
class OllamaClient(
    private val settings: AiSettings,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()
) : TagModel {

    @Volatile
    private var resolvedModel: String? = settings.model.takeIf { it.isNotBlank() }

    @Volatile
    private var lastTrouble: ModelTrouble? = null

    override fun trouble(): ModelTrouble? = lastTrouble

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = HttpRequest.newBuilder(URI.create("${settings.url}/api/tags"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build()
            http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200
        }.getOrDefault(false)
    }

    override suspend fun suggest(prompt: String): List<String> = withContext(Dispatchers.IO) {
        lastTrouble = null
        val model = resolvedModel ?: run {
            // Null means the request itself failed; an empty list means the
            // server is up and has nothing installed. Different sentences.
            val installed = installedModels()
            if (installed == null) {
                lastTrouble = ModelTrouble(ModelIssue.UNREACHABLE, url = settings.url)
                return@withContext emptyList()
            }
            val first = installed.firstOrNull()
            if (first == null) {
                lastTrouble = ModelTrouble(ModelIssue.NO_MODEL, url = settings.url)
                return@withContext emptyList()
            }
            resolvedModel = first
            first
        }
        val body = buildString {
            append("{\"model\":\"").append(JsonField.escape(model)).append("\",")
            // Determinism matters more than creativity for a filing suggestion.
            append("\"prompt\":\"").append(JsonField.escape(prompt)).append("\",")
            append("\"stream\":false,\"options\":{\"temperature\":0,\"num_predict\":160}}")
        }
        val response = runCatching {
            val request = HttpRequest.newBuilder(URI.create("${settings.url}/api/generate"))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            http.send(request, HttpResponse.BodyHandlers.ofString())
        }.getOrNull()
        if (response == null) {
            lastTrouble = ModelTrouble(ModelIssue.UNREACHABLE, model = model, url = settings.url)
            return@withContext emptyList()
        }
        if (response.statusCode() != 200) {
            lastTrouble = ModelTrouble(
                issue = ModelIssue.MODEL_REFUSED,
                status = response.statusCode(),
                model = model,
                url = settings.url
            )
            return@withContext emptyList()
        }
        val text = JsonField.string(response.body(), "response")
        if (text.isNullOrBlank()) {
            lastTrouble = ModelTrouble(ModelIssue.BAD_ANSWER, status = 200, model = model, url = settings.url)
            return@withContext emptyList()
        }
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    /** Model names the server lists; null when the request itself failed. */
    private fun installedModels(): List<String>? {
        val body = runCatching {
            val request = HttpRequest.newBuilder(URI.create("${settings.url}/api/tags"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return@runCatching null
            response.body()
        }.getOrNull() ?: return null
        return JsonField.allStrings(body, "name")
    }
}
