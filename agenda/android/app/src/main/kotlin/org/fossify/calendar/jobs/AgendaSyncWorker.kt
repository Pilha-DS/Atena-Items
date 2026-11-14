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
import org.fossify.calendar.helpers.AGENDA_SYNC_INTERVAL_MINUTES
import org.fossify.calendar.helpers.AGENDA_SYNC_PERIODIC_WORK
import org.fossify.calendar.helpers.AGENDA_SYNC_WORK
import org.fossify.calendar.helpers.AgendaSyncEngine
import org.fossify.calendar.helpers.AgendaSyncErrorKind
import java.util.concurrent.TimeUnit

/**
 * One round of the Atena Hub sync, on WorkManager.
 *
 * Triggered by a local edit (debounced), by the periodic tick while the app is
 * idle, and by the "sync now" button. There is no network constraint on
 * purpose: the manifest drops ACCESS_NETWORK_STATE, and a failed attempt is
 * retried anyway.
 */
class AgendaSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = AgendaSyncEngine.sync(applicationContext)
        return when {
            result.ok || !result.configured -> Result.success()
            result.error == AgendaSyncErrorKind.NETWORK ||
                result.error == AgendaSyncErrorKind.SERVER -> Result.retry()

            // A rejected token or a broken response will not fix itself by retrying.
            else -> Result.success()
        }
    }

    companion object {
        /** A burst of edits becomes one push. */
        private const val DEBOUNCE_SECONDS = 5L

        fun syncSoon(context: Context) {
            if (!context.config.agendaSyncConfigured) {
                return
            }
            WorkManager.getInstance(context).enqueueUniqueWork(
                AGENDA_SYNC_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AgendaSyncWorker>()
                    .setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
                    .build()
            )
        }

        fun schedulePeriodic(context: Context) {
            if (!context.config.agendaSyncConfigured) {
                cancelPeriodic(context)
                return
            }
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                AGENDA_SYNC_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<AgendaSyncWorker>(
                    AGENDA_SYNC_INTERVAL_MINUTES,
                    TimeUnit.MINUTES
                ).build()
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(AGENDA_SYNC_PERIODIC_WORK)
        }

        fun cancelPending(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(AGENDA_SYNC_WORK)
        }
    }
}
