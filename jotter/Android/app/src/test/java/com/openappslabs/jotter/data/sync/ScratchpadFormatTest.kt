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

package com.openappslabs.jotter.data.sync

import com.openappslabs.jotter.data.model.Note
import com.openappslabs.jotter.data.model.NoteFormat
import com.openappslabs.jotter.data.model.Scratchpad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The general note is Markdown on the wire, no matter what the row said. */
class ScratchpadFormatTest {

    @Test
    fun `scratchpad is sent as markdown`() {
        val note = Note(isScratchpad = true, format = NoteFormat.TEXT.key)
        assertEquals(NoteFormat.MARKDOWN.key, note.toSyncDto().format)
    }

    @Test
    fun `a pulled scratchpad lands as markdown`() {
        val dto = SyncNoteDto(uuid = Scratchpad.UUID, format = NoteFormat.TEXT.key)
        val note = dto.toNote()
        assertTrue(note.isScratchpad)
        assertEquals(NoteFormat.MARKDOWN.key, note.format)
    }

    @Test
    fun `other notes keep their own format`() {
        val html = Note(isScratchpad = false, format = NoteFormat.HTML.key)
        assertEquals(NoteFormat.HTML.key, html.toSyncDto().format)
        val dto = SyncNoteDto(uuid = "outra-nota", format = NoteFormat.JSON.key)
        assertEquals(NoteFormat.JSON.key, dto.toNote().format)
    }
}
