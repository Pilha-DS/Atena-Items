package com.openappslabs.jotter.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownLiveTest {
    private val palette = MarkdownLivePalette(
        marker = Color.Gray,
        codeBackground = Color.LightGray,
        link = Color.Blue,
        quote = Color.DarkGray
    )

    private fun styled(source: String) = styleMarkdownLive(source, palette)

    private fun AnnotatedString.hasSpan(start: Int, end: Int, check: (span: androidx.compose.ui.text.SpanStyle) -> Boolean) =
        spanStyles.any { it.start == start && it.end == end && check(it.item) }

    @Test
    fun text_is_kept_character_for_character() {
        val source = "### Título\n\n**forte** e `código`\n- item\n> citação\n```\ncode\n```"
        assertEquals(source, styled(source).text)
    }

    @Test
    fun offset_mapping_is_identity() {
        val input = AnnotatedString("a **b** e `c`\n## d")
        val output = MarkdownLiveTransformation(palette).filter(input)
        assertEquals(input.text, output.text.text)
        for (i in 0..input.length) {
            assertEquals(i, output.offsetMapping.originalToTransformed(i))
            assertEquals(i, output.offsetMapping.transformedToOriginal(i))
        }
    }

    @Test
    fun bold_and_code_keep_the_markers_but_style_the_content() {
        val source = "**forte** e `código`"
        val out = styled(source)

        val boldStart = source.indexOf("forte")
        assertTrue(out.hasSpan(boldStart, boldStart + "forte".length) {
            it.fontWeight == FontWeight.Bold
        })

        val codeStart = source.indexOf("código")
        assertTrue(out.hasSpan(codeStart, codeStart + "código".length) {
            it.fontFamily == FontFamily.Monospace
        })
    }

    @Test
    fun heading_is_sized_and_lists_keep_their_markers() {
        val heading = styled("## Título")
        val headingStart = "## Título".indexOf("Título")
        assertTrue(heading.hasSpan(headingStart, headingStart + "Título".length) {
            it.fontSize == 20.sp && it.fontWeight == FontWeight.Bold
        })

        val list = styled("- item")
        assertTrue(list.hasSpan(0, 1) { it.fontWeight == FontWeight.SemiBold })
    }

    @Test
    fun fenced_code_is_not_parsed_as_markup() {
        val source = "```\n# literal\n```"
        val out = styled(source)
        val lineStart = source.indexOf("# literal")
        val lineEnd = lineStart + "# literal".length
        assertFalse(out.spanStyles.any {
            it.start >= lineStart && it.end <= lineEnd && it.item.fontSize != TextUnit.Unspecified
        })
        assertTrue(out.spanStyles.any {
            it.start <= lineStart && it.end >= lineEnd && it.item.fontFamily == FontFamily.Monospace
        })
    }

    @Test
    fun empty_text_stays_empty() {
        assertEquals("", styled("").text)
        assertEquals("", MarkdownLiveTransformation(palette).filter(AnnotatedString("")).text.text)
    }
}
