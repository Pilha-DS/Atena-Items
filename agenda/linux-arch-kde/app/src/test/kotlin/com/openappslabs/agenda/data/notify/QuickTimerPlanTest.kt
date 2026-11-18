/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Agenda
 *
 * Agenda is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Agenda is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Agenda.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.agenda.data.notify

import com.openappslabs.agenda.data.model.QuickTimer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuickTimerPlanTest {

    private val now = 10_000_000L

    private fun timer(
        uuid: String = "a",
        endTime: Long,
        notified: Boolean = false
    ) = QuickTimer(uuid = uuid, title = "Ir ao mercado", endTime = endTime, notified = notified)

    @Test
    fun a_timer_that_just_ended_rings() {
        val plan = QuickTimerPlan.plan(listOf(timer(endTime = now - 1_000)), now)

        assertEquals(1, plan.ring.size)
        assertTrue(plan.silent.isEmpty())
    }

    @Test
    fun a_timer_ending_in_the_future_waits() {
        val plan = QuickTimerPlan.plan(listOf(timer(endTime = now + 60_000)), now)

        assertTrue(plan.isEmpty)
    }

    @Test
    fun an_already_rung_timer_is_left_alone() {
        val plan = QuickTimerPlan.plan(listOf(timer(endTime = now - 1_000, notified = true)), now)

        assertTrue(plan.isEmpty)
    }

    @Test
    fun an_old_timer_from_the_sync_is_silenced_instead_of_ringing() {
        val old = now - QuickTimerPlan.RING_GRACE_MS - 1
        val plan = QuickTimerPlan.plan(listOf(timer(endTime = old)), now)

        assertTrue(plan.ring.isEmpty())
        assertEquals(1, plan.silent.size)
    }

    @Test
    fun the_grace_window_is_inclusive() {
        val edge = now - QuickTimerPlan.RING_GRACE_MS
        val plan = QuickTimerPlan.plan(listOf(timer(endTime = edge)), now)

        assertEquals(1, plan.ring.size)
    }
}
