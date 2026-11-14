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

class WindowPlacementTest {

    private val screen = Rectangle(0, 0, 1920, 1080)

    @Test
    fun opens_below_the_icon_and_centered_on_it() {
        val placed = WindowPlacement.below(
            anchor = IpcBridge.Anchor(x = 900, y = 10, width = 60, height = 30),
            width = 460,
            height = 700,
            screen = screen
        )
        assertEquals(700, placed.x)
        assertEquals(46, placed.y)
    }

    @Test
    fun never_goes_past_the_left_edge() {
        val placed = WindowPlacement.below(
            anchor = IpcBridge.Anchor(x = 10, y = 10, width = 40, height = 30),
            width = 460,
            height = 700,
            screen = screen
        )
        assertEquals(0, placed.x)
    }

    @Test
    fun never_goes_past_the_right_edge() {
        val placed = WindowPlacement.below(
            anchor = IpcBridge.Anchor(x = 1900, y = 10, width = 40, height = 30),
            width = 460,
            height = 700,
            screen = screen
        )
        assertEquals(1460, placed.x)
    }

    @Test
    fun flips_above_when_there_is_no_room_below() {
        val placed = WindowPlacement.below(
            anchor = IpcBridge.Anchor(x = 800, y = 900, width = 60, height = 30),
            width = 300,
            height = 200,
            screen = screen
        )
        assertEquals(694, placed.y)
    }

    @Test
    fun stays_inside_a_short_screen() {
        val placed = WindowPlacement.below(
            anchor = IpcBridge.Anchor(x = 100, y = 10, width = 40, height = 30),
            width = 460,
            height = 700,
            screen = Rectangle(0, 0, 1920, 600)
        )
        assertEquals(0, placed.y)
    }
}
