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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openappslabs.agenda.data.model.Event
import com.openappslabs.agenda.data.model.EventTimes
import com.openappslabs.agenda.ui.i18n.Strings
import com.openappslabs.agenda.ui.theme.AgendaTheme
import java.nio.file.Path

/**
 * The whole window: the Plasma-style calendar, the selected day's events and
 * the sync footer. Everything it shows comes from [AgendaState]; nothing here
 * touches the database.
 */
@Composable
fun AgendaApp(state: AgendaState, kdeGlobals: Path) {
    AgendaTheme(kdeGlobals) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.fillMaxSize()) {
                CalendarCard(state)
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                DaySection(state, Modifier.weight(1f))
                Footer(state)
            }

            state.editor?.let { initial ->
                EventEditorDialog(
                    initial = initial,
                    onCancel = state::dismissEditor,
                    onSave = state::save,
                    onDelete = initial.uuid?.let { uuid -> { state.delete(uuid) } }
                )
            }

            if (state.syncOpen) {
                SyncSettingsDialog(
                    config = state.syncConfig,
                    closeOnClickOutside = state.closeOnClickOutside,
                    onToggleCloseOnClickOutside = state::updateCloseOnClickOutside,
                    onClose = state::closeSyncSettings,
                    onSave = state::saveSyncSettings,
                    onDisconnect = state::disconnect,
                    onTest = state::testConnection
                )
            }
        }
    }
}

@Composable
private fun DaySection(state: AgendaState, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = state.selectedDayLabel,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            LinkButton(Strings.newEvent, onClick = state::newEvent)
        }

        if (state.selectedEvents.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = Strings.noEvents,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = Strings.noEventsHint,
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
                items(state.selectedEvents, key = { it.uuid }) { event ->
                    EventRow(event, onClick = { state.edit(event) })
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
private fun EventRow(event: Event, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // A thin accent bar reads as "this is on the calendar" without a card.
        Box(
            Modifier
                .width(3.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (event.allDay) primary.copy(alpha = 0.45f) else primary)
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.width(62.dp)) {
            Text(
                text = EventTimes.startLabel(event),
                fontSize = 12.sp,
                color = if (event.allDay) muted else primary,
                maxLines = 1
            )
            if (!event.allDay) {
                Text(
                    text = EventTimes.endLabel(event),
                    fontSize = 11.sp,
                    color = muted,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = event.title,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            EventTimes.dayRangeLabel(event)?.let { range ->
                Text(text = range, fontSize = 11.sp, color = muted, maxLines = 1)
            }
            if (event.location.isNotBlank()) {
                Text(
                    text = event.location,
                    fontSize = 11.sp,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LinkButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun Footer(state: AgendaState) {
    val statusColor = when {
        state.syncState.running -> MaterialTheme.colorScheme.primary
        state.syncState.error != null -> MaterialTheme.colorScheme.error
        state.syncState.configured -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    }

    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(start = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor))
            Spacer(Modifier.width(8.dp))
            Text(
                text = state.syncLine,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            FooterButton(Strings.settings, onClick = state::openSyncSettings)
            FooterButton(
                label = if (state.syncState.running) Strings.syncing else Strings.sync,
                onClick = state::syncNow,
                enabled = state.syncState.configured && !state.syncState.running
            )
        }
    }
}

@Composable
private fun FooterButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val color = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (enabled) color.copy(alpha = 0.85f) else color.copy(alpha = 0.35f)
        )
    }
}
