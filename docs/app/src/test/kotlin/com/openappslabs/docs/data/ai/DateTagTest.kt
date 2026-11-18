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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DateTagTest {

    @Test
    fun `the date tags the library actually ended up with`() {
        // The four that were in the database and caused the month tag on every
        // extension certificate.
        assertTrue(DateTag.isDateOnly("2026"))
        assertTrue(DateTag.isDateOnly("2025"))
        assertTrue(DateTag.isDateOnly("mes-11"))
        assertTrue(DateTag.isDateOnly("mes-10"))
    }

    @Test
    fun `other ways of writing a date count too`() {
        assertTrue(DateTag.isDateOnly("mes_11"))
        assertTrue(DateTag.isDateOnly("Mes 11"))
        assertTrue(DateTag.isDateOnly("m11"))
        assertTrue(DateTag.isDateOnly("11/2026"))
        assertTrue(DateTag.isDateOnly("2026-11"))
        assertTrue(DateTag.isDateOnly("13-11-2026"))
        assertTrue(DateTag.isDateOnly("2026/11/13"))
    }

    @Test
    fun `a date tag with a year and a month is reusable vocabulary`() {
        // `2025/mes-4` can be reused for another April 2025 document; the flat
        // `2026` and `mes-11` cannot, and stay out of the list shown to the
        // model.
        assertTrue(DateTag.isReusable("2025/mes-4"))
        assertTrue(DateTag.isReusable("2025/mes-11"))
        assertTrue(DateTag.isReusable("2025/outubro"))
        assertFalse(DateTag.isReusable("2026"))
        assertFalse(DateTag.isReusable("mes-11"))
        assertFalse(DateTag.isReusable("extensao"))
    }

    @Test
    fun `a date below a subject is not a date tag`() {
        // `unip/2026` says `unip` first: the model may reuse it, and the tag is
        // meaningful. Only a path made of dates alone is filtered out.
        assertFalse(DateTag.isDateOnly("unip/2026"))
        assertFalse(DateTag.isDateOnly("2026/unip"))
        assertFalse(DateTag.isDateOnly("academico/mes-11"))
    }

    @Test
    fun `a subject that merely contains a number is left alone`() {
        assertFalse(DateTag.isDateOnly("extensao"))
        assertFalse(DateTag.isDateOnly("nota-fiscal"))
        assertFalse(DateTag.isDateOnly("projeto-2026"))
        assertFalse(DateTag.isDateOnly("2026-faculdade"))
        assertFalse(DateTag.isDateOnly(""))
    }

    @Test
    fun `canonicalize turns bare year-month into ano-mes-N`() {
        assertEquals("2025/mes-11", DateTag.canonicalize("2025/11"))
        assertEquals("2025/mes-11", DateTag.canonicalize("11/2025"))
        assertEquals("2026/mes-4", DateTag.canonicalize("2026/4"))
        assertEquals("2026/mes-4", DateTag.canonicalize("2026/04"))
    }

    @Test
    fun `canonicalize leaves proper tags alone`() {
        assertEquals("2025/mes-11", DateTag.canonicalize("2025/mes-11"))
        assertEquals("2026/semestre-2", DateTag.canonicalize("2026/semestre-2"))
        assertEquals("extensao", DateTag.canonicalize("extensao"))
        assertEquals("unip/2026", DateTag.canonicalize("unip/2026"))
    }

    @Test
    fun `resolveExisting prefers separate year and month tags`() {
        val stamp = DocDate.Stamp(year = 2025, month = 11)
        assertEquals(
            listOf("2025", "mes-11"),
            DateTag.resolveExisting(stamp, listOf("2025", "mes-11", "extensao"))
        )
        assertEquals(
            listOf("2025/mes-11"),
            DateTag.resolveExisting(stamp, listOf("2025/mes-11", "2025", "mes-11"))
        )
        assertEquals(
            listOf("2025/mes-11"),
            DateTag.resolveExisting(stamp, listOf("extensao"))
        )
    }

    @Test
    fun `remap turns a compound date into the library year and month`() {
        val stamp = DocDate.Stamp(year = 2025, month = 11)
        assertEquals(
            listOf("2025", "mes-11"),
            DateTag.remap("2025/11", listOf("2025", "mes-11"), stamp)
        )
    }

    @Test
    fun `a bare month is allowed only when the document stamp has that month`() {
        val stamp = DocDate.Stamp(year = 2025, month = 11)
        assertTrue(DateTag.allowedIn("mes-11", "qualquer texto", stamp))
        assertFalse(DateTag.allowedIn("mes-10", "qualquer texto", stamp))
        assertTrue(DateTag.allowedIn("2025", "qualquer texto", stamp))
        assertFalse(DateTag.allowedIn("2024", "qualquer texto", stamp))
    }
}
