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

package com.openappslabs.docs.data.search

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Habit score in `[0, 1]`, matching the launcher formula.
 *
 * Frequency (how often) and recency (how recent) are capped together so a
 * single document cannot dominate the list forever.
 */
object Frecency {

    /** Score points (out of 100) a maximal frecency is worth — same as Vicinae. */
    const val WEIGHT = 6.0

    private const val FREQUENCY_SCALE = 5.0
    private const val RECENCY_PEAK = 10.0
    private const val RECENCY_HALF_LIFE_DAYS = 30.0
    private const val CAP = 25.0
    private const val SECONDS_PER_DAY = 86_400.0

    /**
     * @param visitCount opens from the window
     * @param lastVisitedAtMs epoch millis of the last open; 0 = never
     * @param nowMs epoch millis "now"
     */
    fun score(visitCount: Int, lastVisitedAtMs: Long, nowMs: Long): Double {
        if (visitCount <= 0 && lastVisitedAtMs <= 0L) return 0.0
        val frequency = FREQUENCY_SCALE * ln(1.0 + visitCount.coerceAtLeast(0) * 0.1)
        var recency = 0.0
        if (lastVisitedAtMs > 0L) {
            val nowSec = nowMs / 1000.0
            val lastSec = lastVisitedAtMs / 1000.0
            val daysSince = (nowSec - lastSec) / SECONDS_PER_DAY
            recency = RECENCY_PEAK * exp(-max(0.0, daysSince) / RECENCY_HALF_LIFE_DAYS)
        }
        return min(CAP, frequency + recency) / CAP
    }
}
