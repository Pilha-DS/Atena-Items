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

package com.openappslabs.jotter.ui.screens.clipboardscreen

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openappslabs.jotter.platform.Diagnostics
import com.openappslabs.jotter.ui.i18n.LocalStrings
import com.openappslabs.jotter.ui.theme.rememberJotterHaptics
import kotlinx.coroutines.delay
import java.awt.Cursor
import kotlin.math.roundToInt

/** How often the system clipboard is read while the window is on screen. */
private const val POLL_INTERVAL_MS = 900L

/** A write only goes out once typing stopped for this long. */
private const val PUSH_DELAY_MS = 400L

/**
 * Editor for whatever is in the system clipboard right now.
 *
 * It is not a note: nothing is stored in the database. The text is read from
 * the clipboard when the window appears (and again whenever something else
 * copies while it is open) and every edit is written back, so the clipboard
 * itself is what gets edited. Edits are only pushed after a short pause, which
 * keeps one long paste from filling the clipboard history of Klipper with every
 * intermediate keystroke.
 */
@Composable
fun ClipboardScreen(
    window: java.awt.Window,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = rememberJotterHaptics()
    val strings = LocalStrings.current
    val clipboard = LocalClipboardManager.current

    var text by remember { mutableStateOf("") }
    // Last value known to be the clipboard content: what was read on open, or
    // what this editor wrote. It is what tells an outside copy apart from our
    // own write, and what keeps unsaved edits from being overwritten.
    var syncedText by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    fun readClipboard(): String? = runCatching { clipboard.getText()?.text }.getOrNull()

    fun writeClipboard(value: String) {
        runCatching { clipboard.setText(AnnotatedString(value)) }
            .onSuccess { syncedText = value }
            .onFailure { Diagnostics.log("clipboard write failed: ${it.message}") }
    }

    LaunchedEffect(Unit) {
        val current = readClipboard().orEmpty()
        text = current
        syncedText = current
        loaded = true
        Diagnostics.log("clipboard window opened: ${current.length} chars")
    }

    // Something else copied while the window is open: show it, but never on
    // top of text the user is still editing.
    LaunchedEffect(Unit) {
        while (true) {
            delay(POLL_INTERVAL_MS)
            if (!window.isShowing) continue
            val current = readClipboard() ?: continue
            if (current == syncedText) continue
            if (text == syncedText) {
                text = current
                Diagnostics.log("clipboard read from system: ${current.length} chars")
            }
            syncedText = current
        }
    }

    // Every edit goes back to the system clipboard once typing settles.
    LaunchedEffect(text, loaded) {
        if (!loaded || text == syncedText) return@LaunchedEffect
        delay(PUSH_DELAY_MS)
        writeClipboard(text)
    }

    // Hiding the window disposes the editor; the last words typed still have
    // to reach the clipboard before the state goes away with it.
    DisposableEffect(Unit) {
        onDispose {
            if (loaded && text != syncedText) writeClipboard(text)
        }
    }

    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val primaryColor = MaterialTheme.colorScheme.primary
    val cursorBrush = remember(primaryColor) { SolidColor(primaryColor) }
    val textStyle = remember(onSurfaceColor) {
        TextStyle(
            fontSize = 15.sp,
            lineHeight = 23.sp,
            color = onSurfaceColor
        )
    }
    val scrollState = rememberScrollState()

    Column(modifier = modifier.fillMaxSize()) {
        ClipboardHeader(
            window = window,
            onReload = {
                haptics.click()
                val current = readClipboard().orEmpty()
                text = current
                syncedText = current
            },
            onClose = {
                haptics.click()
                onClose()
            }
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = textStyle,
                cursorBrush = cursorBrush,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    .verticalScroll(scrollState)
            )
            if (text.isEmpty() && loaded) {
                Text(
                    text = strings.clipboardEmpty,
                    style = textStyle,
                    color = placeholderColor,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 0.dp)
                )
            }
        }
    }
}

/**
 * Header with the window name: the grip is what moves the window, and the
 * reload button pulls whatever the clipboard holds now, discarding local edits.
 */
@Composable
private fun ClipboardHeader(
    window: java.awt.Window,
    onReload: () -> Unit,
    onClose: () -> Unit
) {
    val strings = LocalStrings.current
    val density = LocalDensity.current.density
    val gripColor = MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)))
                .pointerInput(window) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val location = window.location
                        window.setLocation(
                            location.x + (dragAmount.x / density).roundToInt(),
                            location.y + (dragAmount.y / density).roundToInt()
                        )
                    }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(34.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(gripColor)
            )
            Text(
                text = strings.clipboard,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp)
            )
        }

        IconButton(
            onClick = onReload,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = strings.reloadClipboard,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = strings.closeClipboard,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
