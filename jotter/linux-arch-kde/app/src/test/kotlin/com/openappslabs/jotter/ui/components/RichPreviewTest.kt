package com.openappslabs.jotter.ui.components

import androidx.compose.ui.graphics.Color
import com.openappslabs.jotter.data.model.NoteFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RichPreviewTest {
    private val palette = RichPalette(codeBackground = Color.Gray, link = Color.Blue)

    private fun preview(text: String, format: NoteFormat) =
        previewRichText(text, format, palette).text

    @Test
    fun markdown_drops_block_markers() {
        val out = preview("### Buildar em C\n\nPasso **um** e `dois`", NoteFormat.MARKDOWN)
        assertEquals("Buildar em C Passo um e dois", out)
    }

    @Test
    fun markdown_lists_keep_their_own_bullets() {
        assertEquals("• primeiro 2. segundo", preview("- primeiro\n2. segundo", NoteFormat.MARKDOWN))
    }

    @Test
    fun markdown_image_shows_alt_text() {
        assertEquals("logo", preview("![logo](https://x/y.png)", NoteFormat.MARKDOWN))
    }

    @Test
    fun html_drops_tags_and_decodes_entities() {
        assertEquals("Olá mundo & cia", preview("<p>Olá <b>mundo</b> &amp; cia</p>", NoteFormat.HTML))
    }

    @Test
    fun json_and_xml_show_content_not_markup() {
        assertEquals("{ \"a\": 1 }", preview("{\"a\":1}", NoteFormat.JSON))
        assertEquals("<a>x</a>", preview("<a>x</a>", NoteFormat.XML))
    }

    @Test
    fun plain_text_is_untouched_and_blank_stays_empty() {
        assertEquals("### nao e markdown", preview("### nao e markdown", NoteFormat.TEXT))
        assertEquals("", preview("   \n ", NoteFormat.MARKDOWN))
    }

    @Test
    fun preview_is_capped() {
        val long = (1..500).joinToString("\n") { "linha $it com algum texto" }
        val out = preview(long, NoteFormat.MARKDOWN)
        assert(out.length <= 280) { "preview grew to ${out.length} chars" }
    }
}
