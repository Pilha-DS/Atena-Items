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
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Where the model runs: on this machine, or on a service that speaks the
 * OpenAI chat-completions dialect (DeepSeek is one).
 *
 * The distinction is not cosmetic. [OLLAMA] keeps the document text on the
 * machine; [OPENAI] sends the excerpt over the network. That is why the
 * provider is chosen in the config file and never guessed from a stray URL
 * alone beyond what is documented below.
 */
enum class AiProvider { OLLAMA, OPENAI }

/**
 * What to do with the `key=` line when the dialog saves the settings.
 *
 * Explicit instead of "the key is whatever is in [AiSettings]" because the two
 * sources have different trust: a key that came from the environment must never
 * be written to disk by accident, while a key the user just typed is exactly
 * what has to be written ([Set]).
 */
sealed interface KeyChange {
    /** Leave the file's `key=` line exactly as it is. */
    data object Keep : KeyChange

    /** Write this key; the one already in the file is replaced. */
    data class Set(val value: String) : KeyChange

    /** Delete the `key=` line. */
    data object Remove : KeyChange
}

/**
 * Where the model lives, if the user has one.
 *
 * Lines in `~/.config/docs/ai`, all optional:
 *
 * ```
 * provider=ollama
 * url=http://127.0.0.1:11434
 * model=qwen2.5:7b
 * ```
 *
 * and, for a remote service:
 *
 * ```
 * provider=deepseek
 * model=deepseek-flash
 * key=SUA_CHAVE
 * ```
 *
 * The default is the Ollama address on localhost — nothing leaves the machine.
 * Without a model the app still suggests tags, using the text alone (see
 * [HeuristicTagger]); the model only makes the suggestion smarter.
 *
 * [apiKey] is never printed: [toString] redacts it, because a data class that
 * leaks a token through a log line or a crash report is a real accident
 * waiting to happen.
 *
 * Invariant, deliberately: **the key is write-only in the window.** The field in
 * [com.openappslabs.docs.ui.AiSettingsDialog] is masked, the key can be typed,
 * replaced or removed, and it is written straight to this file with `0600` — but
 * it is never shown back: the dialog can only say whether a key is configured.
 * A key rendered on screen goes into a screenshot, a screen share and a crash
 * dump far more easily than a line in a file only its owner can read, and there
 * is no case where showing it again helps more than re-pasting it.
 */
