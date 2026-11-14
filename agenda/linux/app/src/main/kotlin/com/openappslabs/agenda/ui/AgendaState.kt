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

package com.openappslabs.agenda.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.openappslabs.agenda.data.model.Event
import com.openappslabs.agenda.data.model.EventTimes
import com.openappslabs.agenda.data.repository.EventRepository
import com.openappslabs.agenda.data.sync.AgendaSyncEngine
import com.openappslabs.agenda.data.sync.SyncConfig
import com.openappslabs.agenda.data.sync.SyncErrorKind
import com.openappslabs.agenda.data.sync.SyncException
import com.openappslabs.agenda.data.sync.SyncPreferences
import com.openappslabs.agenda.data.sync.SyncUiState
import com.openappslabs.agenda.platform.WindowPreferences
import com.openappslabs.agenda.ui.i18n.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * State of the window. The composables only read these values and call the
 * functions below — loading, validating and persisting happens here.
 */
class AgendaState(
    private val repository: EventRepository,
    private val syncEngine: AgendaSyncEngine,
    private val preferences: SyncPreferences,
    private val windowPreferences: WindowPreferences,
    private val scope: CoroutineScope,
    private val onDataChanged: () -> Unit = {}
) {

    /** Cancelled and replaced on every navigation, so stale loads never land. */
    private var loadJob: Job? = null

    private val locale: Locale = Locale.forLanguageTag("pt-BR")
    private val monthFormat = DateTimeFormatter.ofPattern("MMMM", locale)
    private val dayFormat = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", locale)
    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm", locale)

    var selectedDate by mutableStateOf(EventTimes.today())
        private set
    var visibleMonth by mutableStateOf(YearMonth.now())
        private set
    var view by mutableStateOf(CalendarView.Days)
        private set
    var selectedEvents by mutableStateOf<List<Event>>(emptyList())
        private set
    var dayCounts by mutableStateOf<Map<LocalDate, Int>>(emptyMap())
        private set
    var syncState by mutableStateOf(syncEngine.state.value)
        private set
    var syncConfig by mutableStateOf(preferences.read())
        private set
    var editor by mutableStateOf<EventFormValues?>(null)
        private set
    var syncOpen by mutableStateOf(false)
        private set

    /** Whether the window hides when the user clicks outside of it. */
    var closeOnClickOutside by mutableStateOf(windowPreferences.read().closeOnClickOutside)
        private set

    /**
     * Sunday-first 6x7 grid of the visible month. The leading and trailing
     * cells carry the real dates of the adjacent months, so every week is full.
     */
    val gridDays: List<LocalDate>
        get() = monthGridDays(visibleMonth)

    /** Start of the decade shown by the "Anos" tab. */
    val decadeStart: Int
        get() = (visibleMonth.year / 10) * 10

    /** Label at the top of the calendar, changing with the current [view]. */
    val calendarLabel: String
        get() = when (view) {
            CalendarView.Days -> capitalize(visibleMonth.atDay(1).format(monthFormat))
            CalendarView.Months -> visibleMonth.year.toString()
            CalendarView.Years -> "$decadeStart – ${decadeStart + 9}"
        }

    val selectedDayLabel: String
        get() = capitalize(selectedDate.format(dayFormat))

    val syncLine: String
        get() {
            val state = syncState
            if (!state.configured) return Strings.notConfigured
            if (state.running) return Strings.syncing
            when (state.error) {
                SyncErrorKind.UNAUTHORIZED -> return Strings.unauthorized
                null -> Unit
                else -> return Strings.offline
            }
            if (state.lastSyncAt <= 0L) return Strings.neverSynced
            val time = Instant.ofEpochMilli(state.lastSyncAt)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
                .format(clockFormat)
            return "Sincronizado às $time"
        }

    init {
        scope.launch {
            syncEngine.state.collect { updated ->
                syncState = updated
                if (!updated.running) refresh()
            }
        }
        // First paint does not wait for SQLite: the calendar shows the month
        // immediately and the dots appear when the query comes back.
        refresh()
    }

    /**
     * Re-reads the visible grid off the UI thread. A new request cancels the
     * previous one, so navigating months quickly never applies stale data on
     * top of newer data.
     */
    private fun refresh() {
        loadJob?.cancel()
        loadJob = scope.launch {
            val days = gridDays
            val date = selectedDate
            val loaded = withContext(Dispatchers.IO) {
                val events = repository.eventsForDate(date)
                val gridEvents = repository.eventsBetween(
                    EventTimes.startOfDay(days.first()),
                    EventTimes.endOfDay(days.last())
                )
                val counts = days.distinct().associateWith { day ->
                    gridEvents.count { EventTimes.isOn(it, day) }
                }
                events to counts
            }
            selectedEvents = loaded.first
            dayCounts = loaded.second
            onDataChanged()
        }
    }

    fun select(date: LocalDate) {
        selectedDate = date
        if (YearMonth.from(date) != visibleMonth) visibleMonth = YearMonth.from(date)
        refresh()
    }

    /** Switches the calendar page without touching the selected day. */
    fun showView(updated: CalendarView) {
        view = updated
    }

    /** Moves one step back in whatever the current page shows. */
    fun previousPeriod() = shiftPeriod(-1)

    fun nextPeriod() = shiftPeriod(1)

    private fun shiftPeriod(direction: Int) {
        visibleMonth = when (view) {
            CalendarView.Days -> visibleMonth.plusMonths(direction.toLong())
            CalendarView.Months -> visibleMonth.plusYears(direction.toLong())
            CalendarView.Years -> visibleMonth.plusYears(direction * 10L)
        }
        refresh()
    }

    fun selectMonth(month: YearMonth) {
        visibleMonth = month
        view = CalendarView.Days
        refresh()
    }

    fun selectYear(year: Int) {
        visibleMonth = visibleMonth.withYear(year)
        view = CalendarView.Months
        refresh()
    }

    fun goToday() {
        view = CalendarView.Days
        select(EventTimes.today())
    }

    fun newEvent() {
        val now = LocalTime.now()
        val date = selectedDate
        val start = if (date == EventTimes.today()) nextHalfHour(now) else LocalTime.of(9, 0)
        editor = EventFormValues(
            dateText = date.format(EventTimes.dateFormat),
            startText = start.format(clockFormat),
            endText = start.plusHours(1).format(clockFormat)
        )
    }

    fun edit(event: Event) {
        editor = EventForm.valuesFrom(event)
    }

    fun dismissEditor() {
        editor = null
    }

    fun openSyncSettings() {
        syncConfig = preferences.read()
        syncOpen = true
    }

    fun closeSyncSettings() {
        syncOpen = false
    }

    /** Persisted right away: the next click outside already respects it. */
    fun updateCloseOnClickOutside(enabled: Boolean) {
        closeOnClickOutside = windowPreferences.update { it.copy(closeOnClickOutside = enabled) }
            .closeOnClickOutside
    }

    /** Returns a message for the editor to show, or `null` when it was saved. */
    fun save(values: EventFormValues): String? = when (val result = EventForm.build(values)) {
        is EventForm.Result.Invalid -> result.message
        is EventForm.Result.Ok -> {
            val event = repository.save(result.draft)
            editor = null
            selectedDate = EventTimes.toLocalDate(event.startTime)
            visibleMonth = YearMonth.from(selectedDate)
            refresh()
            syncEngine.notifyLocalChange()
            null
        }
    }

    fun delete(uuid: String) {
        val event = repository.find(uuid) ?: return
        repository.delete(event)
        editor = null
        refresh()
        syncEngine.notifyLocalChange()
    }

    /** Returns a message for the dialog to show, or `null` when it was saved. */
    fun saveSyncSettings(url: String, token: String, deviceName: String): String? {
        val cleanUrl = url.trim()
        val cleanToken = token.trim()
        if (cleanUrl.isEmpty()) return Strings.errorServerUrl
        if (cleanToken.isEmpty()) return Strings.errorToken

        val previous = preferences.read()
        val sameServer = previous.serverUrl.trim() == cleanUrl && previous.token == cleanToken
        val updated: SyncConfig = preferences.update {
            it.copy(
                serverUrl = cleanUrl,
                token = cleanToken,
                deviceName = deviceName.trim().ifBlank { it.deviceName },
                // A different server means the old cursor points into another dataset.
                cursor = if (sameServer) it.cursor else 0L,
                lastSyncAt = if (sameServer) it.lastSyncAt else 0L
            )
        }
        syncConfig = updated
        syncOpen = false
        scope.launch { syncEngine.syncNow() }
        return null
    }

    fun disconnect() {
        syncConfig = preferences.update {
            it.copy(serverUrl = "", token = "", cursor = 0L, lastSyncAt = 0L)
        }
        syncState = SyncUiState(configured = false)
        syncOpen = false
    }

    fun testConnection(url: String, token: String, onResult: (String) -> Unit) {
        scope.launch {
            val result = syncEngine.testConnection(url, token)
            onResult(
                result.fold(
                    onSuccess = { Strings.testOk },
                    onFailure = { error ->
                        if (error is SyncException && error.kind == SyncErrorKind.UNAUTHORIZED) {
                            Strings.unauthorized
                        } else {
                            "${Strings.syncError}: ${error.message ?: "?"}"
                        }
                    }
                )
            )
        }
    }

    fun syncNow() {
        scope.launch { syncEngine.syncNow() }
    }

    /** Periodic call from the window: refreshes the date and the status file. */
    fun tick() {
        refresh()
    }

    private fun capitalize(text: String): String =
        text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

    private fun nextHalfHour(time: LocalTime): LocalTime {
        val add = 30 - (time.minute % 30)
        val rounded = time.plusMinutes(add.toLong()).withSecond(0).withNano(0)
        return if (rounded.isBefore(time)) LocalTime.of(9, 0) else rounded
    }
}

/**
 * The 42 days drawn for [month], Sunday first. A month that fits in five weeks
 * is pushed down one week, so the calendar always shows six rows — the same
 * grid the Plasma calendar draws.
 */
internal fun monthGridDays(month: YearMonth): List<LocalDate> {
    val first = month.atDay(1)
    // Sunday is 7 in java.time; `value % 7` turns it into a 0 offset.
    val leading = first.dayOfWeek.value % 7
    val padding = if (leading + month.lengthOfMonth() <= 35) 7 else 0
    val start = first.minusDays((leading + padding).toLong())
    return (0 until 42).map { start.plusDays(it.toLong()) }
}
