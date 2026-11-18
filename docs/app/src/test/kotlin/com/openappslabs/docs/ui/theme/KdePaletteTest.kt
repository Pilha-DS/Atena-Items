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

package com.openappslabs.docs.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KdePaletteTest {

    private val darkGlobals = """
        [General]
        AccentColor=61,174,233

        [Colors:Window]
        BackgroundNormal=35,38,41
        ForegroundNormal=239,240,241

        [Colors:View]
        BackgroundNormal=27,30,32
        ForegroundNormal=239,240,241

        [Colors:Selection]
        BackgroundNormal=61,174,233
        ForegroundNormal=252,252,252
    """.trimIndent()

    @Test
    fun `reads window view and accent colors`() {
        val palette = assertNotNull(parseKdeGlobals(darkGlobals))

        assertEquals(Color(35, 38, 41), palette.window)
        assertEquals(Color(27, 30, 32), palette.view)
        assertEquals(Color(61, 174, 233), palette.accent)
        assertEquals(Color(252, 252, 252), palette.accentText)
        assertTrue(palette.isDark)
    }

    @Test
    fun `falls back to selection when General has no accent`() {
        val text = darkGlobals.lines()
            .filterNot { it.startsWith("AccentColor") }
            .joinToString("\n")

        assertEquals(Color(61, 174, 233), assertNotNull(parseKdeGlobals(text)).accent)
    }

    @Test
    fun `detects a light palette`() {
        val text = """
            [Colors:Window]
            BackgroundNormal=252,252,252
            ForegroundNormal=35,38,41
        """.trimIndent()

        val palette = assertNotNull(parseKdeGlobals(text))
        assertFalse(palette.isDark)
        // View missing: the window colors are reused instead of left unset.
        assertEquals(palette.window, palette.view)
    }

    @Test
    fun `returns null when the window group is missing`() {
        assertNull(parseKdeGlobals("[Colors:View]\nBackgroundNormal=27,30,32"))
        assertNull(parseKdeGlobals(""))
    }

    @Test
    fun `rejects malformed rgb values`() {
        assertNull(parseRgb("300,0,0"))
        assertNull(parseRgb("1,2"))
        assertNull(parseRgb("red,green,blue"))
        assertNull(parseRgb(null))
        assertEquals(Color(1, 2, 3), parseRgb(" 1, 2, 3 "))
        assertEquals(Color(1, 2, 3), parseRgb("1,2,3,255"))
    }

    @Test
    fun `scheme keeps the desktop colors and a contrasting outline`() {
        val palette = assertNotNull(parseKdeGlobals(darkGlobals))
        val scheme = docsColorScheme(palette)

        assertEquals(palette.window, scheme.background)
        assertEquals(palette.view, scheme.surface)
        assertEquals(palette.accent, scheme.primary)
        // The outline must be visible against the surface it is drawn on.
        assertTrue(scheme.outline != scheme.surface)
    }
}
