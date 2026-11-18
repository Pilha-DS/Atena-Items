/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Agenda
 *
 * Agenda is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.agenda.platform

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class ToastPlacementTest {

    private val screen = Rectangle(0, 0, 1920, 1080)

    @Test
    fun sits_in_the_top_right_corner_with_a_margin() {
        val placed = ToastPlacement.topRight(screen, width = 340, height = 88, margin = 16)
        assertEquals(1920 - 340 - 16, placed.x)
        assertEquals(16, placed.y)
    }

    @Test
    fun follows_a_panel_that_shrinks_the_usable_area() {
        // A 40 px panel on the right and 32 px on the top are already excluded.
        val usable = Rectangle(0, 32, 1880, 1048)
        val placed = ToastPlacement.topRight(usable, width = 340, height = 88, margin = 16)
        assertEquals(1880 - 340 - 16, placed.x)
        assertEquals(32 + 16, placed.y)
    }

    @Test
    fun stays_inside_a_screen_narrower_than_the_toast() {
        val placed = ToastPlacement.topRight(Rectangle(0, 0, 300, 400), 340, 88, 16)
        assertEquals(0, placed.x)
    }

    @Test
    fun stays_inside_a_screen_shorter_than_the_toast() {
        val placed = ToastPlacement.topRight(Rectangle(0, 0, 1920, 60), 340, 88, 16)
        assertEquals(0, placed.y)
    }

    @Test
    fun respects_a_second_monitor_origin() {
        val second = Rectangle(1920, 0, 1920, 1080)
        val placed = ToastPlacement.topRight(second, 340, 88, 16)
        assertEquals(1920 + 1920 - 340 - 16, placed.x)
        assertEquals(16, placed.y)
    }
}
