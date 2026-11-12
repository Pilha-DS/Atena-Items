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

package com.openappslabs.jotter.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Opening order of the app.
 *
 * The general note is the one thing that has to show up immediately: it is the
 * text the user came back for, and its query reads a single row. Everything
 * that touches the whole database (the note list, the plasmoid counter, the
 * trash) waits for it instead of competing for the same database executor, so
 * opening Jotter paints the general note first and the rest right after.
 *
 * The gate is one-shot and never closes again: once the app is up, list
 * reloads, category changes and every other query run as usual.
 */
object Startup {

    /** Set once the general note was shown (or the app decided there is none). */
    private val generalNoteReady = MutableStateFlow(false)

    /** Called by the general note window as soon as its text is on screen. */
    fun onGeneralNoteReady() {
        if (!generalNoteReady.value) generalNoteReady.value = true
    }

    /** Suspends until [onGeneralNoteReady] was called. */
    suspend fun awaitGeneralNote() {
        generalNoteReady.first { it }
    }
}
