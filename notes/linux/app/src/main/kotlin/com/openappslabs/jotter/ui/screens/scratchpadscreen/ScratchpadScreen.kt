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

package com.openappslabs.jotter.ui.screens.scratchpadscreen

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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openappslabs.jotter.di.AppGraph
import com.openappslabs.jotter.platform.AutoSave
import com.openappslabs.jotter.platform.Diagnostics
import com.openappslabs.jotter.platform.ScratchpadRecovery
import com.openappslabs.jotter.platform.ScratchpadSaver
import com.openappslabs.jotter.platform.Startup
import com.openappslabs.jotter.ui.theme.rememberJotterHaptics
import java.awt.Cursor
import kotlin.math.roundToInt
import com.openappslabs.jotter.ui.i18n.LocalStrings

/**
 * The "general note": one persistent scratchpad with no title, no read/edit
 * mode and no toolbar, just plain text. It is the same [com.openappslabs.jotter.data.model.Note]
 * type as every other note, flagged as scratchpad and hidden from the list.
 */
@Composable
fun ScratchpadScreen(
    window: java.awt.Window,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = rememberJotterHaptics()
    val strings = LocalStrings.current
    var noteId by remember { mutableStateOf<Int?>(null) }
    var text by remember { mutableStateOf("") }
    // Nothing may be written (nor flushed) before the stored text is on screen:
    // an empty buffer saved during startup is what used to erase the note.
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val note = runCatching { AppGraph.notesRepository.getOrCreateScratchpad() }
            .onFailure { Diagnostics.log("scratchpad open failed: ${it.stackTraceToString()}") }
            .getOrNull()
        if (note == null) {
            // Never leave the window blank when the database hiccups: whatever
            // the mirror holds is still better than an empty note, and no write
            // happens until the row is known.
            text = ScratchpadRecovery.read()
            loaded = true
            Startup.onGeneralNoteReady()
            return@LaunchedEffect
        }
        noteId = note.id
        // The database wins; the recovery mirror is only a fallback for when it
        // comes back empty (wiped or unreadable schema).
        val recovered = if (note.content.isBlank()) {
            ScratchpadRecovery.read().also { restored ->
                if (restored.isNotBlank()) {
                    Diagnostics.log("scratchpad restored from mirror (${restored.length} chars)")
                    runCatching { AppGraph.notesRepository.saveScratchpad(note.id, restored) }
                }
            }
        } else {
            ""
        }
        text = if (note.content.isBlank()) recovered else note.content
        // This text just came from the database (or was written back above):
        // mark it as stored so opening the note does not write it again.
        ScratchpadSaver.markPersisted(text)
        loaded = true
        Diagnostics.log("scratchpad opened: ${text.length} chars (id=${note.id})")
        // The general note has priority when Jotter opens. Waiting for two
        // frames guarantees this text was painted before the notes list and
        // the plasmoid counter start reading the whole table.
        withFrameNanos { }
        withFrameNanos { }
        Startup.onGeneralNoteReady()
    }

    // Every change is handed to the writer immediately; it serializes the
    // writes and retries failures instead of dropping them.
    LaunchedEffect(text, loaded) {
        if (!loaded) return@LaunchedEffect
        val id = noteId ?: return@LaunchedEffect
        ScratchpadSaver.submit(id, text)
    }

    // Hiding the widget, quitting or logging out must never lose the last words
    // typed: this flush waits for the write to actually reach SQLite.
    DisposableEffect(noteId) {
        val id = noteId
        val unregister = AutoSave.register {
            if (id != null && loaded) ScratchpadSaver.flushBlocking(id, text)
        }
        onDispose {
            unregister()
            // The window is leaving the composition (the note was closed): make
            // sure the text is on disk before the state disappears with it.
            if (id != null && loaded) ScratchpadSaver.flushBlocking(id, text)
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
        ScratchpadHeader(window = window, onClose = onClose)

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
                    text = strings.typeAnything,
                    style = textStyle,
                    color = placeholderColor,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 0.dp)
                )
            }
        }
    }
}

/**
 * Title-less header: the grip is what moves the window, the only chrome is a
 * close button so the window can be dismissed without the tray.
 */
@Composable
private fun ScratchpadHeader(
    window: java.awt.Window,
    onClose: () -> Unit
) {
    val haptics = rememberJotterHaptics()
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
        Box(
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
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .width(34.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(gripColor)
            )
        }

        IconButton(
            onClick = {
                haptics.click()
                onClose()
            },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = strings.closeGeneralNote,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
