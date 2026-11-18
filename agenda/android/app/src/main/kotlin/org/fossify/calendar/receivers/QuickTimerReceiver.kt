package org.fossify.calendar.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import org.fossify.calendar.helpers.QuickTimerAlarms
import org.fossify.commons.helpers.ensureBackgroundThread

/**
 * Wakes the app when a quick timer reaches zero.
 *
 * Reading the timer again (instead of trusting the intent) is what makes a
 * cancelled or rescheduled timer safe: the row is the source of truth.
 */
class QuickTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uuid = intent.getStringExtra(QuickTimerAlarms.EXTRA_UUID) ?: return
        if (intent.action == QuickTimerAlarms.ACTION_DISMISS) {
            QuickTimerAlarms.dismissNotification(context, uuid)
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakelock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "simplecalendar:quicktimerreceiver"
        )
        wakelock.acquire(WAKE_LOCK_TIMEOUT_MS)
        ensureBackgroundThread {
            QuickTimerAlarms.ring(context, uuid)
            wakelock.release()
        }
    }

    private companion object {
        const val WAKE_LOCK_TIMEOUT_MS = 10_000L
    }
}
