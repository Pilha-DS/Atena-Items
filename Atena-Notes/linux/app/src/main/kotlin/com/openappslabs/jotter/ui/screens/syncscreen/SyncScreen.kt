/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Jotter is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Jotter.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.jotter.ui.screens.syncscreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openappslabs.jotter.data.sync.SyncErrorKind
import com.openappslabs.jotter.di.AppGraph
import com.openappslabs.jotter.ui.components.BackButton
import com.openappslabs.jotter.ui.components.ConfirmationDialog
import com.openappslabs.jotter.ui.i18n.AppStrings
import com.openappslabs.jotter.ui.i18n.LocalStrings
import com.openappslabs.jotter.ui.screens.settingsscreen.components.SettingsGroup
import com.openappslabs.jotter.ui.screens.settingsscreen.components.SettingsItemArrow
import com.openappslabs.jotter.ui.theme.rememberJotterHaptics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onBackClick: () -> Unit,
    viewModel: SyncScreenViewModel = viewModel {
        SyncScreenViewModel(AppGraph.syncEngine, AppGraph.syncPreferencesRepository)
    }
) {
    val strings = LocalStrings.current
    val haptics = rememberJotterHaptics()
    val uiState by viewModel.uiState.collectAsState()
    val config = uiState.config
    val sync = uiState.sync

    var serverUrl by remember(config.serverUrl) { mutableStateOf(config.serverUrl) }
    var token by remember(config.token) { mutableStateOf(config.token) }
    var tokenVisible by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }

    val canConnect = serverUrl.isNotBlank() && token.isNotBlank() && !uiState.checking
    val censor = remember { PasswordVisualTransformation() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = strings.sync,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = { BackButton(onClick = onBackClick) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (uiState.showForm) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = strings.syncLocalFirst,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedTextField(
                                value = serverUrl,
                                onValueChange = { serverUrl = it },
                                label = { Text(strings.syncServerUrl) },
                                placeholder = { Text(strings.syncServerUrlHint) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = token,
                                onValueChange = { token = it },
                                label = { Text(strings.syncToken) },
                                supportingText = { Text(strings.syncTokenHint) },
                                singleLine = true,
                                visualTransformation = if (tokenVisible) {
                                    VisualTransformation.None
                                } else {
                                    censor
                                },
                                trailingIcon = {
                                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                        Icon(
                                            imageVector = if (tokenVisible) {
                                                Icons.Default.VisibilityOff
                                            } else {
                                                Icons.Default.Visibility
                                            },
                                            contentDescription = null
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            uiState.probeError?.let { error ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .size(16.dp)
                                    )
                                    Text(
                                        text = syncErrorMessage(error, strings),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(start = 6.dp)
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    haptics.click()
                                    viewModel.connect(serverUrl, token)
                                },
                                enabled = canConnect,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (uiState.checking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Text(strings.syncConnect)
                                }
                            }

                            if (uiState.checking) {
                                Text(
                                    text = strings.syncChecking,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            if (config.isConfigured) {
                                TextButton(
                                    onClick = {
                                        haptics.tick()
                                        serverUrl = config.serverUrl
                                        token = config.token
                                        viewModel.cancelEdit()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(strings.cancel)
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    SettingsGroup {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = strings.syncServerUrl,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = config.serverUrl,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${strings.syncDevice}: " +
                                    config.deviceName.ifBlank { "-" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        SettingsItemArrow(
                            icon = Icons.Default.Edit,
                            title = strings.syncDeviceName,
                            subtitle = strings.syncDeviceNameHint,
                            onClick = {
                                haptics.click()
                                viewModel.askToRenameDevice()
                            }
                        )

                        SettingsItemArrow(
                            icon = Icons.Default.Sync,
                            title = strings.syncEdit,
                            subtitle = config.serverUrl,
                            onClick = {
                                haptics.click()
                                viewModel.editConnection()
                            }
                        )

                        SettingsItemArrow(
                            icon = Icons.Default.CloudOff,
                            title = strings.syncDisconnect,
                            subtitle = null,
                            onClick = { showDisconnectDialog = true },
                            isDestructive = true
                        )
                    }
                }

                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = strings.syncLastSync,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = lastSyncLabel(sync.lastSyncAt, strings.syncNever),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )

                            if (sync.lastSyncAt > 0L && !sync.running) {
                                Text(
                                    text = strings.syncResult(sync.pushed, sync.pulled),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (sync.running) {
                                Text(
                                    text = strings.syncRunning,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            sync.error?.let { error ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .size(16.dp)
                                    )
                                    Text(
                                        text = syncErrorMessage(error, strings),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(start = 6.dp)
                                    )
                                }
                            }

                            if (sync.hasClockSkew) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .size(16.dp)
                                    )
                                    Text(
                                        text = strings.syncClockSkew(formatSkew(sync.clockSkewMs)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.padding(start = 6.dp)
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    haptics.click()
                                    viewModel.syncNow()
                                },
                                enabled = !sync.running,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = strings.syncNow,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Step two of the connection, and the "rename device" action, share a dialog.
    uiState.askingName?.let { suggestion ->
        DeviceNameDialog(
            title = if (uiState.showForm) strings.syncDeviceNameTitle else strings.syncDeviceName,
            initialValue = suggestion,
            hint = strings.syncDeviceNameHint,
            confirmText = if (uiState.showForm) strings.syncConnect else strings.save,
            onDismiss = {
                if (uiState.showForm) viewModel.cancelDeviceName() else viewModel.cancelEdit()
            },
            onConfirm = { name ->
                if (uiState.showForm) {
                    viewModel.confirmDeviceName(name)
                } else {
                    viewModel.renameDevice(name)
                }
            }
        )
    }

    if (showDisconnectDialog) {
        ConfirmationDialog(
            icon = Icons.Default.CloudOff,
            title = strings.syncDisconnectTitle,
            message = strings.syncDisconnectMessage,
            confirmText = strings.syncDisconnect,
            isDestructive = true,
            onDismiss = { showDisconnectDialog = false },
            onConfirm = {
                showDisconnectDialog = false
                viewModel.disconnect()
                serverUrl = ""
                token = ""
            }
        )
    }
}

@Composable
private fun DeviceNameDialog(
    title: String,
    initialValue: String,
    hint: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(initialValue) { mutableStateOf(initialValue) }
    val haptics = rememberJotterHaptics()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(hint) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.click()
                    onConfirm(name)
                },
                enabled = name.isNotBlank()
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(LocalStrings.current.cancel) }
        }
    )
}

private fun lastSyncLabel(lastSyncAt: Long, never: String): String =
    if (lastSyncAt <= 0L) {
        never
    } else {
        SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(lastSyncAt))
    }

internal fun syncErrorMessage(error: SyncErrorKind, strings: AppStrings): String = when (error) {
    SyncErrorKind.NOT_CONFIGURED -> strings.syncErrorNotConfigured
    SyncErrorKind.UNAUTHORIZED -> strings.syncErrorUnauthorized
    SyncErrorKind.NETWORK -> strings.syncErrorNetwork
    SyncErrorKind.SERVER -> strings.syncErrorServer
    SyncErrorKind.INVALID_RESPONSE -> strings.syncErrorResponse
}

/** "12 min", "3 h": a rough magnitude is enough to spot a wrong clock. */
private fun formatSkew(skewMs: Long): String {
    val minutes = abs(skewMs) / 60_000L
    return when {
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h"
    }
}
