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
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A real HTTP round trip against a fake Ollama, so the request body and the
 * parsing are exercised without a model on the machine.
 */
class OllamaClientTest {

    private lateinit var server: HttpServer
    private var lastBody = ""
    private var generateAnswer = """{"response":"academico/extensao\npessoal/financas","done":true}"""
    private var generateStatus = 200

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/tags") { exchange ->
            val body = """{"models":[{"name":"llama3.1:8b"}]}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/generate") { exchange ->
            lastBody = exchange.requestBody.readBytes().decodeToString()
            val bytes = generateAnswer.toByteArray()
            exchange.sendResponseHeaders(generateStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun settings() = AiSettings(
        url = "http://127.0.0.1:${server.address.port}",
        model = ""
    )

    @Test
    fun `a running server is available`() = runBlocking {
        assertTrue(OllamaClient(settings()).isAvailable())
    }

    @Test
    fun `a closed port is not available instead of an exception`() = runBlocking {
        val client = OllamaClient(AiSettings(url = "http://127.0.0.1:1"))
        assertFalse(client.isAvailable())
    }

    @Test
    fun `a closed port is reported as unreachable`() = runBlocking {
        val client = OllamaClient(AiSettings(url = "http://127.0.0.1:1"))

        assertTrue(client.suggest("x").isEmpty())
        assertEquals(ModelIssue.UNREACHABLE, client.trouble()?.issue)
    }

    @Test
    fun `the answer comes back one suggestion per line`() = runBlocking {
        val lines = OllamaClient(settings()).suggest("prompt")

        assertEquals(listOf("academico/extensao", "pessoal/financas"), lines)
    }

    @Test
    fun `the request names the model, turns streaming off and escapes the prompt`() = runBlocking {
        OllamaClient(settings()).suggest("linha \"com aspas\"\ne acento: ção")

        assertTrue("\"model\":\"llama3.1:8b\"" in lastBody)
        assertTrue("\"stream\":false" in lastBody)
        assertTrue("linha \\\"com aspas\\\"\\ne acento: ção" in lastBody)
    }

    @Test
    fun `a model named in the settings is used as is`() = runBlocking {
        OllamaClient(AiSettings(url = settings().url, model = "qwen2.5:3b")).suggest("x")

        assertTrue("\"model\":\"qwen2.5:3b\"" in lastBody)
    }

    @Test
    fun `an error from the server means no suggestions`() = runBlocking {
        generateStatus = 500
        assertTrue(OllamaClient(settings()).suggest("prompt").isEmpty())
    }

    @Test
    fun `a response without the field means no suggestions`() = runBlocking {
        generateAnswer = """{"done":true}"""
        assertTrue(OllamaClient(settings()).suggest("prompt").isEmpty())
    }

    @Test
    fun `settings come from the config file with localhost as default`() {
        val dir = createTempDirectory("docs-ai")
        val file = dir.resolve("ai")
        assertEquals(AiSettings.DEFAULT_URL, AiSettings.read(file, env = emptyMap()).url)
        assertFalse(AiSettings.read(file, env = emptyMap()).configured)

        Files.writeString(file, "# comentário\nmodel=llama3.1:8b\nurl=http://127.0.0.1:9999/\n")

        val read = AiSettings.read(file, env = emptyMap())
        assertEquals("http://127.0.0.1:9999", read.url)
        assertEquals("llama3.1:8b", read.model)
        assertTrue(read.configured)
    }

    @Test
    fun `the environment can name the model without a config file`() {
        val file = createTempDirectory("docs-ai").resolve("ai")

        val read = AiSettings.read(file, env = mapOf("DOCS_AI_MODEL" to "gemma2:2b"))

        assertEquals("gemma2:2b", read.model)
        assertEquals(AiSettings.DEFAULT_URL, read.url)
    }

    @Test
    fun `a broken config file falls back to the defaults`() {
        val dir = createTempDirectory("docs-ai")
        val file = dir.resolve("ai")
        Files.writeString(file, "isto não é configuração\nurl=ftp://x\n")

        val read = AiSettings.read(file, env = emptyMap())

        assertEquals(AiSettings.DEFAULT_URL, read.url)
        assertFalse(read.configured)
    }
}
