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

package com.openappslabs.jotter.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLiveTest {
    private val palette = MarkdownLivePalette(
        marker = Color.Gray,
        codeBackground = Color.LightGray,
        link = Color.Blue,
        quote = Color.DarkGray
    )

    private fun styled(source: String) = styleMarkdownLive(source, palette)

    private fun AnnotatedString.hasSpan(start: Int, end: Int, check: (SpanStyle) -> Boolean) =
        spanStyles.any { it.start == start && it.end == end && check(it.item) }

    @Test
    fun `text is kept character for character`() {
        val source = "### Título\n\n**forte** e `código`\n- item\n> citação\n```\ncode\n```"
        assertEquals(source, styled(source).text)
    }

    @Test
    fun `offset mapping is identity`() {
        val input = AnnotatedString("a **b** e `c`\n## d")
        val output = MarkdownLiveTransformation(palette).filter(input)
        assertEquals(input.text, output.text.text)
        for (i in 0..input.length) {
            assertEquals(i, output.offsetMapping.originalToTransformed(i))
            assertEquals(i, output.offsetMapping.transformedToOriginal(i))
        }
    }

    @Test
    fun `bold and code keep the markers but style the content`() {
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
    fun `heading is sized and lists keep their markers`() {
        val heading = styled("## Título")
        val headingStart = "## Título".indexOf("Título")
        assertTrue(heading.hasSpan(headingStart, headingStart + "Título".length) {
            it.fontSize == 20.sp && it.fontWeight == FontWeight.Bold
        })

        val list = styled("- item")
        assertTrue(list.hasSpan(0, 1) { it.fontWeight == FontWeight.SemiBold })
    }

    @Test
    fun `fenced code is not parsed as markup`() {
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
    fun `empty text stays empty`() {
        assertEquals("", styled("").text)
        assertEquals("", MarkdownLiveTransformation(palette).filter(AnnotatedString("")).text.text)
    }
}
