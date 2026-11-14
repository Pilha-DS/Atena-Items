package org.fossify.calendar.helpers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.fossify.calendar.R
import org.fossify.calendar.activities.MainActivity
import org.fossify.calendar.models.Event
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.isOreoPlus
import org.joda.time.DateTime

/**
 * The two notifications of the day: the morning summary and the heads-up
 * before an event. They live on their own channel, so muting the event
 * reminders does not mute the agenda, and vice versa.
 */
object AgendaNotification {

    fun showDigest(context: Context, events: List<Event>) {
        val body = events.joinToString("\n") { "${timeLabel(context, it)}  ${it.title}" }
        post(context, AGENDA_NOTIFY_DIGEST_ID, context.getString(R.string.agenda_notify_digest_title), body)
    }

    fun showEventSoon(context: Context, event: Event, leadMinutes: Int) {
        val title = context.getString(R.string.agenda_notify_soon_title, leadMinutes, event.title)
        val body = buildString {
            append(timeLabel(context, event))
            if (event.location.isNotBlank()) {
                append('\n').append(event.location)
            }
        }
        // One notification per event, so two events close together do not
        // overwrite each other.
        post(context, NOTIFICATION_BASE + (event.id ?: 0L).toInt(), title, body)
    }

    fun cancelDigest(context: Context) {
        context.notificationManager.cancel(AGENDA_NOTIFY_DIGEST_ID)
    }

    fun cancelEvent(context: Context, eventId: Long) {
        context.notificationManager.cancel(NOTIFICATION_BASE + eventId.toInt())
    }

    private fun timeLabel(context: Context, event: Event): String =
        if (event.getIsAllDay()) {
            context.getString(R.string.all_day)
        } else {
            Formatter.getTime(context, DateTime(event.getEventStartTS() * 1000L))
        }

    private fun post(context: Context, id: Int, title: String, body: String) {
        val manager = context.notificationManager
        if (isOreoPlus()) {
            val channel = NotificationChannel(
                AGENDA_NOTIFY_CHANNEL_ID,
                context.getString(R.string.agenda_notify_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setShowBadge(true)
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, AGENDA_NOTIFY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_calendar_vector)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // Android 13+ without the notification permission throws here. The
        // worker keeps running: the user chose not to see notifications.
        runCatching { manager.notify(id, notification) }
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Below the digest id, so the summary never collides with an event. */
    private const val NOTIFICATION_BASE = 41_000
}
