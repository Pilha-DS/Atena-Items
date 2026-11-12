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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openappslabs.jotter.data.model.NoteFormat

/**
 * Renders a note's plain text according to its [NoteFormat].
 *
 * Parsing is deliberately simple and dependency-free: the goal is a readable
 * preview of what is stored, never a lossy conversion. The database keeps the
 * original text, so switching formats (or back to plain text) loses nothing.
 */

internal enum class RichBlockStyle { PARAGRAPH, HEADING, CODE, QUOTE, BULLET, NUMBER, DIVIDER }

internal data class RichBlock(
    val style: RichBlockStyle = RichBlockStyle.PARAGRAPH,
    val level: Int = 0,
    val number: Int = 0,
    val text: AnnotatedString = AnnotatedString("")
)

/** Theme colors a parser needs while building spans. */
internal data class RichPalette(val codeBackground: Color, val link: Color)

@Composable
fun RichTextView(
    text: String,
    format: NoteFormat,
    modifier: Modifier = Modifier,
    baseStyle: TextStyle = MaterialTheme.typography.bodyLarge
) {
    val palette = RichPalette(
        codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
        link = MaterialTheme.colorScheme.primary
    )
    val blocks = remember(text, format, palette) { parseRichText(text, format, palette) }
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val keyColor = MaterialTheme.colorScheme.tertiary
    val tagColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        blocks.forEach { block ->
            when (block.style) {
                RichBlockStyle.DIVIDER -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                RichBlockStyle.CODE -> Text(
                    text = highlightCode(block.text.text, format, keyColor, tagColor),
                    style = baseStyle.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = codeColor
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.codeBackground, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )

                RichBlockStyle.HEADING -> Text(
                    text = block.text,
                    style = headingStyle(block.level),
                    color = MaterialTheme.colorScheme.onSurface
                )

                RichBlockStyle.QUOTE -> Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(
                        modifier = Modifier
                            .width(3.dp)
                            .background(
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(2.dp)
                            )
                    )
                    Text(
                        text = block.text,
                        style = baseStyle.copy(
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }

                RichBlockStyle.BULLET -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "•",
                        style = baseStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(16.dp)
                    )
                    Text(text = block.text, style = baseStyle, color = MaterialTheme.colorScheme.onSurface)
                }

                RichBlockStyle.NUMBER -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "${block.number}.",
                        style = baseStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(22.dp)
                    )
                    Text(text = block.text, style = baseStyle, color = MaterialTheme.colorScheme.onSurface)
                }

                RichBlockStyle.PARAGRAPH -> Text(
                    text = block.text,
                    style = baseStyle,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
    2 -> TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    3 -> TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
    4 -> TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
    5 -> TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
    else -> TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
}

// -------------------------------------------------------------------------
// Entry point
// -------------------------------------------------------------------------

internal fun parseRichText(
    source: String,
    format: NoteFormat,
    palette: RichPalette
): List<RichBlock> {
    if (source.isBlank()) return emptyList()
    return when (format) {
        NoteFormat.TEXT -> listOf(
            RichBlock(RichBlockStyle.PARAGRAPH, text = AnnotatedString(source))
        )
        NoteFormat.MARKDOWN -> parseMarkdown(source, palette)
        NoteFormat.HTML -> parseHtml(source, palette)
        NoteFormat.JSON -> listOf(
            RichBlock(RichBlockStyle.CODE, text = AnnotatedString(prettyJson(source)))
        )
        NoteFormat.XML -> listOf(
            RichBlock(RichBlockStyle.CODE, text = AnnotatedString(prettyXml(source)))
        )
    }
}

// -------------------------------------------------------------------------
// Markdown
// -------------------------------------------------------------------------

private val MD_HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val MD_BULLET = Regex("^[-*+]\\s+(.*)$")
private val MD_NUMBER = Regex("^(\\d{1,3})[.)]\\s+(.*)$")

private val MD_INLINE = Regex(
    "`([^`]+)`" +
        "|\\*\\*([^*]+)\\*\\*" +
        "|__([^_]+)__" +
        "|\\*([^*]+)\\*" +
        "|_([^_]+)_" +
        "|~~([^~]+)~~" +
        "|\\[([^\\]]*)]\\([^)]*\\)"
)

