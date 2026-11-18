/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Agenda
 *
 * Agenda is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.agenda.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class ToastProgressTest {

    private val start = 1_000_000L
    private val duration = 2_000L

    @Test
    fun starts_full() {
        assertEquals(1f, toastProgress(start, start, duration))
    }

    @Test
    fun shrinks_with_the_clock() {
        assertEquals(0.75f, toastProgress(start, start + 500, duration))
        assertEquals(0.5f, toastProgress(start, start + 1_000, duration))
        assertEquals(0.25f, toastProgress(start, start + 1_500, duration))
    }

    @Test
    fun ends_empty_and_never_goes_below_zero() {
        assertEquals(0f, toastProgress(start, start + duration, duration))
        assertEquals(0f, toastProgress(start, start + 5 * duration, duration))
    }

    @Test
    fun a_clock_that_went_backwards_does_not_overflow() {
        assertEquals(1f, toastProgress(start, start - 1_000, duration))
    }

    @Test
    fun a_zero_duration_is_never_full() {
        assertEquals(0f, toastProgress(start, start, 0L))
    }
}
