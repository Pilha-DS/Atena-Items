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

package com.openappslabs.docs.data.tags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TagAliasesTest {

    @Test
    fun `trims and lowercases`() {
        assertEquals("nfe", TagAliases.normalize("  NFe  "))
    }

    @Test
    fun `rejects path shaped aliases and empty text`() {
        assertNull(TagAliases.normalize(""))
        assertNull(TagAliases.normalize("   "))
        assertNull(TagAliases.normalize("nota/fiscal"))
        assertNull(TagAliases.normalize("a".repeat(TagAliases.MAX_LENGTH + 1)))
    }

    @Test
    fun `redundant when it only repeats the tag`() {
        assertTrue(TagAliases.redundant("nota-fiscal", "nota-fiscal"))
        assertTrue(TagAliases.redundant("fiscal", "nota/fiscal"))
        assertFalse(TagAliases.redundant("nfe", "nota-fiscal"))
    }
}
