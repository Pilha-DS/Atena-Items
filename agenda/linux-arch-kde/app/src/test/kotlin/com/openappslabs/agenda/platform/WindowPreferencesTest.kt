/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Agenda
 *
 * Agenda is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.agenda.platform

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowPreferencesTest {

    private lateinit var file: Path
    private lateinit var preferences: WindowPreferences

    @BeforeTest
    fun setUp() {
        file = Files.createTempFile("agenda-window", ".json")
        Files.deleteIfExists(file)
        preferences = WindowPreferences(file)
    }

    @AfterTest
    fun tearDown() {
        Files.deleteIfExists(file)
    }

    @Test
    fun defaults_to_closing_when_clicking_outside() {
        assertTrue(preferences.read().closeOnClickOutside)
    }

    @Test
    fun remembers_when_autohide_is_turned_off() {
        preferences.update { it.copy(closeOnClickOutside = false) }
        assertTrue(!preferences.read().closeOnClickOutside)

        preferences.update { it.copy(closeOnClickOutside = true) }
        assertTrue(preferences.read().closeOnClickOutside)
    }

    @Test
    fun corrupt_file_falls_back_to_the_default() {
        Files.writeString(file, "{ not json")
        assertEquals(WindowConfig(), preferences.read())
    }

    @Test
    fun keeps_unknown_keys_from_newer_versions_readable() {
        Files.writeString(file, """{"closeOnClickOutside":false,"futureOption":7}""")
        assertTrue(!preferences.read().closeOnClickOutside)
    }
}
