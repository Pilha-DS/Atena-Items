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

/**
 * Decides what the timer thread does on each tick.
 *
 * A timer whose countdown just reached zero rings once. One that expired long
 * ago is only marked: it may have arrived through the sync when the app was
 * closed, and an alarm for something from yesterday is noise, not a reminder.
 */
object QuickTimerPlan {

    /** A timer that ended up to this long ago still rings; older ones are silent. */
    const val RING_GRACE_MS = 10L * 60L * 1000L

    data class Plan(val ring: List<QuickTimer>, val silent: List<QuickTimer>) {
        val isEmpty: Boolean get() = ring.isEmpty() && silent.isEmpty()
    }

    fun plan(timers: List<QuickTimer>, now: Long): Plan {
        val ring = ArrayList<QuickTimer>()
        val silent = ArrayList<QuickTimer>()
        for (timer in timers) {
            if (timer.notified || timer.endTime > now) continue
            if (now - timer.endTime <= RING_GRACE_MS) ring.add(timer) else silent.add(timer)
        }
        return Plan(ring = ring, silent = silent)
    }
}
