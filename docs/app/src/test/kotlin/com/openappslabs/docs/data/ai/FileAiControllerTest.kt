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
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The dialog path end to end, with a fake service: what `save` writes, what the
 * live slot then answers with, and the one thing that must never appear — the
 * key.
 */
class FileAiControllerTest {

    private lateinit var server: HttpServer
    private var authSeen: String? = null
    private var chatStatus = 200

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/models") { exchange ->
            authSeen = exchange.requestHeaders.getFirst("Authorization")
            val bytes = """{"data":[]}""".toByteArray()
            exchange.sendResponseHeaders(if (authSeen.isNullOrBlank()) 401 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        // The dialog's "Testar" now asks for a real answer, not just the list.
        server.createContext("/v1/chat/completions") { exchange ->
            authSeen = exchange.requestHeaders.getFirst("Authorization")
            exchange.requestBody.readBytes()
            val bytes =
                """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""".toByteArray()
            exchange.sendResponseHeaders(chatStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun url() = "http://127.0.0.1:${server.address.port}/v1"

    @Test
    fun `save writes the file, applies the client and keeps the key`() = runBlocking {
        val file = createTempDirectory("docs-ai").resolve("ai")
        Files.writeString(file, "provider=ollama\nmodel=x\nkey=sk-do-arquivo\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))

        val slot = ModelSlot(AiSettings.read(file, env = emptyMap()))
        val controller = FileAiController(file, slot)
        assertTrue(slot.model is OllamaClient)

        val saved = controller.save(
            AiSettings(provider = AiProvider.OPENAI, url = url(), model = "deepseek-flash")
        )

        assertTrue(saved)
        // Applied without a restart…
        assertTrue(slot.model is OpenAiClient)
        assertEquals("deepseek-flash", slot.settings.model)
        // …and the key came from the file, untouched.
        assertEquals("sk-do-arquivo", slot.settings.apiKey)
        assertTrue(Files.readString(file).contains("key=sk-do-arquivo"))
        assertFalse(AiSettings.read(file, env = emptyMap()).keyFileExposed)
    }

    @Test
    fun `test uses the stored key and never asks for one`() = runBlocking {
        val file = createTempDirectory("docs-ai").resolve("ai")
        Files.writeString(file, "provider=deepseek\nmodel=deepseek-flash\nkey=sk-1\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        val controller = FileAiController(file, ModelSlot(AiSettings.read(file, env = emptyMap())))

        val ok = controller.test(AiSettings(provider = AiProvider.OPENAI, url = url(), model = "deepseek-flash"))

        assertTrue(ok)
        assertEquals("Bearer sk-1", authSeen)
    }

    @Test
    fun `without a key the remote test fails and nothing is sent`() = runBlocking {
        val file = createTempDirectory("docs-ai").resolve("ai")
        val controller = FileAiController(file, ModelSlot(AiSettings.read(file, env = emptyMap())))

        val ok = controller.test(AiSettings(provider = AiProvider.OPENAI, url = url(), model = "deepseek-flash"))

        assertFalse(ok)
        assertEquals(null, authSeen)
        assertFalse(Files.exists(file))
    }

    @Test
    fun `a model the service refuses fails the test instead of passing on the key`() = runBlocking {
        // Unknown model id: the key is fine, the answer is a 400. The old test
        // only asked for the model list and said "ok".
        chatStatus = 400
        val file = createTempDirectory("docs-ai").resolve("ai")
        Files.writeString(file, "provider=deepseek\nmodel=deepseek-nexiste\nkey=sk-1\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        val controller = FileAiController(file, ModelSlot(AiSettings.read(file, env = emptyMap())))

        val ok = controller.test(
            AiSettings(provider = AiProvider.OPENAI, url = url(), model = "deepseek-nexiste")
        )

        assertFalse(ok)
    }

    @Test
    fun `a key typed in the window reaches the live client and the file`() = runBlocking {
        val file = createTempDirectory("docs-ai").resolve("ai")
        val slot = ModelSlot(AiSettings())
        val controller = FileAiController(file, slot)

        val saved = controller.save(
            AiSettings(provider = AiProvider.OPENAI, url = url(), model = "deepseek-flash"),
            KeyChange.Set("sk-digitada")
        )

        assertTrue(saved)
        // The slot re-read the file, so the next suggestion already has the key…
        assertEquals("sk-digitada", slot.settings.apiKey)
        assertTrue(Files.readString(file).contains("key=sk-digitada"))
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(file)
        )
        // …and the remote test right after the save authenticates with it.
        assertTrue(controller.test(AiSettings(provider = AiProvider.OPENAI, url = url(), model = "deepseek-flash")))
        assertEquals("Bearer sk-digitada", authSeen)
    }

    @Test
    fun `testing a key typed in the dialog uses that key before saving it`() = runBlocking {
        val file = createTempDirectory("docs-ai").resolve("ai")
        val controller = FileAiController(file, ModelSlot(AiSettings()))

        val ok = controller.test(
            AiSettings(
                provider = AiProvider.OPENAI,
                url = url(),
                model = "deepseek-flash",
                apiKey = "sk-colada"
            )
        )

        assertTrue(ok)
        assertEquals("Bearer sk-colada", authSeen)
        // Testing changes nothing on disk.
        assertFalse(Files.exists(file))
    }

    @Test
    fun `saving without a key in the dialog keeps the browser file key, not an env one`() = runBlocking {
        val file = createTempDirectory("docs-ai").resolve("ai")
        Files.writeString(file, "provider=deepseek\nmodel=deepseek-flash\nkey=sk-do-arquivo\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        val controller = FileAiController(file, ModelSlot(AiSettings.read(file, env = emptyMap())))

        // The dialog holds the key in memory only to show "configurada"; saving
        // with an untouched field must not duplicate it in the candidate.
        val ok = controller.save(
            AiSettings(provider = AiProvider.OPENAI, url = url(), model = "deepseek-flash", apiKey = "sk-do-arquivo"),
            KeyChange.Keep
        )

        assertTrue(ok)
        assertEquals("sk-do-arquivo", AiSettings.read(file, env = emptyMap()).apiKey)
        assertEquals(1, Files.readString(file).lines().count { it.startsWith("key=") })
    }

    @Test
    fun `a failed save leaves the settings in force untouched`() {
        val slot = ModelSlot(AiSettings())
        val before = slot.model
        // A directory where the file should be: the write cannot succeed.
        val blocked = createTempDirectory("docs-ai")
        val controller = FileAiController(blocked, slot)

        val saved = controller.save(AiSettings(provider = AiProvider.OPENAI, model = "deepseek-flash"))

        assertFalse(saved)
        assertSame(before, slot.model)
        assertEquals(AiProvider.OLLAMA, slot.settings.provider)
    }

    @Test
    fun `switching back to local swaps the client again`() = runBlocking {
        val file = createTempDirectory("docs-ai").resolve("ai")
        val slot = ModelSlot(AiSettings(provider = AiProvider.OPENAI, url = url(), model = "deepseek-flash", apiKey = "sk-1"))
        val controller = FileAiController(file, slot)
        val remote = slot.model
        assertTrue(remote is OpenAiClient)

        assertTrue(controller.save(AiSettings(provider = AiProvider.OLLAMA, model = "llama3.1:8b")))

        assertTrue(slot.model is OllamaClient)
        assertNotSame(remote, slot.model)
        assertEquals(AiProvider.OLLAMA, slot.settings.provider)
    }
}
