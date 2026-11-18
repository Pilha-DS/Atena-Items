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

import java.nio.file.Path

/**
 * What the "IA" dialog is allowed to change.
 *
 * The provider, the model, the address and the key are all editable there; the
 * key is the only one that travels as its own parameter ([KeyChange]) instead of
 * living inside the candidate, so "keep what the file has" and "write this new
 * value" can never be confused.
 *
 * An interface, not a class, so the controller can be driven in a test without
 * a file or a network, and so the screen never sees [FileAiController].
 */
interface AiController {
    /** The settings in force right now, key included (the dialog never shows it). */
    fun current(): AiSettings

    /**
     * Tries the candidate.
     *
     * A key typed in the dialog is tried as typed, before being saved; without
     * one, the stored key is used, so testing an address or a model does not
     * require pasting the key again.
     */
    suspend fun test(candidate: AiSettings): Boolean

    /** Persists the candidate and applies it to the clients. */
    fun save(candidate: AiSettings, key: KeyChange = KeyChange.Keep): Boolean
}

/**
 * Holds the live model client so a saved setting takes effect on the next
 * suggestion instead of on the next launch.
 *
 * `@Volatile` because the writers are the UI thread (save) and the readers are
 * the suggestion coroutines on `Dispatchers.IO`.
 */
class ModelSlot(settings: AiSettings) {
    @Volatile
    var settings: AiSettings = settings
        private set

    @Volatile
    var model: TagModel? = runCatching { AiModels.create(settings) }.getOrNull()
        private set

    fun apply(next: AiSettings) {
        settings = next
        model = runCatching { AiModels.create(next) }.getOrNull()
    }
}

/**
 * The real thing: a file on disk plus the live [ModelSlot].
 *
 * Reading the file again after writing is deliberate — it is the only way to be
 * sure the value in memory matches what the next launch will read, and it is
 * what carries the untouchable `key=` line back into [ModelSlot.settings].
 */
class FileAiController(
    private val file: Path,
    private val slot: ModelSlot
) : AiController {

    override fun current(): AiSettings = slot.settings

    override suspend fun test(candidate: AiSettings): Boolean {
        // A key typed in the dialog wins for the test; otherwise the stored one
        // is used, so re-testing an address does not need the key again.
        val merged = if (candidate.apiKey.isNotBlank()) {
            candidate
        } else {
            candidate.withKeyFrom(slot.settings)
        }
        val client = runCatching { AiModels.create(merged) }.getOrNull() ?: return false
        // A real completion, not the model list. The list only proves the key:
        // a model name the service does not know (a retired `deepseek-chat`, a
        // typo) passes it and then fails every suggestion — the
        // dialog said "ok" while the feature was dead.
        val answer = runCatching { client.suggest(PROBE) }.getOrDefault(emptyList())
        if (answer.isNotEmpty()) return true
        // Empty with no reported trouble is a live model with nothing to say;
        // anything else is a failure the dialog must show as such.
        return runCatching { client.trouble() }.getOrNull() == null
    }

    override fun save(candidate: AiSettings, key: KeyChange): Boolean {
        if (!AiSettings.save(file, candidate, key)) return false
        // Re-read instead of trusting the candidate: the file is the truth, and
        // the key that just got written must reach the client from there.
        slot.apply(AiSettings.read(file))
        return true
    }

    private companion object {
        /** Short on purpose: the test is about the path, not the answer. */
        const val PROBE = "Responda apenas com a palavra ok."
    }
}
