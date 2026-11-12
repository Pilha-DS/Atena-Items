/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.jotter.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.openappslabs.jotter.platform.Xdg
import okio.Path.Companion.toOkioPath

object DataStoreFactory {

    fun create(): DataStore<Preferences> {
        Xdg.ensureDirectories()
        return PreferenceDataStoreFactory.createWithPath(
            produceFile = { Xdg.preferencesFile.toOkioPath() }
        )
    }
}
