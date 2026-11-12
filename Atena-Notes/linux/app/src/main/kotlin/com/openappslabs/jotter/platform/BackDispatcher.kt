/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.jotter.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember

/**
 * Android's BackHandler equivalent. Screens register a handler; the root window
 * intercepts Escape and gives the most recently registered handler a chance to
 * consume the event before the navigation stack pops.
 */
class BackDispatcher {
    private val handlers = mutableStateListOf<Pair<Int, () -> Unit>>()
    private var nextToken = 0
    private var escapeHandler: (() -> Unit)? = null

    fun register(onBack: () -> Unit): Int {
        val token = nextToken++
        handlers.add(token to onBack)
        return token
    }

    fun unregister(token: Int) {
        handlers.removeAll { it.first == token }
    }

    fun handleBack(): Boolean {
        val handler = handlers.lastOrNull()?.second
        if (handler != null) {
            handler()
            return true
        }
        escapeHandler?.invoke()
        return escapeHandler != null
    }

    fun setEscapeHandler(handler: (() -> Unit)?) {
        escapeHandler = handler
    }
}

val LocalBackDispatcher = compositionLocalOf<BackDispatcher> {
    error("BackDispatcher not provided")
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val dispatcher = LocalBackDispatcher.current
    DisposableEffect(dispatcher, enabled, onBack) {
        if (!enabled) return@DisposableEffect onDispose { }
        val token = dispatcher.register(onBack)
        onDispose { dispatcher.unregister(token) }
    }
}

@Composable
fun rememberBackDispatcher(): BackDispatcher = remember { BackDispatcher() }
