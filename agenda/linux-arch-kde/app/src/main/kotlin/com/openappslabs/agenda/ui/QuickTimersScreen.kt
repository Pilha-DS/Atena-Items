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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openappslabs.agenda.data.model.QuickTimer
import com.openappslabs.agenda.data.model.QuickTimerTimes
import com.openappslabs.agenda.ui.i18n.Strings
import kotlinx.coroutines.delay

/**
 * The "assuntos rápidos" page: a name and a short duration create a countdown,
 * like the phone's clock timer. The list is the running timers, and the
 * remaining time is derived from `endTime` on every tick — not stored.
 */
@Composable
fun QuickTimersScreen(state: AgendaState, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            state.updateClock()
        }
    }

    Column(modifier.fillMaxSize()) {
        QuickTimersHeader(state)
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
        QuickTimerForm(state)
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))

        if (state.timers.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = Strings.timerEmpty,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = Strings.timerEmptyHint,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp)
            ) {
                items(state.timers, key = { it.uuid }) { timer ->
                    QuickTimerRow(
                        timer = timer,
                        now = state.nowMillis,
                        onRemove = { state.removeTimer(timer.uuid) }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 18.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickTimersHeader(state: AgendaState) {
    val hasFinished = state.timers.any { QuickTimerTimes.isExpired(it, state.nowMillis) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = Strings.quickTimers,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (hasFinished) {
            LinkText(Strings.timerClearFinished, onClick = state::clearFinishedTimers)
        }
        Spacer(Modifier.width(4.dp))
        LinkText(Strings.calendarScreen, onClick = state::showCalendar)
    }
}

@Composable
private fun QuickTimerForm(state: AgendaState) {
    var title by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val result = state.addTimer(title, duration)
        error = result
        if (result == null) {
            title = ""
            duration = ""
        }
    }

    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 8.dp)) {
        Text(
            text = Strings.newTimer,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = title,
            onValueChange = { title = it; error = null },
            label = { Text(Strings.timerTitle) },
            placeholder = { Text(Strings.timerTitleHint) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = duration,
                onValueChange = { duration = it; error = null },
                label = { Text(Strings.timerDuration) },
                placeholder = { Text(Strings.timerDurationHint) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = ::submit) {
                Text(Strings.timerStart)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            QuickTimerTimes.presets.forEach { minutes ->
                PresetChip(
                    label = if (minutes < 60) "$minutes min" else "${minutes / 60} h",
                    onClick = { duration = minutes.toString(); error = null }
                )
            }
        }
        error?.let { message ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun QuickTimerRow(timer: QuickTimer, now: Long, onRemove: () -> Unit) {
    val expired = QuickTimerTimes.isExpired(timer, now)
    val accent = if (expired) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.65f)
    } else {
        MaterialTheme.colorScheme.primary
    }
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = timer.title,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = Strings.timerEndsAt(QuickTimerTimes.endLabel(timer.endTime)),
                fontSize = 11.sp,
                color = muted,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (expired) {
                Strings.timerFinished
            } else {
                QuickTimerTimes.remainingLabel(timer.endTime, now)
            },
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = accent,
            maxLines = 1
        )
        TextButton(onClick = onRemove) {
            Text(Strings.cancel, fontSize = 11.sp, color = muted)
        }
    }
}

@Composable
private fun PresetChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
    }
}

@Composable
private fun LinkText(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