data class AiSettings(
    val provider: AiProvider = AiProvider.OLLAMA,
    val url: String = DEFAULT_URL,
    val model: String = "",
    val apiKey: String = "",
    /**
     * True when the key came from a config file that other users can read.
     * The screen warns once; the fix is `chmod 600`.
     */
    val keyFileExposed: Boolean = false
) {
    /** A model is named, and a remote provider also has a key. */
    val configured: Boolean
        get() = model.isNotBlank() && when (provider) {
            AiProvider.OLLAMA -> true
            AiProvider.OPENAI -> apiKey.isNotBlank()
        }

    override fun toString(): String =
        "AiSettings(provider=$provider, url=$url, model=$model, " +
            "apiKey=${if (apiKey.isEmpty()) "none" else "redacted"})"

    /**
     * The same settings under another provider, with that provider's defaults.
     *
     * Model names and base URLs do not travel between Ollama and DeepSeek:
     * keeping `qwen2.5:7b` (or a LAN Ollama address) after picking "remote"
     * made Test/Save look broken even though the radio had moved. Custom
     * hosts are retyped when the user comes back to local — cheaper than a
     * half-switched config.
     */
    fun withProvider(next: AiProvider): AiSettings {
        if (next == provider) return this
        return copy(
            provider = next,
            url = defaultUrl(next),
            model = defaultModel(next)
        )
    }

    /** For testing a draft with the key the app already has, without showing it. */
    fun withKeyFrom(other: AiSettings): AiSettings =
        copy(apiKey = other.apiKey, keyFileExposed = other.keyFileExposed)

    companion object {
        const val DEFAULT_URL = "http://127.0.0.1:11434"
        const val DEEPSEEK_URL = "https://api.deepseek.com/v1"
        /** Current Fast API model; `deepseek-chat` / `deepseek-reasoner` retired mid-2026. */
        const val DEEPSEEK_MODEL = "deepseek-flash"

        /** Names DeepSeek accepts today (Chat Completions enum). */
        val DEEPSEEK_MODEL_SUGGESTIONS = listOf(
            DEEPSEEK_MODEL,
            "deepseek-v4-pro"
        )

        /** Old API ids remapped so a saved `deepseek-chat` still works. */
        private val DEEPSEEK_RETIRED_MODELS = mapOf(
            "deepseek-chat" to DEEPSEEK_MODEL,
            "deepseek-reasoner" to DEEPSEEK_MODEL,
            "deepseek-v4-flash" to DEEPSEEK_MODEL,
            "deepseek-v4-flash-vision-exp" to DEEPSEEK_MODEL
        )

        /**
         * Suggested Ollama size for tag/folder suggestions.
         *
         * 3B is fast but mixes close Portuguese senses (`nota` grade vs invoice).
         * 7B is the smallest size that usually keeps those apart on this app's
         * prompt; `qwen2.5` follows short JSON-ish answers well in pt-BR.
         */
        const val OLLAMA_RECOMMENDED = "qwen2.5:7b"

        /** Quick picks in the AI dialog (Ollama). First is the recommended one. */
        val OLLAMA_MODEL_SUGGESTIONS = listOf(
            OLLAMA_RECOMMENDED,
            "llama3.1:8b",
            "qwen2.5:3b"
        )

        fun defaultUrl(provider: AiProvider): String = when (provider) {
            AiProvider.OLLAMA -> DEFAULT_URL
            AiProvider.OPENAI -> DEEPSEEK_URL
        }

        fun defaultModel(provider: AiProvider): String = when (provider) {
            AiProvider.OLLAMA -> OLLAMA_RECOMMENDED
            AiProvider.OPENAI -> DEEPSEEK_MODEL
        }

        /**
         * Writes the settings the dialog can change — provider, model, address —
         * and optionally the key line.
         *
         * The default ([KeyChange.Keep]) carries the `key=` line over **verbatim
         * from the file**, so a key that came from `DEEPSEEK_API_KEY` never
         * lands on disk just because the user changed the model. Only an explicit
         * [KeyChange.Set] writes a key, and it is the one the user just typed.
         *
         * Comments in the file are regenerated, which is why the written header
         * says so. The file is created `0600` and kept `0600`: it carries a
         * secret from the moment the key is written.
         */
        fun save(
            file: Path,
            draft: AiSettings,
            key: KeyChange = KeyChange.Keep
        ): Boolean = runCatching {
            Files.createDirectories(file.parent)
            val keyLine = resolveKeyLine(file, key)
            val text = buildString {
                appendLine("# Atena Docs — IA. Este arquivo é escrito pela janela do app;")
                appendLine("# comentários são refeitos. A chave pode ser digitada em")
                appendLine("# ⋮ → Inteligência artificial…, ou mantida à mão na linha key= abaixo.")
                appendLine("provider=" + when (draft.provider) {
                    AiProvider.OLLAMA -> "ollama"
                    AiProvider.OPENAI -> "deepseek"
                })
                appendLine("model=" + draft.model.trim())
                appendLine("url=" + draft.url.trim().trimEnd('/'))
                if (keyLine != null) appendLine(keyLine)
            }
            if (!Files.exists(file)) {
                Files.createFile(
                    file,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
                )
            }
            Files.writeString(file, text)
            runCatching {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
            }
            true
        }.getOrDefault(false)

        /**
         * The `key=` line to write, from what the user asked for.
         *
         * A [KeyChange.Set] with a blank or control-character value does **not**
         * delete the key that is there: a paste that lost its content must cost
         * nothing. Deleting is [KeyChange.Remove], requested explicitly.
         */
        private fun resolveKeyLine(file: Path, key: KeyChange): String? = when (key) {
            KeyChange.Remove -> null
            KeyChange.Keep -> runCatching { existingKeyLine(file) }.getOrNull()
            is KeyChange.Set -> key.value.trim()
                .takeIf { it.isNotEmpty() && it.none { char -> char.isISOControl() } }
                ?.let { "key=$it" }
                ?: runCatching { existingKeyLine(file) }.getOrNull()
        }


        /** The `key=` line exactly as the user wrote it, or null. */
        private fun existingKeyLine(file: Path): String? {
            if (!Files.isRegularFile(file)) return null
            return Files.readAllLines(file)
                .map { it.trim() }
                .firstOrNull { line ->
                    !line.startsWith("#") && line.substringBefore('=').trim().lowercase() == "key" &&
                        line.substringAfter('=', "").isNotBlank()
                }
        }

        /** Reads the file; a broken or missing one falls back to the defaults. */
        fun read(file: Path, env: Map<String, String> = System.getenv()): AiSettings {
            val values = runCatching {
                if (!Files.isRegularFile(file)) return@runCatching emptyMap()
                Files.readAllLines(file)
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
                    .associate { line ->
                        line.substringBefore('=').trim().lowercase() to
                            line.substringAfter('=').trim().removeSurrounding("\"")
                    }
            }.getOrDefault(emptyMap())

            val rawUrl = values["url"]?.takeIf { it.startsWith("http") }
                ?: env["DOCS_AI_URL"]?.takeIf { it.startsWith("http") }
            val provider = provider(values["provider"] ?: env["DOCS_AI_PROVIDER"], rawUrl)

            val keyFromFile = values["key"]?.takeIf { it.isNotBlank() }
            val envKey = env["DOCS_AI_KEY"]?.takeIf { it.isNotBlank() }
                ?: if (provider == AiProvider.OPENAI) {
                    // The variable DeepSeek's own tools use, so an already
                    // exported key works without a second file.
                    env["DEEPSEEK_API_KEY"]?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            val key = keyFromFile ?: envKey ?: ""

            val url = rawUrl
                ?: if (provider == AiProvider.OPENAI) DEEPSEEK_URL else DEFAULT_URL
            val rawModel = values["model"]?.takeIf { it.isNotBlank() }
                ?: env["DOCS_AI_MODEL"]?.takeIf { it.isNotBlank() }
                ?: if (provider == AiProvider.OPENAI) DEEPSEEK_MODEL else ""
            val model = if (provider == AiProvider.OPENAI) {
                DEEPSEEK_RETIRED_MODELS[rawModel.lowercase()] ?: rawModel
            } else {
                rawModel
            }

            return AiSettings(
                provider = provider,
                url = url.trimEnd('/'),
                model = model,
                apiKey = key,
                keyFileExposed = keyFromFile != null && isReadableByOthers(file)
            )
        }

        /**
         * `provider=` wins; without it, a public host means a remote service.
         *
         * Nobody points the app at `api.deepseek.com` by accident, and asking
         * for a public address *is* the statement that the text may travel.
         * A private address is different: an Ollama in another machine of the
         * house (`192.168.0.10`, `ollama.local`) is still local data, so it
         * stays on the Ollama client — the user sets `provider=deepseek` when
         * they really mean a service. Everything else stays local too.
         */
        private fun provider(name: String?, url: String?): AiProvider {
            val explicit = name?.trim()?.lowercase()
            return when {
                explicit.isNullOrEmpty() -> if (isPublicHost(url)) AiProvider.OPENAI else AiProvider.OLLAMA
                explicit == "deepseek" || explicit == "openai" || explicit == "chat" -> AiProvider.OPENAI
                else -> AiProvider.OLLAMA
            }
        }

        private fun isPublicHost(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val host = runCatching { java.net.URI.create(url).host }.getOrNull()
                ?.lowercase()
                ?: return false
            if (host in LOCAL_HOSTS) return false
            // A single label is a machine name (`ollama`, `servidor`).
            if (!host.contains('.')) return false
            if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home")) return false
            return !PRIVATE_IPV4.matches(host)
        }

        private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "::1", "0.0.0.0", "[::1]")

        /** RFC 1918 and link-local: a home network, not a service on the internet. */
        private val PRIVATE_IPV4 = Regex(
            """^(10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}|169\.254\.\d{1,3}\.\d{1,3})$"""
        )

        /** Group or others holding a read bit on the file that carries the key. */
        private fun isReadableByOthers(file: Path): Boolean = runCatching {
            val permissions = Files.getPosixFilePermissions(file)
            permissions.any {
                it in setOf(
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.OTHERS_WRITE
                )
            }
        }.getOrDefault(false)
    }
}
