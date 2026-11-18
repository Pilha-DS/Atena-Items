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
 * A remote model over the OpenAI chat-completions dialect — which is what
 * DeepSeek speaks, so one client covers it and any service with the same shape.
 *
 * Two differences from [OllamaClient] that matter:
 *
 *  - the key goes in the `Authorization` header and is never written anywhere
 *    else — not to a log, not to an error message, not to the dialog;
 *  - the answer lives in `choices[0].message.content`, not in a flat
 *    `response` field.
 *
 * Failure is still a fallback, not an exception: no key, no network, 401, 429,
 * 500 — all of it becomes "no suggestions", and the caller uses the text alone.
 * An error body is deliberately not surfaced: it can echo the request, and the
 * request carries the document excerpt.
 */
class OpenAiClient(
    private val settings: AiSettings,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
) : TagModel {

    @Volatile
    private var lastTrouble: ModelTrouble? = null

    override fun trouble(): ModelTrouble? = lastTrouble

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (settings.apiKey.isBlank()) return@withContext false
        runCatching {
            val request = HttpRequest.newBuilder(URI.create(endpoint("models")))
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer ${settings.apiKey}")
                .GET()
                .build()
            // 401 means "answer is there, key is not": still unavailable here,
            // but not a network problem — the message in the UI covers both.
            http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200
        }.getOrDefault(false)
    }

    override suspend fun suggest(prompt: String): List<String> = withContext(Dispatchers.IO) {
        lastTrouble = null
        if (settings.apiKey.isBlank()) {
            lastTrouble = ModelTrouble(ModelIssue.NO_KEY, model = settings.model, url = settings.url)
            return@withContext emptyList()
        }
        if (settings.model.isBlank()) {
            lastTrouble = ModelTrouble(ModelIssue.NO_MODEL, url = settings.url)
            return@withContext emptyList()
        }
        val body = buildString {
            append("{\"model\":\"").append(JsonField.escape(settings.model)).append("\",")
            append("\"messages\":[{\"role\":\"user\",\"content\":\"")
                .append(JsonField.escape(prompt)).append("\"}],")
            // Determinism matters more than creativity for a filing suggestion.
            append("\"temperature\":0,\"max_tokens\":160,\"stream\":false}")
        }
        val response = runCatching {
            val request = HttpRequest.newBuilder(URI.create(endpoint("chat/completions")))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${settings.apiKey}")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            http.send(request, HttpResponse.BodyHandlers.ofString())
        }.getOrNull()
        if (response == null) {
            lastTrouble = ModelTrouble(
                ModelIssue.UNREACHABLE,
                model = settings.model,
                url = settings.url
            )
            return@withContext emptyList()
        }
        if (response.statusCode() != 200) {
            lastTrouble = ModelTrouble(
                issue = issueFor(response.statusCode()),
                status = response.statusCode(),
                model = settings.model,
                url = settings.url
            )
            return@withContext emptyList()
        }
        val answer = JsonField.string(response.body(), "content")
        if (answer.isNullOrBlank()) {
            lastTrouble = ModelTrouble(
                ModelIssue.BAD_ANSWER,
                status = 200,
                model = settings.model,
                url = settings.url
            )
            return@withContext emptyList()
        }
        answer.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    private fun issueFor(status: Int): ModelIssue = when {
        status == 401 || status == 403 -> ModelIssue.UNAUTHORIZED
        status == 400 || status == 404 || status == 422 -> ModelIssue.MODEL_REFUSED
        else -> ModelIssue.SERVER_ERROR
    }

    /**
     * `models` or `chat/completions` below the configured base URL.
     *
     * The OpenAI dialect lives under `/v1`, and DeepSeek accepts both
     * `https://api.deepseek.com` and `.../v1` as the base. Writing the address
     * without the version — the obvious thing, and the one the docs allow — must
     * not turn every request into a 404, so a base with no path gets `/v1`.
     */
    private fun endpoint(path: String): String = "${base()}/$path"

    internal fun base(): String {
        val url = settings.url.trim().trimEnd('/')
        val uriPath = runCatching { URI.create(url).path }.getOrNull().orEmpty()
        return if (uriPath.isEmpty() || uriPath == "/") "$url/v1" else url
    }
}

/**
 * Picks the client for the configured provider.
 *
 * The local client is returned even when no model is named, because Ollama
 * lists its own models and picking the first one is a feature. A remote client
 * without a key would only produce 401s on every click, so it is not created
 * at all and the app goes straight to the text-only suggestions.
 */
object AiModels {
    fun create(settings: AiSettings): TagModel? = when (settings.provider) {
        AiProvider.OLLAMA -> OllamaClient(settings)
        AiProvider.OPENAI ->
            if (settings.apiKey.isBlank() || settings.model.isBlank()) null else OpenAiClient(settings)
    }
}
