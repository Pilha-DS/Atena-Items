package org.fossify.calendar.helpers

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.fossify.calendar.databases.EventsDatabase
import org.fossify.calendar.extensions.getAlarmManager
import org.fossify.calendar.extensions.setExactAlarm
import org.fossify.calendar.models.QuickTimer
import org.fossify.calendar.receivers.QuickTimerReceiver
import org.fossify.commons.helpers.ensureBackgroundThread

/**
 * Puts each quick timer on the system alarm clock.
 *
 * The alarm carries only the uuid; when it fires, the timer is read again from
 * the database. That way a deleted timer, one already rung or one whose
 * deadline moved on another device never rings the wrong thing.
 *
 * The deadline is absolute (an epoch millis), so a reboot, an app update or a
 * sync edit only means rescheduling — never a drifting countdown.
 */
object QuickTimerAlarms {

    const val ACTION_RING = "org.fossify.calendar.action.QUICK_TIMER_RING"
    const val ACTION_DISMISS = "org.fossify.calendar.action.QUICK_TIMER_DISMISS"
    const val EXTRA_UUID = "quick_timer_uuid"
    const val CHANNEL_ID = "agenda_quick_timers"

    fun schedule(context: Context, timer: QuickTimer) {
        if (timer.notified) {
            cancel(context, timer.uuid)
            return
        }

        if (timer.endTime <= System.currentTimeMillis()) {
            // Already due: going through the same path keeps a single rule for
            // "too old to ring", instead of an alarm firing at boot.
            ringSoon(context, timer.uuid)
            return
        }

        context.setExactAlarm(timer.endTime, pendingIntent(context, timer.uuid))
    }

    fun cancel(context: Context, uuid: String) {
        context.getAlarmManager().cancel(pendingIntent(context, uuid))
    }

    /** Called by the receiver when the countdown reaches zero. */
    fun ring(context: Context, uuid: String) {
        val dao = EventsDatabase.getInstance(context.applicationContext).QuickTimersDao()
        val timer = dao.getByUuid(uuid) ?: return
        val now = System.currentTimeMillis()
        when {
            timer.notified -> Unit
            QuickTimerTimes.shouldRing(timer, now) -> {
                QuickTimerNotification.show(context, timer)
                dao.markNotified(uuid)
            }

            QuickTimerTimes.isStale(timer, now) -> dao.markNotified(uuid)
        }
    }

    fun dismissNotification(context: Context, uuid: String) {
        QuickTimerNotification.dismiss(context, uuid)
    }

    /** After a reboot, an app update or a pull: every pending timer is re-armed. */
    fun rescheduleAll(context: Context) {
        val appContext = context.applicationContext
        ensureBackgroundThread {
            val dao = EventsDatabase.getInstance(appContext).QuickTimersDao()
            for (timer in dao.getAll()) {
                if (!timer.notified) {
                    schedule(appContext, timer)
                }
            }
        }
    }

    fun ringSoon(context: Context, uuid: String) {
        val appContext = context.applicationContext
        ensureBackgroundThread { ring(appContext, uuid) }
    }

    fun uuidUri(uuid: String): Uri = Uri.Builder()
        .scheme("quicktimer")
        .authority("timer")
        .appendPath(uuid)
        .build()

    private fun pendingIntent(context: Context, uuid: String): PendingIntent {
        val intent = Intent(context, QuickTimerReceiver::class.java).apply {
            action = ACTION_RING
            // Part of the intent identity, so two timers never share an alarm.
            data = uuidUri(uuid)
            putExtra(EXTRA_UUID, uuid)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
