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

package com.openappslabs.jotter.utils

import java.net.URI

/**
 * Turns whatever was pasted into the one canonical form the inbox stores.
 *
 * Canonical means "two pastes of the same page are the same row": the scheme
 * and host are lowercased, a default port disappears and the path loses a bare
 * trailing slash. Nothing else is touched — rewriting the query would break
 * links that only work with their parameters.
 */
object LinkUrl {

    /** Schemes that make sense in a link inbox; `file:` and `javascript:` do not. */
    private val ALLOWED_SCHEMES = setOf("http", "https")

    /**
     * Returns the canonical URL, or `null` when the text is not an absolute
     * http(s) link. A bare domain gains `https://`, because that is what people
     * paste from a chat or a screenshot.
     */
    fun normalize(raw: String): String? {
        var value = raw.trim()
        // A pasted URL often arrives wrapped in angle brackets: <https://x>.
        if (value.length > 2 && value.startsWith("<") && value.endsWith(">")) {
            value = value.substring(1, value.length - 1).trim()
        }
        if (value.isEmpty() || value.any { it.isWhitespace() }) return null

        if (!value.contains("://")) {
            // Without a scheme there has to be a host that looks like one, so a
            // sentence typed by accident is not stored as a link.
            val head = value.substringBefore('/')
            if (head.isEmpty()) return null
            if (!head.contains('.') && !head.contains("localhost")) return null
            value = "https://$value"
        }

        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in ALLOWED_SCHEMES) return null

        val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
        if (host.isEmpty()) return null
        // A host without a dot is only legal for localhost and IPv6 literals.
        if (!host.contains('.') && !host.contains(':') && host != "localhost") return null

        val userInfo = uri.rawUserInfo?.let { "$it@" } ?: ""
        val port = if (uri.port == -1 || uri.port == defaultPort(scheme)) "" else ":${uri.port}"
        var path = uri.rawPath.orEmpty()
        if (path == "/") path = ""
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        val fragment = uri.rawFragment?.let { "#$it" } ?: ""

        return buildString {
            append(scheme).append("://").append(userInfo).append(host).append(port)
            append(path).append(query).append(fragment)
        }
    }

    /** Short host shown under the title in the list ("www." is noise there). */
    fun hostOf(url: String): String = runCatching {
        URI(url).host?.lowercase()?.removePrefix("www.")
    }.getOrNull().orEmpty()

    private fun defaultPort(scheme: String): Int = when (scheme) {
        "http" -> 80
        "https" -> 443
        else -> -1
    }
}