private fun parseMarkdown(source: String, palette: RichPalette): List<RichBlock> {
    val blocks = mutableListOf<RichBlock>()
    val lines = source.replace("\r\n", "\n").split('\n')
    val paragraph = StringBuilder()
    var i = 0

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += RichBlock(
                RichBlockStyle.PARAGRAPH,
                text = inlineMarkdown(paragraph.toString().trim(), palette)
            )
            paragraph.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // Fenced code: kept verbatim, never parsed as markup.
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            flushParagraph()
            val fence = trimmed.take(3)
            val body = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                body.append(lines[i]).append('\n')
                i++
            }
            if (i < lines.size) i++ // closing fence
            blocks += RichBlock(
                RichBlockStyle.CODE,
                text = AnnotatedString(body.toString().trimEnd('\n'))
            )
            continue
        }

        if (trimmed.isEmpty()) {
            flushParagraph()
            i++
            continue
        }

        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            flushParagraph()
            blocks += RichBlock(RichBlockStyle.DIVIDER)
            i++
            continue
        }

        val heading = MD_HEADING.matchEntire(trimmed)
        if (heading != null) {
            flushParagraph()
            blocks += RichBlock(
                RichBlockStyle.HEADING,
                level = heading.groupValues[1].length,
                text = inlineMarkdown(heading.groupValues[2], palette)
            )
            i++
            continue
        }

        if (trimmed.startsWith(">")) {
            flushParagraph()
            val quote = StringBuilder()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                if (quote.isNotEmpty()) quote.append(' ')
                quote.append(lines[i].trim().removePrefix(">").trim())
                i++
            }
            blocks += RichBlock(
                RichBlockStyle.QUOTE,
                text = inlineMarkdown(quote.toString(), palette)
            )
            continue
        }

        val bullet = MD_BULLET.matchEntire(trimmed)
        if (bullet != null) {
            flushParagraph()
            blocks += RichBlock(
                RichBlockStyle.BULLET,
                text = inlineMarkdown(bullet.groupValues[1], palette)
            )
            i++
            continue
        }

        val numbered = MD_NUMBER.matchEntire(trimmed)
        if (numbered != null) {
            flushParagraph()
            blocks += RichBlock(
                RichBlockStyle.NUMBER,
                number = numbered.groupValues[1].toIntOrNull() ?: 1,
                text = inlineMarkdown(numbered.groupValues[2], palette)
            )
            i++
            continue
        }

        if (paragraph.isNotEmpty()) paragraph.append(' ')
        paragraph.append(trimmed)
        i++
    }
    flushParagraph()
    return blocks
}

private fun inlineMarkdown(text: String, palette: RichPalette): AnnotatedString =
    buildAnnotatedString {
        var last = 0
        for (match in MD_INLINE.findAll(text)) {
            if (match.range.first > last) append(text.substring(last, match.range.first))
            val groups = match.groups
            when {
                groups[1] != null -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = palette.codeBackground
                    )
                ) { append(groups[1]!!.value) }

                groups[2] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(groups[2]!!.value)
                }

                groups[3] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(groups[3]!!.value)
                }

                groups[4] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(groups[4]!!.value)
                }

                groups[5] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(groups[5]!!.value)
                }

                groups[6] != null -> withStyle(
                    SpanStyle(textDecoration = TextDecoration.LineThrough)
                ) { append(groups[6]!!.value) }

                groups[7] != null -> withStyle(
                    SpanStyle(
                        color = palette.link,
                        textDecoration = TextDecoration.Underline
                    )
                ) { append(groups[7]!!.value) }
            }
            last = match.range.last + 1
        }
        if (last < text.length) append(text.substring(last))
    }

// -------------------------------------------------------------------------
// HTML (subset: formatting tags, headings, lists, quotes, code)
// -------------------------------------------------------------------------

private val HTML_TOKEN = Regex("(?is)<pre[^>]*>.*?</pre>|<[^>]+>")
private val HTML_ENTITY = Regex("&(#\\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);")

