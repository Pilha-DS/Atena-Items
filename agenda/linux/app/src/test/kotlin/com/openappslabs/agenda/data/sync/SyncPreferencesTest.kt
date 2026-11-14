/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Agenda
 *
 * Agenda is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.agenda.data.sync

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncPreferencesTest {

    private lateinit var file: java.nio.file.Path
    private lateinit var preferences: SyncPreferences

    @BeforeTest
    fun setUp() {
        file = Files.createTempFile("agenda-config", ".json")
        Files.deleteIfExists(file)
        preferences = SyncPreferences(file) { 5_000L }
    }

    @AfterTest
    fun tearDown() {
        Files.deleteIfExists(file)
    }

    @Test
    fun starts_unconfigured_with_a_stable_device_identity() {
        val first = preferences.read()
        assertTrue(!first.isConfigured)
        assertTrue(first.deviceId.isNotBlank())
        assertTrue(first.deviceName.isNotBlank())

        preferences.save(first)
        assertEquals(first.deviceId, preferences.read().deviceId)
    }

    @Test
    fun round_trips_server_and_token() {
        preferences.update {
            it.copy(serverUrl = "http://192.168.0.10:8080", token = "abc")
        }

        val read = preferences.read()
        assertEquals("http://192.168.0.10:8080", read.serverUrl)
        assertEquals("abc", read.token)
        assertTrue(read.isConfigured)
    }

    @Test
    fun stores_the_cursor_after_a_sync() {
        preferences.saveCursor(cursor = 42, syncedAt = 9_000)

        val read = preferences.read()
        assertEquals(42L, read.cursor)
        assertEquals(9_000L, read.lastSyncAt)
    }

    @Test
    fun corrupt_file_falls_back_to_defaults() {
        Files.writeString(file, "{ not json")

        val read = preferences.read()
        assertTrue(!read.isConfigured)
        assertTrue(read.deviceId.isNotBlank())
    }
}
