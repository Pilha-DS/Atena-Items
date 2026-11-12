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
 * Coordinates lock dialogs. Screens request an unlock/setup and the root composable
 * renders the PIN dialog. Replaces BiometricAuthUtil on the desktop.
 */
object AppLock {

    enum class Mode { UNLOCK, SETUP }

    data class Request(
        val mode: Mode,
        val title: String,
        val subtitle: String,
        val onSuccess: () -> Unit,
        val onError: (String) -> Unit
    )

    var request by mutableStateOf<Request?>(null)
        private set

    var isUnlocked by mutableStateOf(true)
        private set

    fun requestUnlock(
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {}
    ) {
        if (!PinStore.hasPin()) {
            onSuccess()
            return
        }
        request = Request(Mode.UNLOCK, title, subtitle, onSuccess, onError)
    }

    fun requestSetup(
        title: String,
        subtitle: String,
        onDone: (Boolean) -> Unit
    ) {
        request = Request(
            mode = Mode.SETUP,
            title = title,
            subtitle = subtitle,
            onSuccess = { onDone(true) },
            onError = { onDone(false) }
        )
    }

    fun dismiss() {
        request = null
    }

    fun onUnlocked() {
        isUnlocked = true
        request = null
    }

    fun lock() {
        if (PinStore.hasPin()) isUnlocked = false
    }
}
