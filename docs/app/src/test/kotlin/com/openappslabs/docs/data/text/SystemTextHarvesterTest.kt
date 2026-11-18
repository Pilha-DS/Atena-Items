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

import com.openappslabs.docs.data.model.DocKind
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The harvester against a fake shell: the decisions (which tool, when to fall
 * back to OCR, what to do when a tool is missing) are tested here, without
 * spawning Tesseract on every build.
 */
class SystemTextHarvesterTest {

    private class FakeShell(
        private val available: Set<String>,
        private val outputs: Map<String, String?> = emptyMap(),
        private val onCall: (List<String>) -> Unit = {}
    ) : Shell {
        val calls = mutableListOf<List<String>>()

        override fun exists(command: String): Boolean = command in available

        override fun run(args: List<String>, timeoutSeconds: Long, maxChars: Int): String? {
            calls += args
            onCall(args)
            outputs[args.joinToString(" ")]?.let { return it }
            return outputs[args.first()]
        }
    }

    private fun file(name: String, content: String = "x"): Path =
        createTempDirectory("docs-harvest").resolve(name).also { Files.writeString(it, content) }

    @Test
    fun `a text file is read directly, without any external program`() {
        val shell = FakeShell(available = emptySet())
        val harvest = SystemTextHarvester(shell).harvest(file("nota.md", "# Título\ncorpo"), DocKind.MARKDOWN)

        assertEquals(TextSource.TEXT, harvest?.source)
        assertEquals("# Título\ncorpo", harvest?.text)
        assertTrue(shell.calls.isEmpty())
    }

    @Test
    fun `a docx is unzipped, not sent to OCR`() {
        val dir = createTempDirectory("docs-harvest")
        val docx = dir.resolve("c.docx")
        ZipOutputStream(docx.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("<w:document><w:p><w:r><w:t>Declaração de matrícula</w:t></w:r></w:p></w:document>".toByteArray())
            zip.closeEntry()
        }

        val harvest = SystemTextHarvester(FakeShell(available = setOf("tesseract")))
            .harvest(docx, DocKind.WORD)

        assertEquals(TextSource.OFFICE, harvest?.source)
        assertEquals("Declaração de matrícula", harvest?.text)
    }

    @Test
    fun `a pdf with a text layer goes through pdftotext`() {
        val shell = FakeShell(
            available = setOf("pdftotext"),
            outputs = mapOf("pdftotext" to "Relatório anual de extensão\nsegunda página do documento")
        )
        val harvest = SystemTextHarvester(shell).harvest(file("relatorio.pdf"), DocKind.PDF)

        assertEquals(TextSource.PDF, harvest?.source)
        assertEquals("Relatório anual de extensão\nsegunda página do documento", harvest?.text)
        assertEquals("pdftotext", shell.calls.single().first())
    }

    @Test
    fun `a scanned pdf falls back to rendering and OCR`() {
        val shell = FakeShell(
            available = setOf("pdftotext", "pdftoppm", "tesseract"),
            outputs = mapOf(
                "pdftotext" to "",
                // Rendering prints nothing on success.
                "pdftoppm" to "",
                // Tesseract sees the rendered pages by name.
                "tesseract --list-langs" to "List of available languages:\neng\npor\n",
                "tesseract" to "texto da pagina escaneada"
            ),
            onCall = { args ->
                when (args.first()) {
                    "pdftoppm" -> {
                        val prefix = Path.of(args[args.size - 1])
                        Files.writeString(Path.of("$prefix-1.png"), "png")
                        Files.writeString(Path.of("$prefix-2.png"), "png")
                    }
                }
            }
        )
        val harvester = SystemTextHarvester(shell)
        val harvest = harvester.harvest(file("escaneado.pdf"), DocKind.PDF)

        // The fake tesseract returns its own name; what matters is the path.
        assertTrue(shell.calls.any { it.first() == "pdftoppm" }, "as páginas foram renderizadas")
        assertTrue(shell.calls.count { it.first() == "tesseract" && "--list-langs" !in it } >= 2)
        assertEquals(TextSource.OCR, harvest?.source)
        assertEquals(
            "texto da pagina escaneada\ntexto da pagina escaneada",
            harvest?.text
        )
    }

    @Test
    fun `a scanned pdf without tesseract keeps the empty text layer`() {
        val shell = FakeShell(available = setOf("pdftotext"), outputs = mapOf("pdftotext" to ""))
        val harvest = SystemTextHarvester(shell).harvest(file("escaneado.pdf"), DocKind.PDF)

        assertEquals(TextSource.PDF, harvest?.source)
        assertEquals("", harvest?.text)
    }

    @Test
    fun `an image without tesseract has no text and does not call anything`() {
        val shell = FakeShell(available = emptySet())
        val harvest = SystemTextHarvester(shell).harvest(file("foto.jpg"), DocKind.IMAGE)

        assertEquals(TextSource.NONE, harvest?.source)
        assertTrue(shell.calls.isEmpty())
    }

    @Test
    fun `an image goes through tesseract in Portuguese and English`() {
        val shell = FakeShell(
            available = setOf("tesseract"),
            outputs = mapOf(
                "tesseract --list-langs" to "List of available languages:\neng\npor\n",
                "tesseract" to "Certificado\n\n  emitido em 2026  "
            )
        )
        val harvest = SystemTextHarvester(shell).harvest(file("certificado.png"), DocKind.IMAGE)

        assertEquals(TextSource.OCR, harvest?.source)
        assertEquals("Certificado\nemitido em 2026", harvest?.text)
        val ocrCall = shell.calls.last()
        assertEquals("por+eng", ocrCall[ocrCall.indexOf("-l") + 1])
    }

    @Test
    fun `a failing tesseract is remembered as a failure`() {
        val shell = FakeShell(
            available = setOf("tesseract"),
            outputs = mapOf("tesseract" to null)
        )
        val harvest = SystemTextHarvester(shell).harvest(file("quebrado.png"), DocKind.IMAGE)

        assertEquals(TextSource.FAILED, harvest?.source)
        assertFalse(harvest!!.hasText)
    }

    @Test
    fun `a video has nothing to read`() {
        val shell = FakeShell(available = setOf("tesseract", "pdftotext"))
        val harvest = SystemTextHarvester(shell).harvest(file("aula.mp4"), DocKind.VIDEO)

        assertEquals(TextSource.NONE, harvest?.source)
        assertTrue(shell.calls.isEmpty())
    }

    @Test
    fun `a file that vanished is null`() {
        val harvest = SystemTextHarvester(FakeShell(available = emptySet()))
            .harvest(Path.of("/nao/existe/mais.pdf"), DocKind.PDF)
        assertNull(harvest)
    }

    @Test
    fun `language detection without por falls back to eng`() {
        val shell = FakeShell(
            available = setOf("tesseract"),
            outputs = mapOf(
                "tesseract --list-langs" to "List of available languages:\neng\n",
                "tesseract" to "texto"
            )
        )
        SystemTextHarvester(shell).harvest(file("x.png"), DocKind.IMAGE)
        val ocrCall = shell.calls.last()
        assertEquals("eng", ocrCall[ocrCall.indexOf("-l") + 1])
    }
}
