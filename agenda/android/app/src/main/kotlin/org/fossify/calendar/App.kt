package org.fossify.calendar

import org.fossify.calendar.extensions.hasDummyAlarm
import org.fossify.calendar.jobs.AgendaNotifyWorker
import org.fossify.calendar.jobs.AgendaSyncWorker
import org.fossify.calendar.jobs.AppStartupWorker
import org.fossify.commons.FossifyApp

class App : FossifyApp() {
    override fun onCreate() {
        super.onCreate()
        if (!hasDummyAlarm()) {
            AppStartupWorker.start(this)
        }
        AgendaSyncWorker.schedulePeriodic(this)
        // WorkManager keeps periodic work across reboots, but scheduling on
        // every start is what makes an install upgrade start notifying without
        // waiting for the user to open the app first.
        AgendaNotifyWorker.schedulePeriodic(this)
    }
}
