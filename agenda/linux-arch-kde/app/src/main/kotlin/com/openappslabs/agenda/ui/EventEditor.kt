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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.openappslabs.agenda.ui.i18n.Strings

/**
 * Modal editor. It owns only the text being typed; validation and persistence
 * are [onSave]'s job, and a returned message is shown right above the buttons.
 */
@Composable
fun EventEditorDialog(
    initial: EventFormValues,
    onCancel: () -> Unit,
    onSave: (EventFormValues) -> String?,
    onDelete: (() -> Unit)?
) {
    var title by remember(initial) { mutableStateOf(initial.title) }
    var dateText by remember(initial) { mutableStateOf(initial.dateText) }
    var startText by remember(initial) { mutableStateOf(initial.startText) }
    var endText by remember(initial) { mutableStateOf(initial.endText) }
    var allDay by remember(initial) { mutableStateOf(initial.allDay) }
    var location by remember(initial) { mutableStateOf(initial.location) }
    var description by remember(initial) { mutableStateOf(initial.description) }
    var error by remember(initial) { mutableStateOf<String?>(null) }

    fun currentValues() = EventFormValues(
        uuid = initial.uuid,
        title = title,
        dateText = dateText,
        startText = startText,
        endText = endText,
        allDay = allDay,
        location = location,
        description = description
    )

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.width(404.dp).padding(20.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (initial.uuid == null) Strings.newEvent else Strings.editEvent,
                    style = MaterialTheme.typography.titleMedium
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; error = null },
                    label = { Text(Strings.title) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = { dateText = it; error = null },
                        label = { Text(Strings.date) },
                        placeholder = { Text(Strings.dateHint) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allDay, onCheckedChange = { allDay = it; error = null })
                    Text(Strings.allDay, style = MaterialTheme.typography.bodyMedium)
                }

                if (!allDay) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = startText,
                            onValueChange = { startText = it; error = null },
                            label = { Text(Strings.start) },
                            placeholder = { Text(Strings.timeHint) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = endText,
                            onValueChange = { endText = it; error = null },
                            label = { Text(Strings.end) },
                            placeholder = { Text(Strings.timeHint) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text(Strings.location) },
                    placeholder = { Text(Strings.locationHint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(Strings.description) },
                    placeholder = { Text(Strings.descriptionHint) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onDelete != null) {
                        TextButton(onClick = onDelete) {
                            Text(Strings.delete, color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Box(Modifier.width(1.dp))
                    }
                    Row {
                        TextButton(onClick = onCancel) { Text(Strings.cancel) }
                        Button(
                            onClick = { error = onSave(currentValues()) },
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            Text(Strings.save)
                        }
                    }
                }
            }
        }
    }
}
