package org.fossify.calendar.jobs

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.eventsHelper
import org.fossify.calendar.helpers.AGENDA_NOTIFY_CHECK_WORK
import org.fossify.calendar.helpers.AGENDA_NOTIFY_INTERVAL_MINUTES
import org.fossify.calendar.helpers.AGENDA_NOTIFY_WORK
import org.fossify.calendar.helpers.AgendaNotification
import org.fossify.calendar.helpers.AgendaNotifyPlan
import org.fossify.calendar.helpers.AgendaSyncEngine
import org.fossify.calendar.helpers.Config
import org.fossify.calendar.helpers.Formatter
import org.fossify.calendar.models.Event
import java.util.concurrent.TimeUnit

/**
 * The background half of the agenda notifications.
 *
 * Every run answers two questions: "is the morning summary due?" and "is an
 * event about to start?". Both answer the same way whether the app was opened
 * or not, which is the point: this is the only reason the app keeps working in
 * the background.
 *
 * Before the summary, a sync runs when the hub is configured, so an event
 * created on the other device is announced here too. The summary is remembered
 * by day and the heads-up by event+start, so a worker that wakes up twice never
 * repeats them.
 */
class AgendaNotifyWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val config = context.config
        if (!config.agendaNotifyEnabled) {
            return Result.success()
        }

        val today = Formatter.getTodayCode()
        val dayStart = Formatter.getDayStartTS(today)
        val dayEnd = Formatter.getDayEndTS(today)
        val digestDue = isDigestDue(config, today, dayStart)

        // Only the summary needs what the other device created. The heads-up
        // works with what the hub sync already pulled, so a fifteen-minute run
        // does not turn into a fifteen-minute network call.
        if (digestDue && config.agendaSyncConfigured) {
            AgendaSyncEngine.sync(context)
        }

        val events = todayEvents(dayStart, dayEnd)
        if (digestDue) {
            if (events.isNotEmpty()) {
                AgendaNotification.showDigest(context, events)
            }
            // Marked even with nothing to say, otherwise the check would repeat
            // every fifteen minutes until an event showed up.
            config.agendaNotifyDigestDay = today
        }

        val kept = AgendaNotifyPlan.pruneKeys(config.agendaNotifySent, dayStart, dayEnd)
        config.agendaNotifySent = announceUpcoming(config, events, kept)
        return Result.success()
    }

    private fun todayEvents(dayStart: Long, dayEnd: Long): List<Event> {
        val loaded = ArrayList<Event>()
        applicationContext.eventsHelper.getEventsSync(dayStart, dayEnd, applyTypeFilter = true) {
            loaded.addAll(it)
        }
        return loaded
    }

    private fun isDigestDue(config: Config, today: String, dayStart: Long): Boolean {
        if (!config.agendaNotifyDigest || config.agendaNotifyDigestDay == today) {
            return false
        }

        val digestAt = dayStart + config.agendaNotifyDigestAt.toLong() * SECONDS_PER_MINUTE
        return System.currentTimeMillis() / 1000L >= digestAt
    }

    private fun announceUpcoming(
        config: Config,
        events: List<Event>,
        sent: Set<String>
    ): Set<String> {
        if (!config.agendaNotifyRemind) {
            return sent
        }

        val now = System.currentTimeMillis() / 1000L
        val lead = config.agendaNotifyLead.toLong() * SECONDS_PER_MINUTE
        val due = AgendaNotifyPlan.dueEvents(events, now, lead, sent)
        var updated = sent
        for (event in due) {
            AgendaNotification.showEventSoon(applicationContext, event, config.agendaNotifyLead)
            updated = updated + AgendaNotifyPlan.key(event)
        }
        return updated
    }

    companion object {
        fun schedulePeriodic(context: Context) {
            if (!context.config.agendaNotifyEnabled) {
                cancelPeriodic(context)
                return
            }
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                AGENDA_NOTIFY_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<AgendaNotifyWorker>(
                    AGENDA_NOTIFY_INTERVAL_MINUTES,
                    TimeUnit.MINUTES
                ).build()
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(AGENDA_NOTIFY_WORK)
        }

        /** One run right away, so toggling in Settings shows the effect at once. */
        fun checkSoon(context: Context) {
            if (!context.config.agendaNotifyEnabled) {
                return
            }
            WorkManager.getInstance(context).enqueueUniqueWork(
                AGENDA_NOTIFY_CHECK_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AgendaNotifyWorker>()
                    .setInitialDelay(CHECK_DELAY_SECONDS, TimeUnit.SECONDS)
                    .build()
            )
        }

        private const val CHECK_DELAY_SECONDS = 3L
        private const val SECONDS_PER_MINUTE = 60L
    }
}
