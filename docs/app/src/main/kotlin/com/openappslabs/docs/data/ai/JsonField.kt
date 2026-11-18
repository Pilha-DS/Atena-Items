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
 * The two lines of JSON this app needs, read without a library.
 *
 * The only JSON crossing the wire is Ollama's: `{"response":"..."}` from
 * `/api/generate` and `{"models":[{"name":"..."}]}` from `/api/tags`. A full
 * parser would be a dependency for two fields, so the scanner walks the object
 * and decodes escapes properly — a model answer with quotes or accented
 * characters must survive the trip.
 */
object JsonField {

    /** Value of the first `"name": "…"` pair, decoded. */
    fun string(json: String, name: String): String? {
        val needle = "\"$name\""
        var index = json.indexOf(needle)
        while (index >= 0) {
            var cursor = index + needle.length
            while (cursor < json.length && json[cursor].isWhitespace()) cursor++
            if (cursor < json.length && json[cursor] == ':') {
                cursor++
                while (cursor < json.length && json[cursor].isWhitespace()) cursor++
                if (cursor < json.length && json[cursor] == '"') return readString(json, cursor)
            }
            index = json.indexOf(needle, index + 1)
        }
        return null
    }

    /** Every value of `"name": "…"` in the document, in order. */
    fun allStrings(json: String, name: String): List<String> {
        val values = mutableListOf<String>()
        var from = 0
        while (true) {
            val found = json.indexOf("\"$name\"", from)
            if (found < 0) break
            var cursor = found + name.length + 2
            while (cursor < json.length && json[cursor].isWhitespace()) cursor++
            if (cursor < json.length && json[cursor] == ':') {
                cursor++
                while (cursor < json.length && json[cursor].isWhitespace()) cursor++
                if (cursor < json.length && json[cursor] == '"') {
                    val value = readString(json, cursor)
                    if (value != null) values.add(value)
                    cursor += 1
                }
            }
            from = maxOf(cursor, found + 1)
        }
        return values
    }

    /** Decodes the string literal that starts at [startQuote]. */
    internal fun readString(json: String, startQuote: Int): String? {
        val builder = StringBuilder()
        var index = startQuote + 1
        while (index < json.length) {
            val char = json[index]
            when {
                char == '"' -> return builder.toString()
                char == '\\' && index + 1 < json.length -> {
                    val next = json[index + 1]
                    when (next) {
                        'n' -> builder.append('\n')
                        't' -> builder.append('\t')
                        'r' -> builder.append('\r')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000c')
                        '"' -> builder.append('"')
                        '\\' -> builder.append('\\')
                        '/' -> builder.append('/')
                        'u' -> {
                            val hex = json.substring(index + 2, minOf(index + 6, json.length))
                            val code = hex.toIntOrNull(16)
                            if (code == null) {
                                builder.append("\\u").append(hex)
                            } else {
                                builder.append(code.toChar())
                            }
                            index += 4
                        }
                        else -> builder.append(next)
                    }
                    index += 2
                }
                else -> {
                    builder.append(char)
                    index += 1
                }
            }
        }
        return null
    }

    /** Escapes the *content* of a JSON string: the caller adds the quotes. */
    fun escape(value: String): String {
        val builder = StringBuilder(value.length + 2)
        for (char in value) {
            when (char) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                '\b' -> builder.append("\\b")
                '\u000c' -> builder.append("\\f")
                else -> if (char < ' ') {
                    builder.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(char)
                }
            }
        }
        return builder.toString()
    }
}
