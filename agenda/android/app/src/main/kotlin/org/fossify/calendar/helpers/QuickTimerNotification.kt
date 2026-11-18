package org.fossify.calendar.helpers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import org.fossify.calendar.R
import org.fossify.calendar.activities.QuickTimersActivity
import org.fossify.calendar.models.QuickTimer
import org.fossify.calendar.receivers.QuickTimerReceiver
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.isOreoPlus

/**
 * The end of a quick timer: it looks and sounds like the phone's clock alarm,
 * on its own channel so muting the agenda does not mute the timers.
 */
object QuickTimerNotification {

    fun show(context: Context, timer: QuickTimer) {
        val manager = context.notificationManager
        if (isOreoPlus()) {
            manager.createNotificationChannel(channel(context))
        }

        val title = timer.title.ifBlank { context.getString(R.string.quick_timer_default_title) }
        val notification = NotificationCompat.Builder(context, QuickTimerAlarms.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_clock_outline_vector)
            .setContentTitle(context.getString(R.string.quick_timer_finished_title, title))
            .setContentText(context.getString(R.string.quick_timer_finished_body))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.quick_timer_finished_body))
            )
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .addAction(
                0,
                context.getString(R.string.quick_timer_dismiss),
                dismissIntent(context, timer.uuid)
            )
            .build()

        // Android 13+ without the notification permission throws here. The
        // timer is still marked as rung, exactly like a muted alarm.
        runCatching { manager.notify(notificationId(timer.uuid), notification) }
    }

    fun dismiss(context: Context, uuid: String) {
        context.notificationManager.cancel(notificationId(uuid))
    }

    private fun channel(context: Context): NotificationChannel =
        NotificationChannel(
            QuickTimerAlarms.CHANNEL_ID,
            context.getString(R.string.quick_timer_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setShowBadge(true)
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }

    /** Stable per timer, so a sync edit replaces the same notification. */
    private fun notificationId(uuid: String): Int = QUICK_TIMER_BASE + (uuid.hashCode() and ID_MASK)

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, QuickTimersActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun dismissIntent(context: Context, uuid: String): PendingIntent {
        val intent = Intent(context, QuickTimerReceiver::class.java).apply {
            action = QuickTimerAlarms.ACTION_DISMISS
            data = QuickTimerAlarms.uuidUri(uuid)
            putExtra(QuickTimerAlarms.EXTRA_UUID, uuid)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private const val QUICK_TIMER_BASE = 41_500
    private const val ID_MASK = 0xFFFF

    /** A phone-alarm rhythm: wait, buzz, pause, buzz. */
    @Suppress("MagicNumber")
    private val VIBRATION_PATTERN = longArrayOf(0L, 500L, 300L, 500L)
}
