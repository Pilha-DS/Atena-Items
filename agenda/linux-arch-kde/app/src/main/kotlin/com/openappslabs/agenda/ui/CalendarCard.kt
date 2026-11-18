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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openappslabs.agenda.data.model.EventTimes
import com.openappslabs.agenda.ui.i18n.Strings
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private val PtBr: Locale = Locale.forLanguageTag("pt-BR")

/** Sunday-first, exactly like the Plasma calendar. */
private val Weekdays = listOf("dom.", "seg.", "ter.", "qua.", "qui.", "sex.", "sáb.")

private val CellHeight = 34.dp
private val CellShape = RoundedCornerShape(3.dp)

/**
 * Month grid shaped after the Plasma calendar: a quiet header with the month
 * name and `‹ Hoje ›`, three underlined tabs and the day matrix. Selecting a
 * day only reports through [AgendaState]; the shape of the data is not this
 * file's business.
 */
@Composable
fun CalendarCard(state: AgendaState) {
    Column(Modifier.fillMaxWidth()) {
        CalendarTitle(state)
        ViewTabs(state)
        when (state.view) {
            CalendarView.Days -> DaysGrid(state)
            CalendarView.Months -> MonthsGrid(state)
            CalendarView.Years -> YearsGrid(state)
        }
    }
}

@Composable
private fun CalendarTitle(state: AgendaState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = state.calendarLabel,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        RefreshButton(
            running = state.syncState.running,
            onClick = {
                // Not configured yet: the refresh is what leads to the settings.
                if (state.syncState.configured) state.syncNow() else state.openSyncSettings()
            }
        )
        ArrowButton("‹", Strings.previousPeriod, state::previousPeriod)
        PlainButton(Strings.today, state::goToday)
        ArrowButton("›", Strings.nextPeriod, state::nextPeriod)
    }
}

@Composable
private fun ViewTabs(state: AgendaState) {
    val tabs = listOf(
        CalendarView.Days to Strings.days,
        CalendarView.Months to Strings.months,
        CalendarView.Years to Strings.years
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        tabs.forEach { (view, label) ->
            val active = state.view == view
            Column(
                modifier = Modifier.weight(1f).clickable { state.showView(view) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(9.dp))
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (active) 1f else 0.72f)
                )
                Spacer(Modifier.height(7.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary else Color.Transparent
                        )
                )
            }
        }
    }
}

@Composable
private fun DaysGrid(state: AgendaState) {
    val today = EventTimes.today()
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth()) {
            Weekdays.forEach { label ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    )
                }
            }
        }

        Spacer(Modifier.height(3.dp))

        state.gridDays.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    DayCell(
                        modifier = Modifier.weight(1f),
                        date = date,
                        inMonth = YearMonth.from(date) == state.visibleMonth,
                        selected = date == state.selectedDate,
                        today = date == today,
                        count = state.dayCounts[date] ?: 0,
                        onClick = { state.select(date) }
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun DayCell(
    modifier: Modifier,
    date: LocalDate,
    inMonth: Boolean,
    selected: Boolean,
    today: Boolean,
    count: Int,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val foreground = MaterialTheme.colorScheme.onSurface
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val textColor = when {
        selected -> foreground
        !inMonth -> foreground.copy(alpha = 0.38f)
        today -> primary
        else -> foreground
    }

    Box(modifier.height(CellHeight).padding(1.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CellShape)
                .background(
                    when {
                        selected -> primary.copy(alpha = 0.30f)
                        hovered -> foreground.copy(alpha = 0.08f)
                        else -> Color.Transparent
                    }
                )
                .then(
                    if (selected) Modifier.border(1.5.dp, primary, CellShape) else Modifier
                )
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                fontSize = 13.sp,
                fontWeight = if (selected || today) FontWeight.Medium else FontWeight.Normal,
                color = textColor
            )
            if (count > 0) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(if (selected) primary else primary.copy(alpha = 0.75f))
                )
            }
        }
    }
}

@Composable
private fun MonthsGrid(state: AgendaState) {
    val months = Month.entries
    PickerGrid(
        labels = months.map {
            it.getDisplayName(TextStyle.FULL, PtBr).replaceFirstChar { c -> c.titlecase(PtBr) }
        },
        selectedIndex = state.visibleMonth.monthValue - 1,
        onSelect = { index -> state.selectMonth(state.visibleMonth.withMonth(index + 1)) }
    )
}

@Composable
private fun YearsGrid(state: AgendaState) {
    // One year on each side of the decade, like the Plasma calendar, so the
    // edges of the grid are still reachable.
    val years = (state.decadeStart - 1..state.decadeStart + 10).toList()
    PickerGrid(
        labels = years.map(Int::toString),
        selectedIndex = years.indexOf(state.visibleMonth.year),
        onSelect = { index -> state.selectYear(years[index]) }
    )
}

/** Four columns, used by the months and years pages. */
@Composable
private fun PickerGrid(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp)) {
        labels.withIndex().chunked(4).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { (index, label) ->
                    PickerCell(
                        label = label,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerCell(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val foreground = MaterialTheme.colorScheme.onSurface
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box(modifier.height(CellHeight).padding(1.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CellShape)
                .background(
                    when {
                        selected -> primary.copy(alpha = 0.30f)
                        hovered -> foreground.copy(alpha = 0.08f)
                        else -> Color.Transparent
                    }
                )
                .then(
                    if (selected) Modifier.border(1.5.dp, primary, CellShape) else Modifier
                )
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = foreground
            )
        }
    }
}

@Composable
private fun ArrowButton(glyph: String, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(26.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = glyph,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
        )
    }
}

@Composable
private fun RefreshButton(running: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(26.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "↻",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (running) 0.45f else 0.9f)
        )
    }
}

@Composable
private fun PlainButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
