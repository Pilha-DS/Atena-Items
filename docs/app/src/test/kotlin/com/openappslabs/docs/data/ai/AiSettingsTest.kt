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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiSettingsTest {

    private fun configFile(text: String? = null): Path {
        val file = createTempDirectory("docs-ai").resolve("ai")
        if (text != null) Files.writeString(file, text)
        return file
    }

    private fun privateConfigFile(text: String): Path {
        val file = configFile(text)
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        return file
    }

    @Test
    fun `nothing configured means the local model with its default address`() {
        val settings = AiSettings.read(configFile(), env = emptyMap())

        assertEquals(AiProvider.OLLAMA, settings.provider)
        assertEquals(AiSettings.DEFAULT_URL, settings.url)
        assertEquals("", settings.model)
        assertEquals("", settings.apiKey)
        assertFalse(settings.keyFileExposed)
    }

    @Test
    fun `deepseek brings its own address and model`() {
        val settings = AiSettings.read(privateConfigFile("provider=deepseek\nkey=sk-1\n"), env = emptyMap())

        assertEquals(AiProvider.OPENAI, settings.provider)
        assertEquals(AiSettings.DEEPSEEK_URL, settings.url)
        assertEquals(AiSettings.DEEPSEEK_MODEL, settings.model)
        assertEquals("sk-1", settings.apiKey)
        assertTrue(settings.configured)
    }

    @Test
    fun `the openai alias means the same dialect`() {
        assertEquals(
            AiProvider.OPENAI,
            AiSettings.read(privateConfigFile("provider=openai\nkey=k\n"), env = emptyMap()).provider
        )
    }

    @Test
    fun `a public address without a provider is treated as remote`() {
        val settings = AiSettings.read(
            privateConfigFile("url=https://api.deepseek.com/v1\nkey=k\n"),
            env = emptyMap()
        )

        assertEquals(AiProvider.OPENAI, settings.provider)
    }

    @Test
    fun `an Ollama in another machine of the house stays local`() {
        val lan = AiSettings.read(
            configFile("url=http://192.168.0.10:11434\nmodel=x\n"),
            env = emptyMap()
        )
        val named = AiSettings.read(
            configFile("url=http://ollama.local:11434\nmodel=x\n"),
            env = emptyMap()
        )

        assertEquals(AiProvider.OLLAMA, lan.provider)
        assertEquals(AiProvider.OLLAMA, named.provider)
    }

    @Test
    fun `an explicit local provider wins over a public url`() {
        val settings = AiSettings.read(
            configFile("provider=ollama\nurl=https://exemplo.com/api\nmodel=x\n"),
            env = emptyMap()
        )

        assertEquals(AiProvider.OLLAMA, settings.provider)
    }

    @Test
    fun `an unknown provider stays local`() {
        assertEquals(
            AiProvider.OLLAMA,
            AiSettings.read(configFile("provider=qualquer\n"), env = emptyMap()).provider
        )
    }

    @Test
    fun `a remote provider without a key is not configured`() {
        val settings = AiSettings.read(configFile("provider=deepseek\n"), env = emptyMap())

        assertEquals(AiSettings.DEEPSEEK_MODEL, settings.model)
        assertFalse(settings.configured)
    }

    @Test
    fun `the key can come from the environment, including the deepseek variable`() {
        assertEquals(
            "sk-env",
            AiSettings.read(
                configFile("provider=deepseek\n"),
                env = mapOf("DOCS_AI_KEY" to "sk-env")
            ).apiKey
        )
        assertEquals(
            "sk-deepseek",
            AiSettings.read(
                configFile("provider=deepseek\n"),
                env = mapOf("DEEPSEEK_API_KEY" to "sk-deepseek")
            ).apiKey
        )
    }

    @Test
    fun `a deepseek variable is ignored by the local provider`() {
        val settings = AiSettings.read(
            configFile("provider=ollama\n"),
            env = mapOf("DEEPSEEK_API_KEY" to "sk-deepseek")
        )

        assertEquals("", settings.apiKey)
    }

    @Test
    fun `a key in a world readable file is flagged`() {
        val exposed = AiSettings.read(configFile("provider=deepseek\nkey=sk-1\n"), env = emptyMap())
        val private = AiSettings.read(privateConfigFile("provider=deepseek\nkey=sk-1\n"), env = emptyMap())

        assertTrue(exposed.keyFileExposed)
        assertFalse(private.keyFileExposed)
    }

    @Test
    fun `a key from the environment is never reported as a file problem`() {
        val settings = AiSettings.read(
            configFile("provider=deepseek\n"),
            env = mapOf("DOCS_AI_KEY" to "sk-1")
        )

        assertFalse(settings.keyFileExposed)
    }

    @Test
    fun `the settings never print the key`() {
        val settings = AiSettings(
            provider = AiProvider.OPENAI,
            model = "deepseek-flash",
            apiKey = "sk-segredo"
        )

        val text = settings.toString()

        assertFalse(text.contains("sk-segredo"))
        assertTrue(text.contains("redacted"))
        assertTrue(AiSettings().toString().contains("none"))
    }

    @Test
    fun `settings never write anything back to the file`() {
        val dir = createTempDirectory("docs-ai")
        val missing = dir.resolve("ai")

        AiSettings.read(missing, env = emptyMap())

        // No file is created just by opening the app.
        assertFalse(Files.exists(missing))

        val file = dir.resolve("ai")
        Files.writeString(file, "provider=deepseek\nkey=sk-1\n")
        val before = Files.readAllBytes(file)

        AiSettings.read(file, env = emptyMap())

        assertTrue(before.contentEquals(Files.readAllBytes(file)))
    }

    @Test
    fun `quotes around a value are tolerated`() {
        val settings = AiSettings.read(
            privateConfigFile("provider=\"deepseek\"\nkey=\"sk-1\"\n"),
            env = emptyMap()
        )

        assertEquals(AiProvider.OPENAI, settings.provider)
        assertEquals("sk-1", settings.apiKey)
    }

    @Test
    fun `save writes provider, model and url`() {
        val file = createTempDirectory("docs-ai").resolve("ai")

        val saved = AiSettings.save(
            file,
            AiSettings(provider = AiProvider.OPENAI, url = "https://x/v1", model = "deepseek-flash")
        )

        assertTrue(saved)
        val read = AiSettings.read(file, env = emptyMap())
        assertEquals(AiProvider.OPENAI, read.provider)
        assertEquals("https://x/v1", read.url)
        assertEquals("deepseek-flash", read.model)
    }

    @Test
    fun `save keeps the key line verbatim and never writes a key from memory`() {
        val file = createTempDirectory("docs-ai").resolve("ai")
        Files.writeString(file, "provider=ollama\nkey=sk-do-arquivo\n")

        AiSettings.save(file, AiSettings(provider = AiProvider.OPENAI, model = "deepseek-flash"))

        val text = Files.readString(file)
        assertTrue(text.contains("key=sk-do-arquivo"))
        // A key that only exists in memory (from the environment) is not written.
        assertFalse(text.contains("sk-do-ambiente"))
        assertEquals("sk-do-arquivo", AiSettings.read(file, env = emptyMap()).apiKey)
    }

    @Test
    fun `save does not invent a key line when the file has none`() {
        val file = createTempDirectory("docs-ai").resolve("ai")

        AiSettings.save(
            file,
            AiSettings(provider = AiProvider.OPENAI, model = "deepseek-flash", apiKey = "sk-em-memoria")
        )

        val text = Files.readString(file)
        assertFalse(text.contains("sk-em-memoria"))
        assertFalse(text.lines().any { it.startsWith("key=") })
    }

    @Test
    fun `save writes the key the user typed in the window`() {
        val file = createTempDirectory("docs-ai").resolve("ai")

        AiSettings.save(
            file,
            AiSettings(provider = AiProvider.OPENAI, model = "deepseek-flash"),
            KeyChange.Set("  sk-digitada  ")
        )

        val text = Files.readString(file)
        assertTrue(text.contains("key=sk-digitada"))
        assertEquals("sk-digitada", AiSettings.read(file, env = emptyMap()).apiKey)
        // A secret just landed on disk: the file must not be world-readable.
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(file)
        )
    }

    @Test
    fun `a typed key replaces the one in the file and only one key line survives`() {
        val file = createTempDirectory("docs-ai").resolve("ai")
        Files.writeString(file, "provider=deepseek\nkey=sk-antiga\n")

        AiSettings.save(file, AiSettings(provider = AiProvider.OPENAI), KeyChange.Set("sk-nova"))

        val text = Files.readString(file)
        assertFalse(text.contains("sk-antiga"))
        assertEquals(1, text.lines().count { it.startsWith("key=") })
        assertEquals("sk-nova", AiSettings.read(file, env = emptyMap()).apiKey)
    }

    @Test
    fun `an empty typed key keeps the key in the file instead of deleting it`() {
        val file = createTempDirectory("docs-ai").resolve("ai")
        Files.writeString(file, "key=sk-antiga\n")

        AiSettings.save(file, AiSettings(provider = AiProvider.OPENAI), KeyChange.Set("   "))

        // A paste that lost its content must cost nothing: deleting is explicit.
        assertTrue(Files.readString(file).contains("key=sk-antiga"))
    }

    @Test
    fun `a key with a line break is refused and the file keeps the good one`() {
        val file = createTempDirectory("docs-ai").resolve("ai")
        Files.writeString(file, "key=sk-boa\n")

        AiSettings.save(file, AiSettings(provider = AiProvider.OPENAI), KeyChange.Set("sk-nova\nprovider=ollama"))

        val text = Files.readString(file)
        assertTrue(text.contains("key=sk-boa"))
        // The injected line did not become a second setting.
        assertFalse(text.contains("provider=ollama"))
    }

    @Test
    fun `removing the key deletes only the key line`() {
        val file = createTempDirectory("docs-ai").resolve("ai")
        Files.writeString(file, "provider=deepseek\nkey=sk-antiga\n")

        AiSettings.save(file, AiSettings(provider = AiProvider.OPENAI, model = "deepseek-flash"), KeyChange.Remove)

        val text = Files.readString(file)
        assertFalse(text.lines().any { it.startsWith("key=") })
        assertEquals("deepseek-flash", AiSettings.read(file, env = emptyMap()).model)
        assertTrue(AiSettings.read(file, env = emptyMap()).apiKey.isEmpty())
    }

    @Test
    fun `the key never appears in the settings toString`() {
        val settings = AiSettings(provider = AiProvider.OPENAI, apiKey = "sk-segredo")

        assertFalse(settings.toString().contains("sk-segredo"))
        assertTrue(settings.toString().contains("redacted"))
    }

    @Test
    fun `save leaves the file private`() {
        val file = createTempDirectory("docs-ai").resolve("ai")
        Files.writeString(file, "key=sk-1\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))

        AiSettings.save(file, AiSettings(provider = AiProvider.OPENAI, model = "deepseek-flash"))

        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(file)
        )
    }

    @Test
    fun `switching the provider fills the defaults`() {
        val local = AiSettings()

        val remote = local.withProvider(AiProvider.OPENAI)
        assertEquals(AiSettings.DEEPSEEK_URL, remote.url)
        assertEquals(AiSettings.DEEPSEEK_MODEL, remote.model)

        val back = remote.withProvider(AiProvider.OLLAMA)
        assertEquals(AiSettings.DEFAULT_URL, back.url)
        assertEquals(AiSettings.OLLAMA_RECOMMENDED, back.model)
    }

    @Test
    fun `ollama suggestions lead with the recommended 7B`() {
        assertEquals("qwen2.5:7b", AiSettings.OLLAMA_RECOMMENDED)
        assertEquals(AiSettings.OLLAMA_RECOMMENDED, AiSettings.OLLAMA_MODEL_SUGGESTIONS.first())
        assertEquals(AiSettings.OLLAMA_RECOMMENDED, AiSettings.defaultModel(AiProvider.OLLAMA))
    }

    @Test
    fun `retired deepseek chat ids remap to flash`() {
        val settings = AiSettings.read(
            privateConfigFile("provider=deepseek\nmodel=deepseek-chat\nkey=sk-1\n"),
            env = emptyMap()
        )

        assertEquals("deepseek-flash", settings.model)
        assertEquals(AiSettings.DEEPSEEK_MODEL, settings.model)
        assertEquals("deepseek-flash", AiSettings.DEEPSEEK_MODEL_SUGGESTIONS.first())
    }

    @Test
    fun `switching providers resets model and url to the target defaults`() {
        val custom = AiSettings(
            provider = AiProvider.OLLAMA,
            url = "http://192.168.0.10:11434",
            model = "qwen2.5:7b"
        )

        val switched = custom.withProvider(AiProvider.OPENAI)

        assertEquals(AiProvider.OPENAI, switched.provider)
        assertEquals(AiSettings.DEEPSEEK_URL, switched.url)
        assertEquals(AiSettings.DEEPSEEK_MODEL, switched.model)

        val back = switched.withProvider(AiProvider.OLLAMA)
        assertEquals(AiSettings.DEFAULT_URL, back.url)
        assertEquals(AiSettings.OLLAMA_RECOMMENDED, back.model)
    }

    @Test
    fun `testing a draft borrows the key without exposing it`() {
        val stored = AiSettings(provider = AiProvider.OPENAI, apiKey = "sk-1")
        val draft = AiSettings(provider = AiProvider.OPENAI, model = "outro")

        val merged = draft.withKeyFrom(stored)

        assertEquals("sk-1", merged.apiKey)
        assertEquals("outro", merged.model)
        assertFalse(merged.toString().contains("sk-1"))
    }
}
