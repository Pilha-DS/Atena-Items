/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Agenda
 *
 * Agenda is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.agenda.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KdeGlobalsTest {

    @Test
    fun reads_sections_and_ignores_comments() {
        val values = parseKdeGlobals(
            listOf(
                "[General]",
                "AccentColor=61,174,233",
                "# a comment",
                "",
                "[Colors:Window]",
                "BackgroundNormal=35,38,41",
                "ForegroundNormal=239,240,241"
            )
        )

        assertEquals("61,174,233", values["General/AccentColor"])
        assertEquals("35,38,41", values["Colors:Window/BackgroundNormal"])
        assertEquals("239,240,241", values["Colors:Window/ForegroundNormal"])
    }

    @Test
    fun parses_rgb_triples() {
        assertEquals(Color(61, 174, 233), parseKdeColor("61,174,233"))
        assertEquals(Color(255, 0, 0), parseKdeColor(" 255, 0, 0 "))
    }

    @Test
    fun rejects_values_that_are_not_colors() {
        assertNull(parseKdeColor("transparent"))
        assertNull(parseKdeColor("10,20"))
    }
}
