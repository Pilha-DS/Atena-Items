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

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/** Colors the live Markdown editor needs while building spans. */
internal data class MarkdownLivePalette(
    val marker: Color,
    val codeBackground: Color,
    val link: Color,
    val quote: Color
)

/**
 * Styles the Markdown of the general note while it is typed.
 *
 * The text is never converted: every character keeps its offset, so the caret,
 * the selection and what the sync stores are exactly the raw Markdown. Only the
 * markup is drawn differently (headings, bold, code, lists…) and the syntax
 * markers lose contrast, which makes the editor read like the rendered note
 * without a separate preview mode.
 */
@Composable
fun rememberMarkdownLiveTransformation(): VisualTransformation {
    val palette = MarkdownLivePalette(
        marker = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
        link = MaterialTheme.colorScheme.primary,
        quote = MaterialTheme.colorScheme.onSurfaceVariant
    )
    return remember(palette) { MarkdownLiveTransformation(palette) }
}

internal class MarkdownLiveTransformation(
    private val palette: MarkdownLivePalette
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(styleMarkdownLive(text.text, palette), OffsetMapping.Identity)
}

internal fun styleMarkdownLive(source: String, palette: MarkdownLivePalette): AnnotatedString =
    buildAnnotatedString {
        append(source)
        if (source.isEmpty()) return@buildAnnotatedString

        val markerStyle = SpanStyle(color = palette.marker)
        val quoteStyle = SpanStyle(color = palette.quote, fontStyle = FontStyle.Italic)
        val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace)
        val listMarkerStyle = SpanStyle(color = palette.link, fontWeight = FontWeight.SemiBold)

        var lineStart = 0
        var inFence = false
        while (lineStart < source.length) {
            val lineEnd = source.indexOf('\n', lineStart).let { if (it < 0) source.length else it }
            val line = source.substring(lineStart, lineEnd)
            when {
                isFence(line) -> {
                    addStyle(codeStyle, lineStart, lineEnd)
                    addStyle(markerStyle, lineStart, lineEnd)
                    inFence = !inFence
                }

                line.isBlank() -> Unit
                inFence -> addStyle(codeStyle, lineStart, lineEnd)
                isDivider(line) -> addStyle(markerStyle, lineStart, lineEnd)
                else -> {
                    heading(line, lineStart, markerStyle)
                    quote(line, lineStart, markerStyle, quoteStyle)
                    listMarker(line, lineStart, listMarkerStyle)
                    inline(line, lineStart, palette, markerStyle)
                }
            }
            lineStart = lineEnd + 1
        }
    }

private val LIVE_HEADING = Regex("^(\\s{0,3})(#{1,6})(\\s+)(.*)$")
private val LIVE_QUOTE = Regex("^(\\s{0,3})((?:>\\s?)+)(.*)$")
private val LIVE_BULLET = Regex("^(\\s*)([-*+])(\\s+)(.*)$")
private val LIVE_NUMBER = Regex("^(\\s*)(\\d{1,3}[.)])(\\s+)(.*)$")

private fun isFence(line: String): Boolean {
    val trimmed = line.trimStart()
    return trimmed.startsWith("```") || trimmed.startsWith("~~~")
}

private fun isDivider(line: String): Boolean {
    val compact = line.filterNot { it.isWhitespace() }
    if (compact.length < 3) return false
    return compact.all { it == compact.first() } && compact.first() in "-*_"
}

private fun AnnotatedString.Builder.heading(
    line: String,
    lineStart: Int,
    markerStyle: SpanStyle
) {
    val match = LIVE_HEADING.matchEntire(line) ?: return
    val indent = match.groups[1]?.value?.length ?: 0
    val hashes = match.groups[2]?.value?.length ?: return
    val spaces = match.groups[3]?.value?.length ?: 0
    val markerStart = lineStart + indent
    val textStart = markerStart + hashes + spaces
    val lineEnd = lineStart + line.length
    addStyle(markerStyle, markerStart, markerStart + hashes)
    if (textStart < lineEnd) addStyle(headingSpan(hashes), textStart, lineEnd)
}

private fun AnnotatedString.Builder.quote(
    line: String,
    lineStart: Int,
    markerStyle: SpanStyle,
    quoteStyle: SpanStyle
) {
    val match = LIVE_QUOTE.matchEntire(line) ?: return
    val prefix = (match.groups[1]?.value?.length ?: 0) +
        (match.groups[2]?.value?.length ?: 0)
    val textStart = lineStart + prefix
    val lineEnd = lineStart + line.length
    addStyle(markerStyle, lineStart, textStart)
    if (textStart < lineEnd) addStyle(quoteStyle, textStart, lineEnd)
}

private fun AnnotatedString.Builder.listMarker(
    line: String,
    lineStart: Int,
    markerStyle: SpanStyle
) {
    val match = LIVE_BULLET.matchEntire(line) ?: LIVE_NUMBER.matchEntire(line) ?: return
    val leading = match.groups[1]?.value?.length ?: 0
    val markerLength = match.groups[2]?.value?.length ?: return
    val markerStart = lineStart + leading
    addStyle(markerStyle, markerStart, markerStart + markerLength)
}

private fun AnnotatedString.Builder.inline(
    line: String,
    lineStart: Int,
    palette: MarkdownLivePalette,
    markerStyle: SpanStyle
) {
    for (match in MD_INLINE.findAll(line)) {
        val inline = inlineStyle(match, palette) ?: continue
        val innerStart = lineStart + inline.range.first
        val innerEnd = lineStart + inline.range.last + 1
        val matchStart = lineStart + match.range.first
        val matchEnd = lineStart + match.range.last + 1
        addStyle(inline.style, innerStart, innerEnd)
        if (matchStart < innerStart) addStyle(markerStyle, matchStart, innerStart)
        if (innerEnd < matchEnd) addStyle(markerStyle, innerEnd, matchEnd)
    }
}

private data class InlineSpan(val range: IntRange, val style: SpanStyle)

private fun inlineStyle(match: MatchResult, palette: MarkdownLivePalette): InlineSpan? {
    match.groups[1]?.let {
        return InlineSpan(
            it.range,
            SpanStyle(fontFamily = FontFamily.Monospace, background = palette.codeBackground)
        )
    }
    match.groups[2]?.let {
        return InlineSpan(it.range, SpanStyle(color = palette.link, fontStyle = FontStyle.Italic))
    }
    match.groups[3]?.let { return InlineSpan(it.range, SpanStyle(fontWeight = FontWeight.Bold)) }
    match.groups[4]?.let { return InlineSpan(it.range, SpanStyle(fontWeight = FontWeight.Bold)) }
    match.groups[5]?.let { return InlineSpan(it.range, SpanStyle(fontStyle = FontStyle.Italic)) }
    match.groups[6]?.let { return InlineSpan(it.range, SpanStyle(fontStyle = FontStyle.Italic)) }
    match.groups[7]?.let {
        return InlineSpan(it.range, SpanStyle(textDecoration = TextDecoration.LineThrough))
    }
    match.groups[8]?.let {
        return InlineSpan(
            it.range,
            SpanStyle(color = palette.link, textDecoration = TextDecoration.Underline)
        )
    }
    return null
}

private fun headingSpan(level: Int): SpanStyle = SpanStyle(
    fontSize = when (level) {
        1 -> 23.sp
        2 -> 20.sp
        3 -> 18.sp
        else -> 17.sp
    },
    fontWeight = if (level <= 3) FontWeight.Bold else FontWeight.SemiBold
)
