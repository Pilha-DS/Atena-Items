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
import kotlin.io.path.deleteIfExists

/**
 * The real harvester: text files are read directly, Office packages are
 * unzipped, PDFs go through `pdftotext` and everything that is an image — or a
 * scanned PDF page — goes through `tesseract`.
 *
 * Rules that keep it predictable:
 *  - one file, one answer. Whatever happens (missing tool, broken file,
 *    timeout) comes back as a [Harvest] with a reason, never an exception;
 *  - a document that changed on disk is read again, because the indexer drops
 *    its text when the size or the date changes;
 *  - OCR is capped by pages and by time, so one 300-page scanned book cannot
 *    hold the scan hostage.
 */
class SystemTextHarvester(private val shell: Shell = SystemShell()) : TextHarvester {

    /** `-l` argument for Tesseract, detected once: `por+eng` when both exist. */
    private val languages: String by lazy { detectLanguages() }

    val ocrAvailable: Boolean get() = shell.exists(TESSERACT)

    val pdfTextAvailable: Boolean get() = shell.exists(PDFTOTEXT)

    override fun harvest(file: Path, kind: DocKind): Harvest? {
        if (!Files.isRegularFile(file)) return null
        val extension = DocKind.extensionOf(file.fileName.toString())
        return runCatching { read(file, kind, extension) }.getOrDefault(Harvest.failed)
    }

    private fun read(file: Path, kind: DocKind, extension: String): Harvest? {
        if (extension in PLAIN_EXTENSIONS) {
            val text = PlainReader.read(file) ?: return Harvest.none
            return Harvest(text, TextSource.TEXT)
        }
        if (extension in OFFICE_EXTENSIONS) {
            val text = OfficeReader.read(file, extension) ?: return Harvest.none
            return Harvest(text, TextSource.OFFICE)
        }
        return when {
            extension == "pdf" -> pdf(file)
            extension in IMAGE_EXTENSIONS -> image(file)
            kind == DocKind.TEXT || kind == DocKind.MARKDOWN || kind == DocKind.CODE ->
                Harvest(PlainReader.read(file).orEmpty(), TextSource.TEXT)
            else -> Harvest.none
        }
    }

    private fun pdf(file: Path): Harvest {
        val extracted = if (pdfTextAvailable) {
            shell.run(
                listOf(PDFTOTEXT, "-q", "-enc", "UTF-8", "-l", PDF_PAGES.toString(), file.toString(), "-"),
                PDF_TIMEOUT_SECONDS
            )?.let(PlainReader::compact)
        } else {
            null
        }

        if (!extracted.isNullOrBlank() && extracted.length >= MIN_PDF_TEXT) {
            return Harvest(extracted, TextSource.PDF)
        }
        // No text layer (a scan) or only page furniture: try the pages as
        // images. Without Tesseract or pdftoppm there is nothing more to do.
        val ocr = ocrPdf(file)
        if (ocr.hasText) return ocr
        return when {
            extracted != null -> Harvest(extracted, TextSource.PDF)
            ocr.source == TextSource.FAILED -> Harvest.failed
            else -> Harvest.none
        }
    }

    /** Renders the first pages and runs Tesseract on each one. */
    private fun ocrPdf(file: Path): Harvest {
        if (!ocrAvailable || !shell.exists(PDFTOPPM)) return Harvest.none
        val dir = runCatching { Files.createTempDirectory("docs-ocr") }.getOrNull()
            ?: return Harvest.failed
        return try {
            val prefix = dir.resolve("page")
            val rendered = shell.run(
                listOf(
                    PDFTOPPM, "-r", OCR_DPI.toString(), "-png",
                    "-f", "1", "-l", OCR_PAGES.toString(),
                    file.toString(), prefix.toString()
                ),
                OCR_TIMEOUT_SECONDS
            )
            if (rendered == null) return Harvest.failed

            val pages = Files.list(dir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".png") }
                    .sorted()
                    .toList()
            }
            if (pages.isEmpty()) return Harvest.failed

            val builder = StringBuilder()
            for (page in pages) {
                val text = ocrImage(page)
                if (text.hasText) builder.append(text.text).append('\n')
                if (builder.length >= MAX_TEXT_CHARS) break
            }
            Harvest(PlainReader.compact(builder.toString()), TextSource.OCR)
        } finally {
            runCatching {
                Files.list(dir).use { stream -> stream.forEach { it.deleteIfExists() } }
                Files.deleteIfExists(dir)
            }
        }
    }

    private fun image(file: Path): Harvest {
        if (!ocrAvailable) return Harvest.none
        return ocrImage(file)
    }

    private fun ocrImage(file: Path): Harvest {
        val raw = shell.run(
            listOf(TESSERACT, file.toString(), "stdout", "-l", languages),
            OCR_TIMEOUT_SECONDS
        ) ?: return Harvest.failed
        return Harvest(PlainReader.compact(raw), TextSource.OCR)
    }

    private fun detectLanguages(): String {
        val listed = shell.run(listOf(TESSERACT, "--list-langs"), 10) ?: return "eng"
        val available = listed.lineSequence().drop(1).map { it.trim() }.toSet()
        val chosen = buildList {
            if ("por" in available) add("por")
            if ("eng" in available) add("eng")
        }
        return chosen.joinToString("+").ifEmpty { "eng" }
    }

    companion object {
        const val TESSERACT = "tesseract"
        const val PDFTOTEXT = "pdftotext"
        const val PDFTOPPM = "pdftoppm"

        /** Enough for a long report; a scanned book would be a job, not a scan. */
        const val PDF_PAGES = 60
        const val OCR_PAGES = 5
        const val OCR_DPI = 200

        /** Below this, the PDF has no real text layer and the pages are a scan. */
        const val MIN_PDF_TEXT = 40

        const val PDF_TIMEOUT_SECONDS = 60L
        const val OCR_TIMEOUT_SECONDS = 90L

        private val PLAIN_EXTENSIONS = setOf("txt", "text", "log", "md", "markdown", "rst", "adoc", "csv")
        private val OFFICE_EXTENSIONS = setOf("docx", "odt", "xlsx", "pptx")
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "tif", "tiff", "heic", "avif")
    }
}
