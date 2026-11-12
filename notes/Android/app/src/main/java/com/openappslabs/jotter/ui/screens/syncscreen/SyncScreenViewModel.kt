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

import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openappslabs.jotter.data.sync.SyncConfig
import com.openappslabs.jotter.data.sync.SyncEngine
import com.openappslabs.jotter.data.sync.SyncErrorKind
import com.openappslabs.jotter.data.sync.SyncException
import com.openappslabs.jotter.data.sync.SyncPreferencesRepository
import com.openappslabs.jotter.data.sync.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class SyncUiState(
    val config: SyncConfig = SyncConfig(),
    val sync: SyncState = SyncState(),
    /** Talking to the server to validate what was typed. */
    val checking: Boolean = false,
    /** Non-null while the "name this device" step is open, holding the suggestion. */
    val askingName: String? = null,
    /** Error from the validation step, shown right below the fields. */
    val probeError: SyncErrorKind? = null,
    /** The form is visible: nothing configured yet, or the user asked to change it. */
    val editing: Boolean = false
) {
    /** Only two fields matter on a fresh install: server and token. */
    val showForm: Boolean get() = editing || !config.isConfigured
}

@HiltViewModel
class SyncScreenViewModel @Inject constructor(
    private val engine: SyncEngine,
    private val preferences: SyncPreferencesRepository
) : ViewModel() {

    private data class Local(
        val checking: Boolean = false,
        val askingName: String? = null,
        val probeError: SyncErrorKind? = null,
        val editing: Boolean = false,
        val pendingUrl: String = "",
        val pendingToken: String = ""
    )

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<SyncUiState> = combine(
        preferences.configFlow,
        engine.state,
        local
    ) { config, sync, state ->
        SyncUiState(
            config = config,
            sync = sync,
            checking = state.checking,
            askingName = state.askingName,
            probeError = state.probeError,
            editing = state.editing
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SyncUiState()
    )

    init {
        engine.start()
    }

    /**
     * Step one: the user typed a server and a token. Validate them against the
     * server first; only then ask for a device name, so a wrong URL or token
     * never gets saved.
     */
    fun connect(serverUrl: String, token: String) {
        if (serverUrl.isBlank() || token.isBlank()) return
        viewModelScope.launch {
            local.value = local.value.copy(
                checking = true,
                probeError = null,
                pendingUrl = serverUrl.trim(),
                pendingToken = token.trim()
            )
            val result = engine.testConnection(serverUrl, token)
            local.value = local.value.copy(checking = false)
            result.onSuccess {
                local.value = local.value.copy(askingName = suggestedDeviceName())
            }.onFailure { error ->
                local.value = local.value.copy(probeError = kindOf(error))
            }
        }
    }

    /** Step two: keep the name (or the suggestion) and sync immediately. */
    fun confirmDeviceName(name: String) {
        val pending = local.value
        val deviceName = name.trim().ifBlank { pending.askingName ?: suggestedDeviceName() }
        viewModelScope.launch {
            preferences.saveConnection(
                serverUrl = pending.pendingUrl,
                token = pending.pendingToken,
                deviceName = deviceName
            )
            local.value = Local()
            engine.syncNow()
        }
    }

    fun cancelDeviceName() {
        local.value = Local()
    }

    fun syncNow() {
        viewModelScope.launch { engine.syncNow() }
    }

    /** "Change server": shows the two fields again, keeping what is stored. */
    fun editConnection() {
        local.value = Local(editing = true)
        engine.clearError()
    }

    fun cancelEdit() {
        local.value = Local()
    }

    fun renameDevice(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            preferences.saveDeviceName(clean)
            local.value = local.value.copy(askingName = null)
            // Pushing right away teaches the server the new name.
            engine.syncNow()
        }
    }

    fun askToRenameDevice() {
        local.value = local.value.copy(askingName = uiState.value.config.deviceName)
    }

    fun disconnect() {
        viewModelScope.launch {
            preferences.clearConnection()
            engine.clearError()
            local.value = Local(editing = true)
        }
    }

    private fun kindOf(error: Throwable): SyncErrorKind =
        (error as? SyncException)?.kind ?: SyncErrorKind.NETWORK
}

/** The phone model is usually the name the user would pick anyway. */
internal fun suggestedDeviceName(): String {
    val model = Build.MODEL?.trim().orEmpty()
    return model.ifBlank { "Android" }.take(40)
}
