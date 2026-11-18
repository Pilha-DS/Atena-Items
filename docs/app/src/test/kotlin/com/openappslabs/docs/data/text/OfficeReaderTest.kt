/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Atena Docs
 *
 * Atena Docs is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Atena Docs is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Atena Docs.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.docs.data.text

import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfficeReaderTest {

    private fun zip(name: String, vararg entries: Pair<String, String>): Path {
        val file = createTempDirectory("docs-office").resolve(name)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((path, content) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `docx body text comes out with paragraphs on their own lines`() {
        val xml = """
            <w:document><w:body>
              <w:p><w:r><w:t>Certificado de conclusão</w:t></w:r></w:p>
              <w:p><w:r><w:t>Curso de </w:t></w:r><w:r><w:t>Extensão</w:t></w:r></w:p>
            </w:body></w:document>
        """.trimIndent()
        val file = zip("c.docx", "word/document.xml" to xml)

        val text = OfficeReader.read(file, "docx")
        assertEquals("Certificado de conclusão\nCurso de Extensão", text)
    }

    @Test
    fun `odt reads content xml and keeps the headings apart`() {
        val xml = """
            <office:body><text:h>Declaração</text:h>
            <text:p>Aluno <text:span>regular</text:span></text:p></office:body>
        """.trimIndent()
        val file = zip("c.odt", "content.xml" to xml)

        assertEquals("Declaração\nAluno regular", OfficeReader.read(file, "odt"))
    }

    @Test
    fun `xlsx strings are read one per line`() {
        val xml = "<sst><si><t>Janeiro</t></si><si><t>Fevereiro</t></si></sst>"
        val file = zip("p.xlsx", "xl/sharedStrings.xml" to xml)

        assertEquals("Janeiro\nFevereiro", OfficeReader.read(file, "xlsx"))
    }

    @Test
    fun `pptx slides are concatenated`() {
        val file = zip(
            "s.pptx",
            "ppt/slides/slide1.xml" to "<p:sld><a:p><a:r><a:t>Primeiro slide</a:t></a:r></a:p></p:sld>",
            "ppt/slides/slide2.xml" to "<p:sld><a:p><a:r><a:t>Segundo slide</a:t></a:r></a:p></p:sld>"
        )

        assertEquals("Primeiro slide\nSegundo slide", OfficeReader.read(file, "pptx"))
    }

    @Test
    fun `entities are decoded and unknown ones stay as typed`() {
        assertEquals("A & B < C > D \"E\" 'F' ç", OfficeReader.unescape("A &amp; B &lt; C &gt; D &quot;E&quot; &apos;F&apos; &#231;"))
        assertEquals("100% &copy;", OfficeReader.unescape("100% &copy;"))
    }

    @Test
    fun `a package without the expected part is null`() {
        val file = zip("vazio.docx", "word/settings.xml" to "<x/>")
        assertNull(OfficeReader.read(file, "docx"))
    }

    @Test
    fun `a file that is not a zip is null`() {
        val file = createTempDirectory("docs-office").resolve("falso.docx")
        file.toFile().writeText("isto não é um zip")
        assertNull(OfficeReader.read(file, "docx"))
    }

    @Test
    fun `a document with only markup yields empty text`() {
        val text = OfficeReader.toText("<w:p></w:p><w:p/>")
        assertTrue(text.isBlank())
    }
}
