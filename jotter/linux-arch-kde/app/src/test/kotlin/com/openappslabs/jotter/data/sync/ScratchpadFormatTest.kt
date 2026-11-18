package com.openappslabs.jotter.data.sync

import com.openappslabs.jotter.data.model.Note
import com.openappslabs.jotter.data.model.NoteFormat
import com.openappslabs.jotter.data.model.Scratchpad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The general note is Markdown on the wire, no matter what the row said. */
class ScratchpadFormatTest {

    @Test
    fun scratchpad_is_sent_as_markdown() {
        val note = Note(isScratchpad = true, format = NoteFormat.TEXT.key)
        assertEquals(NoteFormat.MARKDOWN.key, note.toSyncDto().format)
    }

    @Test
    fun a_pulled_scratchpad_lands_as_markdown() {
        val dto = SyncNoteDto(uuid = Scratchpad.UUID, format = NoteFormat.TEXT.key)
        val note = dto.toNote()
        assertTrue(note.isScratchpad)
        assertEquals(NoteFormat.MARKDOWN.key, note.format)
    }

    @Test
    fun other_notes_keep_their_own_format() {
        assertEquals(NoteFormat.HTML.key, Note(isScratchpad = false, format = NoteFormat.HTML.key).toSyncDto().format)
        val dto = SyncNoteDto(uuid = "outra-nota", format = NoteFormat.JSON.key)
        assertEquals(NoteFormat.JSON.key, dto.toNote().format)
    }
}