private fun parseHtml(source: String, palette: RichPalette): List<RichBlock> {
    val blocks = mutableListOf<RichBlock>()
    val cleaned = source.replace(Regex("(?s)<!--.*?-->"), "")
    val buffer = StringBuilder()
    val openMarks = mutableListOf<Pair<Int, SpanStyle>>()
    val closedSpans = mutableListOf<Triple<Int, Int, SpanStyle>>()
    var blockStyle = RichBlockStyle.PARAGRAPH
    var level = 0
    var listStyle: RichBlockStyle? = null
    var listNumber = 1
    var currentNumber = 0
    var cursor = 0

    fun flush() {
        // 1:1 character replacement keeps the inline mark offsets valid.
        val raw = buffer.toString()
            .replace('\n', ' ')
            .replace('\t', ' ')
            .replace('\r', ' ')
        val leading = raw.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
        val normalized = raw.trim()
        if (normalized.isNotEmpty()) {
            val annotated = buildAnnotatedString {
                append(normalized)
                fun apply(start: Int, end: Int, style: SpanStyle) {
                    val from = (start - leading).coerceIn(0, normalized.length - 1)
                    val to = (end - leading).coerceIn(from + 1, normalized.length)
                    addStyle(style, from, to)
                }
                for ((start, end, style) in closedSpans) apply(start, end, style)
                // Marks left open (malformed HTML) run to the end of the block.
                for ((start, style) in openMarks) apply(start, normalized.length, style)
            }
            blocks += RichBlock(
                style = when (blockStyle) {
                    RichBlockStyle.HEADING -> RichBlockStyle.HEADING
                    else -> blockStyle
                },
                level = level,
                number = currentNumber,
                text = annotated
            )
        }
        buffer.clear()
        openMarks.clear()
        closedSpans.clear()
        currentNumber = 0
        if (blockStyle == RichBlockStyle.HEADING) {
            blockStyle = RichBlockStyle.PARAGRAPH
            level = 0
        }
    }

    fun mark(position: Int, closing: Boolean, style: SpanStyle) {
        if (!closing) {
            openMarks += position to style
        } else {
            val index = openMarks.indexOfLast { it.second == style }
            if (index >= 0) {
                val (start, _) = openMarks.removeAt(index)
                closedSpans += Triple(start, position, style)
            }
        }
    }

    for (token in HTML_TOKEN.findAll(cleaned)) {
        val plain = cleaned.substring(cursor, token.range.first)
        if (plain.isNotEmpty()) buffer.append(decodeEntities(plain))
        cursor = token.range.last + 1

        val tag = token.value
        if (tag.startsWith("<pre", ignoreCase = true)) {
            flush()
            val inner = tag.substringAfter('>').substringBeforeLast("</pre>")
            // Nested tags (usually <code>) are not markup inside a code block.
            val code = decodeEntities(inner.replace(Regex("<[^>]+>"), "")).trim('\n')
            blocks += RichBlock(RichBlockStyle.CODE, text = AnnotatedString(code))
            continue
        }

        val closing = tag.startsWith("</")
        val body = tag.removePrefix("<").removePrefix("/").removeSuffix(">").removeSuffix("/").trim()
        val name = body.substringBefore(' ').substringBefore('\t').lowercase()

        when (name) {
            "br", "p", "div", "section", "article", "tr", "table" -> flush()

            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                flush()
                if (!closing) {
                    blockStyle = RichBlockStyle.HEADING
                    level = name.last().digitToInt()
                }
            }

            "hr" -> {
                flush()
                blocks += RichBlock(RichBlockStyle.DIVIDER)
            }

            "blockquote" -> {
                flush()
                blockStyle = if (closing) RichBlockStyle.PARAGRAPH else RichBlockStyle.QUOTE
            }

            "ul" -> {
                flush()
                listStyle = if (closing) null else RichBlockStyle.BULLET
            }

            "ol" -> {
                flush()
                listStyle = if (closing) null else RichBlockStyle.NUMBER
                if (!closing) listNumber = 1
            }

            "li" -> {
                flush()
                if (!closing) {
                    blockStyle = when (listStyle) {
                        RichBlockStyle.NUMBER -> RichBlockStyle.NUMBER
                        else -> RichBlockStyle.BULLET
                    }
                    if (blockStyle == RichBlockStyle.NUMBER) {
                        currentNumber = listNumber
                        listNumber++
                    }
                } else {
                    blockStyle = RichBlockStyle.PARAGRAPH
                }
            }

            "b", "strong" -> mark(buffer.length, closing, SpanStyle(fontWeight = FontWeight.Bold))
            "i", "em" -> mark(buffer.length, closing, SpanStyle(fontStyle = FontStyle.Italic))
            "u" -> mark(
                buffer.length, closing,
                SpanStyle(textDecoration = TextDecoration.Underline)
            )

            "s", "strike", "del" -> mark(
                buffer.length, closing,
                SpanStyle(textDecoration = TextDecoration.LineThrough)
            )

            "code" -> mark(
                buffer.length, closing,
                SpanStyle(fontFamily = FontFamily.Monospace, background = palette.codeBackground)
            )

            "a" -> mark(
                buffer.length, closing,
                SpanStyle(color = palette.link, textDecoration = TextDecoration.Underline)
            )
        }
    }

    val tail = cleaned.substring(cursor)
    if (tail.isNotEmpty()) buffer.append(decodeEntities(tail))
    flush()
    return blocks
}

