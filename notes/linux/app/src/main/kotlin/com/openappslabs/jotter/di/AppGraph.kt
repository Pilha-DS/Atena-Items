/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.jotter.di

import com.openappslabs.jotter.data.repository.CategoryRepository
import com.openappslabs.jotter.data.repository.CategoryRepositoryImpl
import com.openappslabs.jotter.data.repository.LinksRepository
import com.openappslabs.jotter.data.repository.LinksRepositoryImpl
import com.openappslabs.jotter.data.repository.NotesRepository
import com.openappslabs.jotter.data.repository.NotesRepositoryImpl
import com.openappslabs.jotter.data.repository.UserPreferencesRepository
import com.openappslabs.jotter.data.source.DatabaseFactory
import com.openappslabs.jotter.data.source.JotterDatabase
import com.openappslabs.jotter.data.sync.NetworkMonitor
import com.openappslabs.jotter.data.sync.SyncClient
import com.openappslabs.jotter.data.sync.SyncEngine
import com.openappslabs.jotter.data.sync.SyncPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency graph. Hilt is Android-only, and the desktop app is small
 * enough that a lazy singleton object is clearer than a DI framework.
 */
object AppGraph {

    /**
     * Lives as long as the process. Used by the shared flows and by work that
     * must not depend on a screen being on the back stack.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: JotterDatabase by lazy { DatabaseFactory.create() }

    /**
     * One DataStore for the file: creating a second instance for the same file
     * throws at runtime, and the sync settings live next to the app settings.
     */
    private val dataStore by lazy { DataStoreFactory.create() }

    val userPreferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepository(dataStore)
    }

    val syncPreferencesRepository: SyncPreferencesRepository by lazy {
        SyncPreferencesRepository(dataStore)
    }

    /** Tells the sync loop whether the current connection costs the user money. */
    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor() }

    val syncEngine: SyncEngine by lazy {
        SyncEngine(
            database = database,
            syncDao = database.syncDao(),
            categoryDao = database.categoryDao(),
            linksDao = database.linksDao(),
            preferences = syncPreferencesRepository,
            client = SyncClient(),
            network = networkMonitor,
            scope = appScope
        )
    }

    val notesRepository: NotesRepository by lazy {
        NotesRepositoryImpl(
            noteDao = database.noteDao(),
            categoryDao = database.categoryDao(),
            database = database,
            appScope = appScope
        )
    }

    val categoryRepository: CategoryRepository by lazy {
        CategoryRepositoryImpl(database.categoryDao(), database.noteDao(), database)
    }

    val linksRepository: LinksRepository by lazy {
        LinksRepositoryImpl(linksDao = database.linksDao(), database = database)
    }
}
