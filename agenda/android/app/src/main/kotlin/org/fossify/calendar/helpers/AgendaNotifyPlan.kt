package org.fossify.calendar.helpers

import org.fossify.calendar.models.Event

/**
 * What to announce, without touching Android's notification APIs, so the rule
 * stays in one readable place: nothing for all-day events (there is no "in ten
 * minutes" for them), nothing for tasks, and nothing twice for the same start.
 */
object AgendaNotifyPlan {

    fun key(event: Event): String = "${event.id ?: -1L}@${event.getEventStartTS()}"

    /** Events starting inside the lead window, oldest first. */
    fun dueEvents(
        events: List<Event>,
        nowSeconds: Long,
        leadSeconds: Long,
        sent: Set<String>
    ): List<Event> = events
        .filter { !it.getIsAllDay() && !it.isTask() }
        .filter { it.getEventStartTS() > nowSeconds && it.getEventStartTS() - nowSeconds <= leadSeconds }
        .filter { key(it) !in sent }
        .sortedBy { it.getEventStartTS() }

    /** Keys from other days are dropped, so the preference cannot grow forever. */
    fun pruneKeys(sent: Set<String>, dayStart: Long, dayEnd: Long): Set<String> = sent
        .filter { key -> key.substringAfter('@').toLongOrNull()?.let { it in dayStart..dayEnd } == true }
        .toSet()
}
