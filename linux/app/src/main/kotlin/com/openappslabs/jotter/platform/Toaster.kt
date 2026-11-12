/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.jotter.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Tiny replacement for android.widget.Toast. The root composable observes [message]
 * and renders a small floating surface near the bottom of the window.
 */
object Toaster {

    data class Message(val text: String, val id: Long)

    var message by mutableStateOf<Message?>(null)
        private set

    private var nextId = 0L

    fun show(text: String) {
        message = Message(text, nextId++)
    }

    fun dismiss(id: Long) {
        if (message?.id == id) message = null
    }
}
