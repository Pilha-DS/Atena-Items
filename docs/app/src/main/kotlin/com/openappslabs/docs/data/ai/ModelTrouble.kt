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

/**
 * Why a suggestion did not come from the model.
 *
 * The old flow answered "the model is offline" for every failure, which is
 * useless when the real cause is a wrong model name or an address missing
 * `/v1`: the user cannot fix what the app does not name. Each value here has a
 * different fix, and the dialog turns it into a sentence.
 */
enum class ModelIssue {
    /** A remote service without a key: no client is even created. */
    NO_KEY,

    /** Connection refused, DNS, timeout: nothing listening at [ModelTrouble.url]. */
    UNREACHABLE,

    /** The service answered 401/403: the key is missing or wrong. */
    UNAUTHORIZED,

    /** 400/404/422: the service does not know the model that was asked for. */
    MODEL_REFUSED,

    /** 429/5xx: the service is up but did not serve the request. */
    SERVER_ERROR,

    /** The server answered, but has no model installed to use. */
    NO_MODEL,

    /** 200 with nothing usable in it: an empty or non-text answer. */
    BAD_ANSWER
}

/**
 * A model failure with the little detail that makes it actionable.
 *
 * [model] and [url] are what the user configured; they are not secrets. The key
 * is never here — not the value, not a prefix.
 */
data class ModelTrouble(
    val issue: ModelIssue,
    /** HTTP status when there was one; 0 otherwise. */
    val status: Int = 0,
    val model: String = "",
    val url: String = ""
)