private fun decodeEntities(text: String): String =
    HTML_ENTITY.replace(text) { match ->
        val value = match.groupValues[1]
        when {
            value.startsWith("#x", ignoreCase = true) ->
                value.drop(2).toIntOrNull(16)?.let { it.toChar().toString() } ?: match.value

            value.startsWith("#") ->
                value.drop(1).toIntOrNull()?.let { it.toChar().toString() } ?: match.value

            else -> when (value.lowercase()) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> " "
                else -> match.value
            }
        }
    }

// -------------------------------------------------------------------------
// JSON / XML: pretty print, then light syntax coloring
// -------------------------------------------------------------------------

private fun prettyJson(source: String): String {
    val out = StringBuilder()
    var depth = 0
    var inString = false
    var escaped = false

    fun newline() {
        out.append('\n')
        repeat(depth) { out.append("  ") }
    }

    for (char in source.trim()) {
        if (inString) {
            out.append(char)
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            continue
        }
        when (char) {
            '"' -> {
                inString = true
                out.append(char)
            }
            '{', '[' -> {
                out.append(char)
                depth++
                newline()
            }
            '}', ']' -> {
                depth = (depth - 1).coerceAtLeast(0)
                newline()
                out.append(char)
            }
            ',' -> {
                out.append(char)
                newline()
            }
            ':' -> out.append(": ")
            ' ', '\t', '\n', '\r' -> Unit
            else -> out.append(char)
        }
    }
    return out.toString()
        .replace(Regex("\\{\\s*\\}"), "{}")
        .replace(Regex("\\[\\s*]"), "[]")
}

private fun prettyXml(source: String): String {
    val text = source.trim()
    val out = StringBuilder()
    var depth = 0
    var i = 0

    fun newline() {
        if (out.isEmpty()) return
        out.append('\n')
        repeat(depth) { out.append("  ") }
    }

    while (i < text.length) {
        if (text[i] == '<') {
            val end = text.indexOf('>', i)
            if (end < 0) {
                out.append(text.substring(i))
                break
            }
            val tag = text.substring(i, end + 1)
            val closing = tag.startsWith("</")
            val selfClosing = tag.endsWith("/>") || tag.startsWith("<!") || tag.startsWith("<?")
            if (closing) {
                depth = (depth - 1).coerceAtLeast(0)
                // A closing tag right after its text stays on the same line.
                if (out.isEmpty() || out.last() == '>') newline()
                out.append(tag)
            } else {
                newline()
                out.append(tag)
                if (!selfClosing) depth++
            }
            i = end + 1
        } else {
            val next = text.indexOf('<', i).let { if (it < 0) text.length else it }
            val chunk = text.substring(i, next).trim()
            if (chunk.isNotEmpty()) out.append(chunk)
            i = next
        }
    }
    return out.toString()
}

private val CODE_STRING = Regex("\"[^\"\\n]*\"")
private val CODE_NUMBER = Regex("\\b-?\\d+(\\.\\d+)?\\b")
private val CODE_TAG = Regex("</?[A-Za-z_][^>]*>")

private fun highlightCode(
    text: String,
    format: NoteFormat,
    keyColor: Color,
    tagColor: Color
): AnnotatedString {
    if (format != NoteFormat.JSON && format != NoteFormat.XML) return AnnotatedString(text)

    return buildAnnotatedString {
        append(text)
        if (format == NoteFormat.XML) {
            for (match in CODE_TAG.findAll(text)) {
                addStyle(SpanStyle(color = tagColor), match.range.first, match.range.last + 1)
            }
        }
        for (match in CODE_STRING.findAll(text)) {
            val after = text.substring(match.range.last + 1).trimStart()
            val color = if (format == NoteFormat.JSON && after.startsWith(":")) keyColor else Color.Unspecified
            if (color != Color.Unspecified) {
                addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
            }
        }
        for (match in CODE_NUMBER.findAll(text)) {
            // Skip numbers that live inside a string literal.
            val inside = CODE_STRING.findAll(text).any { match.range.first in it.range }
            if (!inside) {
                addStyle(
                    SpanStyle(color = keyColor),
                    match.range.first,
                    match.range.last + 1
                )
            }
        }
    }
}
