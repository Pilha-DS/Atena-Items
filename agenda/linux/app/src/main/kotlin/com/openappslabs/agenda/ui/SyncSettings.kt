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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openappslabs.agenda.data.sync.SyncConfig
import com.openappslabs.agenda.ui.i18n.Strings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Hub settings. Connecting is optional: without a server the app is fully
 * usable and everything stays on this machine.
 */
@Composable
fun SyncSettingsDialog(
    config: SyncConfig,
    closeOnClickOutside: Boolean,
    onToggleCloseOnClickOutside: (Boolean) -> Unit,
    onClose: () -> Unit,
    onSave: (String, String, String) -> String?,
    onRefresh: (String, String, String, (String) -> Unit) -> String?,
    onDisconnect: () -> Unit,
    onTest: (String, String, (String) -> Unit) -> Unit
) {
    var url by remember(config) { mutableStateOf(config.serverUrl) }
    var token by remember(config) { mutableStateOf(config.token) }
    var deviceName by remember(config) { mutableStateOf(config.deviceName) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.width(432.dp).padding(20.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(Strings.settingsTitle, style = MaterialTheme.typography.titleMedium)

                Text(
                    text = Strings.windowSection,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        onToggleCloseOnClickOutside(!closeOnClickOutside)
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = closeOnClickOutside,
                        onCheckedChange = onToggleCloseOnClickOutside
                    )
                    Column {
                        Text(Strings.closeOnClickOutside, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = Strings.closeOnClickOutsideHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outline
                )

                Text(Strings.syncTitle, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = Strings.syncHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null; message = null },
                    label = { Text(Strings.serverUrl) },
                    placeholder = { Text(Strings.serverUrlHint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; error = null; message = null },
                    label = { Text(Strings.token) },
                    placeholder = { Text(Strings.tokenHint) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it },
                    label = { Text(Strings.deviceName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        enabled = !syncing && !testing && url.isNotBlank() && token.isNotBlank(),
                        onClick = {
                            syncing = true
                            message = null
                            error = null
                            val failure = onRefresh(url, token, deviceName) { result ->
                                message = result
                                syncing = false
                            }
                            if (failure != null) {
                                error = failure
                                syncing = false
                            }
                        }
                    ) {
                        Text(if (syncing) Strings.syncing else Strings.syncNow)
                    }
                    TextButton(
                        enabled = !testing && !syncing,
                        onClick = {
                            testing = true
                            message = null
                            onTest(url, token) { result ->
                                message = result
                                testing = false
                            }
                        }
                    ) {
                        Text(if (testing) Strings.testing else Strings.testConnection)
                    }
                }

                Text(
                    text = lastSyncLine(config),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                message?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

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
                    if (config.isConfigured) {
                        TextButton(onClick = onDisconnect) {
                            Text(Strings.disconnect, color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Box(Modifier.width(1.dp))
                    }
                    Row {
                        TextButton(onClick = onClose) { Text(Strings.cancel) }
                        Button(
                            onClick = { error = onSave(url, token, deviceName) },
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

private fun lastSyncLine(config: SyncConfig): String {
    if (config.lastSyncAt <= 0L) return Strings.neverSynced
    val formatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")
    val time = Instant.ofEpochMilli(config.lastSyncAt)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
    return "Último sync: $time"
}
