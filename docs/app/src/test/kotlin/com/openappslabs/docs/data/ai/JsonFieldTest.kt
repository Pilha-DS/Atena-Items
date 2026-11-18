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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JsonFieldTest {

    @Test
    fun `reads a plain string field`() {
        assertEquals("llama3.1", JsonField.string("""{"model":"llama3.1","done":true}""", "model"))
    }

    @Test
    fun `decodes the usual escapes`() {
        val json = """{"response":"linha 1\nlinha 2\tcom \"aspas\" e \\\\ barra"}"""
        assertEquals("linha 1\nlinha 2\tcom \"aspas\" e \\\\ barra", JsonField.string(json, "response"))
    }

    @Test
    fun `decodes unicode escapes`() {
        assertEquals("etiqueta ção", JsonField.string("""{"response":"etiqueta \u00e7\u00e3o"}""", "response"))
    }

    @Test
    fun `ignores whitespace around the colon`() {
        assertEquals("x", JsonField.string("{ \"a\" : \"x\" }", "a"))
    }

    @Test
    fun `a missing field is null, not an exception`() {
        assertNull(JsonField.string("""{"other":"v"}""", "response"))
        assertNull(JsonField.string("not json at all", "response"))
        assertNull(JsonField.string("""{"response":123}""", "response"))
    }

    @Test
    fun `allStrings collects every occurrence in order`() {
        val json = """{"models":[{"name":"a:1"},{"name":"b:2"}]}"""
        assertEquals(listOf("a:1", "b:2"), JsonField.allStrings(json, "name"))
    }

    @Test
    fun `escape produces a value that reads back the same`() {
        val original = "Declaração \"final\" \\ 100%"
        val json = "{\"prompt\":\"" + JsonField.escape(original) + "\"}"
        assertEquals(original, JsonField.string(json, "prompt"))
    }

    @Test
    fun `control characters are escaped`() {
        assertEquals("a\\u0001b", JsonField.escape("a\u0001b"))
    }
}
