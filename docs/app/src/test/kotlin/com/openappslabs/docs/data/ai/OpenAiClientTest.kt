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

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The remote leg, against a fake server that speaks the same dialect as
 * DeepSeek: `/models` for the health check and `/chat/completions` for the
 * answer. No key ever reaches a real service from the test suite.
 */
class OpenAiClientTest {

    private lateinit var server: HttpServer
    private var lastBody = ""
    private var lastAuth: String? = null
    private var chatStatus = 200
    private var chatAnswer =
        """{"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"academico/extensao\npessoal"},"finish_reason":"stop"}],"usage":{}}"""

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/models") { exchange ->
            lastAuth = exchange.requestHeaders.getFirst("Authorization")
            val body = """{"object":"list","data":[{"id":"deepseek-flash"}]}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(if (lastAuth.isNullOrBlank()) 401 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/chat/completions") { exchange ->
            lastAuth = exchange.requestHeaders.getFirst("Authorization")
            lastBody = exchange.requestBody.readBytes().decodeToString()
            val bytes = chatAnswer.toByteArray()
            exchange.sendResponseHeaders(chatStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun settings(key: String = "sk-teste", model: String = "deepseek-flash") = AiSettings(
        provider = AiProvider.OPENAI,
        url = "http://127.0.0.1:${server.address.port}/v1",
        model = model,
        apiKey = key
    )

    @Test
    fun `a server that accepts the key is available`() = runBlocking {
        assertTrue(OpenAiClient(settings()).isAvailable())
        assertEquals("Bearer sk-teste", lastAuth)
    }

    @Test
    fun `a missing key never reaches the network`() = runBlocking {
        assertFalse(OpenAiClient(settings(key = "")).isAvailable())
        assertTrue(OpenAiClient(settings(key = "")).suggest("x").isEmpty())
        assertEquals("", lastBody)
    }

    @Test
    fun `the answer is taken from choices zero message content`() = runBlocking {
        val lines = OpenAiClient(settings()).suggest("prompt")

        assertEquals(listOf("academico/extensao", "pessoal"), lines)
    }

    @Test
    fun `the request is a chat completion with the model, the key and the prompt`() = runBlocking {
        OpenAiClient(settings()).suggest("linha \"com aspas\"\ne acento: ção")

        assertTrue("\"model\":\"deepseek-flash\"" in lastBody)
        assertTrue("\"role\":\"user\"" in lastBody)
        assertTrue("\"temperature\":0" in lastBody)
        assertTrue("\"stream\":false" in lastBody)
        assertTrue("linha \\\"com aspas\\\"\\ne acento: ção" in lastBody)
        assertEquals("Bearer sk-teste", lastAuth)
    }

    @Test
    fun `an error status means no suggestions`() = runBlocking {
        chatStatus = 401

        assertTrue(OpenAiClient(settings()).suggest("prompt").isEmpty())
    }

    @Test
    fun `a refused key is reported as unauthorized`() = runBlocking {
        chatStatus = 401
        val client = OpenAiClient(settings())

        client.suggest("prompt")

        assertEquals(ModelIssue.UNAUTHORIZED, client.trouble()?.issue)
        assertEquals(401, client.trouble()?.status)
    }

    @Test
    fun `a refused model is reported with its name and status`() = runBlocking {
        // A retired or typo'd id answers 400; the dialog has to name it instead
        // of "IA indisponível".
        chatStatus = 400
        val client = OpenAiClient(settings(model = "deepseek-nexiste"))

        assertTrue(client.suggest("prompt").isEmpty())
        assertEquals(ModelIssue.MODEL_REFUSED, client.trouble()?.issue)
        assertEquals(400, client.trouble()?.status)
        assertEquals("deepseek-nexiste", client.trouble()?.model)
    }

    @Test
    fun `a closed port is reported as unreachable`() = runBlocking {
        val client = OpenAiClient(
            AiSettings(provider = AiProvider.OPENAI, url = "http://127.0.0.1:1/v1", model = "x", apiKey = "k")
        )

        client.suggest("x")

        assertEquals(ModelIssue.UNREACHABLE, client.trouble()?.issue)
    }

    @Test
    fun `an address without the version still reaches v1`() = runBlocking {
        // `https://api.deepseek.com` is a documented base; without `/v1` the
        // request would be a 404 and the app would silently fall back.
        val bare = settings().copy(url = "http://127.0.0.1:${server.address.port}")

        assertEquals(
            listOf("academico/extensao", "pessoal"),
            OpenAiClient(bare).suggest("prompt")
        )
    }

    @Test
    fun `the base url only gains v1 when it has no path`() {
        fun base(url: String) = OpenAiClient(
            AiSettings(provider = AiProvider.OPENAI, url = url, model = "deepseek-flash", apiKey = "k")
        ).base()

        assertEquals("https://api.deepseek.com/v1", base("https://api.deepseek.com"))
        assertEquals("https://api.deepseek.com/v1", base("https://api.deepseek.com/"))
        assertEquals("https://api.deepseek.com/v1", base("https://api.deepseek.com/v1"))
        assertEquals("http://proxy.local/deepseek/v1", base("http://proxy.local/deepseek/v1"))
    }

    @Test
    fun `an answer without content means no suggestions`() = runBlocking {
        chatAnswer = """{"choices":[{"index":0,"message":{"role":"assistant"}}]}"""

        assertTrue(OpenAiClient(settings()).suggest("prompt").isEmpty())
    }

    @Test
    fun `a reasoning_content field is not mistaken for the answer`() = runBlocking {
        chatAnswer =
            """{"choices":[{"message":{"reasoning_content":"pensando","content":"academico"}}]}"""

        assertEquals(listOf("academico"), OpenAiClient(settings()).suggest("prompt"))
    }

    @Test
    fun `a closed port is unavailable instead of an exception`() = runBlocking {
        val client = OpenAiClient(
            AiSettings(provider = AiProvider.OPENAI, url = "http://127.0.0.1:1/v1", model = "x", apiKey = "k")
        )

        assertFalse(client.isAvailable())
        assertTrue(client.suggest("x").isEmpty())
    }
}

class AiModelsTest {

    private fun settings(provider: AiProvider, model: String = "", key: String = "") =
        AiSettings(provider = provider, model = model, apiKey = key)

    @Test
    fun `without a provider the local model is used`() {
        assertTrue(AiModels.create(settings(AiProvider.OLLAMA)) is OllamaClient)
    }

    @Test
    fun `a configured remote provider gets the chat completions client`() {
        assertTrue(
            AiModels.create(settings(AiProvider.OPENAI, "deepseek-flash", "sk-1")) is OpenAiClient
        )
    }

    @Test
    fun `a remote provider without a key or a model has no client at all`() {
        assertEquals(null, AiModels.create(settings(AiProvider.OPENAI, "deepseek-flash", "")))
        assertEquals(null, AiModels.create(settings(AiProvider.OPENAI, "", "sk-1")))
    }
}
