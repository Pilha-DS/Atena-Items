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

package com.openappslabs.jotter.data.sync

import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.UUID

/**
 * Everything needed to sync: a server, a token and a name for this device.
 *
 * There is no "auto sync" switch on purpose: once a server is configured the
 * app always pushes local changes and pulls remote ones. Disconnecting is the
 * only way to stop it.
 */
@Immutable
data class SyncConfig(
    val serverUrl: String = "",
    val token: String = "",
    /** Shown on the server's page, so you know which device is which. */
    val deviceName: String = "",
    /** Stable per-install id, so renaming a device keeps its history. */
    val deviceId: String = "",
    /** Last server sequence this device has seen; `0` means "never synced". */
    val cursor: Long = 0L,
    val lastSyncAt: Long = 0L
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()
}

/** Shared with [com.openappslabs.jotter.data.repository.UserPreferencesRepository]. */
object SyncPreferenceKeys {
    val SERVER_URL = stringPreferencesKey("sync_server_url")
    val TOKEN = stringPreferencesKey("sync_token")
    val DEVICE_NAME = stringPreferencesKey("sync_device_name")
    val DEVICE_ID = stringPreferencesKey("sync_device_id")
    val CURSOR = longPreferencesKey("sync_cursor")
    val LAST_SYNC_AT = longPreferencesKey("sync_last_at")
}

/**
 * Sync settings live in the same DataStore as the rest of the settings, but
 * with their own repository so a cursor update every few minutes does not
 * invalidate the whole UI preference object.
 */
class SyncPreferencesRepository(
    private val dataStore: DataStore<Preferences>
) {

    val configFlow: Flow<SyncConfig> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            SyncConfig(
                serverUrl = preferences[SyncPreferenceKeys.SERVER_URL] ?: "",
                token = preferences[SyncPreferenceKeys.TOKEN] ?: "",
                deviceName = preferences[SyncPreferenceKeys.DEVICE_NAME] ?: "",
                deviceId = preferences[SyncPreferenceKeys.DEVICE_ID] ?: "",
                cursor = preferences[SyncPreferenceKeys.CURSOR] ?: 0L,
                lastSyncAt = preferences[SyncPreferenceKeys.LAST_SYNC_AT] ?: 0L
            )
        }
        .distinctUntilChanged()

    suspend fun read(): SyncConfig = configFlow.first()

    /**
     * One save for the whole connection, so a half-configured state (URL
     * without token, or a missing device id) never reaches the sync loop.
     */
    suspend fun saveConnection(serverUrl: String, token: String, deviceName: String) {
        dataStore.edit { preferences ->
            preferences[SyncPreferenceKeys.SERVER_URL] = serverUrl.trim()
            preferences[SyncPreferenceKeys.TOKEN] = token.trim()
            preferences[SyncPreferenceKeys.DEVICE_NAME] = deviceName.trim()
            if (preferences[SyncPreferenceKeys.DEVICE_ID].isNullOrBlank()) {
                preferences[SyncPreferenceKeys.DEVICE_ID] = UUID.randomUUID().toString()
            }
        }
    }

    /** Renaming the device does not disturb the cursor or the notes. */
    suspend fun saveDeviceName(name: String) {
        dataStore.edit { preferences ->
            preferences[SyncPreferenceKeys.DEVICE_NAME] = name.trim()
        }
    }

    suspend fun saveCursor(cursor: Long, syncedAt: Long) {
        dataStore.edit { preferences ->
            preferences[SyncPreferenceKeys.CURSOR] = cursor
            preferences[SyncPreferenceKeys.LAST_SYNC_AT] = syncedAt
        }
    }

    /**
     * Forgets what this device has pulled without touching the credentials.
     * Used when the local database is replaced wholesale (import/clear), so the
     * next sync starts from a full pull again.
     */
    suspend fun resetCursor() {
        dataStore.edit { preferences ->
            preferences.remove(SyncPreferenceKeys.CURSOR)
            preferences.remove(SyncPreferenceKeys.LAST_SYNC_AT)
        }
    }

    /**
     * "Disconnect": forget the server and the token. The device id survives, so
     * reconnecting later is recognized as the same device.
     */
    suspend fun clearConnection() {
        dataStore.edit { preferences ->
            preferences.remove(SyncPreferenceKeys.SERVER_URL)
            preferences.remove(SyncPreferenceKeys.TOKEN)
            preferences.remove(SyncPreferenceKeys.CURSOR)
            preferences.remove(SyncPreferenceKeys.LAST_SYNC_AT)
        }
    }
}
