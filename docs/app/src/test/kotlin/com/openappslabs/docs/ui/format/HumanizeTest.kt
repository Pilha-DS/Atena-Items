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

package com.openappslabs.docs.ui.format

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class HumanizeTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun `small sizes keep bytes`() {
        assertEquals("0 B", formatSize(0))
        assertEquals("999 B", formatSize(999))
    }

    @Test
    fun `sizes use decimal units like the file manager`() {
        assertEquals("1,0 KB", formatSize(1000))
        assertEquals("1,5 KB", formatSize(1500))
        assertEquals("2,5 MB", formatSize(2_500_000))
        assertEquals("1,0 GB", formatSize(1_000_000_000))
    }

    @Test
    fun `big values lose the decimal`() {
        assertEquals("123 MB", formatSize(123_456_789))
    }

    @Test
    fun `negative size is unknown`() {
        assertEquals("—", formatSize(-1))
    }

    @Test
    fun `date and time are displayed in the local format`() {
        val millis = ZonedDateTime.of(2026, 11, 14, 9, 5, 0, 0, utc).toInstant().toEpochMilli()

        assertEquals("14/11/2026 09:05", formatDateTime(millis, utc))
        assertEquals("09:05", formatTime(millis, utc))
    }

    @Test
    fun `details line is kind, size, date and folder`() {
        val millis = ZonedDateTime.of(2026, 11, 14, 9, 5, 0, 0, utc).toInstant().toEpochMilli()

        assertEquals(
            "PDF · 1,5 KB · 14/11/2026 09:05 · faculdade",
            docDetails("PDF", 1500, millis, "faculdade", utc)
        )
    }

    @Test
    fun `root level files do not repeat the folder`() {
        val millis = ZonedDateTime.of(2026, 11, 14, 9, 5, 0, 0, utc).toInstant().toEpochMilli()

        assertEquals("PDF · 1,5 KB · 14/11/2026 09:05", docDetails("PDF", 1500, millis, "", utc))
    }
}
