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

package com.openappslabs.jotter.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import javax.imageio.ImageIO

/**
 * What the system clipboard holds right now, as far as the clipboard window
 * is concerned.
 */
sealed interface ClipboardContent {
    /** Plain text, editable in place. */
    data class Text(val value: String) : ClipboardContent

    /**
     * A picture. [signature] fingerprints the copy it came from — the encoded
     * bytes when the toolkit exposes them, the pixels otherwise — so the
     * poller can tell a new copy from the one already on screen without
     * converting it to a Compose bitmap again.
     */
    data class Image(val pixels: BufferedImage, val signature: String) : ClipboardContent

    /**
     * The clipboard still holds the picture the window is already showing.
     * Only the fingerprint is carried, so an unchanged copy costs one transfer
     * and no decode.
     */
    data class ImageRepeat(val signature: String) : ClipboardContent

    /** Nothing the window can show. */
    data object Empty : ClipboardContent
}

/** Picture file names accepted when a file manager copies them. */
private val imageFileExtensions = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")

/** MIME types tried after the generic AWT image flavor. */
private val imageMimeTypes = listOf(
    "image/png",
    "image/jpeg",
    "image/jpg",
    "image/tiff",
    "image/bmp",
    "image/gif",
)

/**
 * Reads the clipboard the way a desktop user expects: a copied picture is
 * shown as a picture even when the same copy also carries text/HTML (which is
 * what browsers and screenshot tools put next to the image).
 *
 * [previousImageSignature] is what the window already shows. When the picture
 * in the clipboard still matches it, the encoded bytes are not decoded again:
 * the poller runs while the window is open, and decoding a 4K screenshot every
 * second just to throw it away is a lot of CPU and garbage for nothing.
 */
fun readSystemClipboard(previousImageSignature: String? = null): ClipboardContent = runCatching {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    val image = readImage(clipboard, previousImageSignature)
    if (image != null) return@runCatching image

    val text = if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
        clipboard.getData(DataFlavor.stringFlavor) as? String
    } else {
        null
    }
    if (text.isNullOrEmpty()) ClipboardContent.Empty else ClipboardContent.Text(text)
}.getOrElse {
    Diagnostics.log("clipboard read failed: ${it.message}")
    ClipboardContent.Empty
}

/** Writes text back to the system clipboard. */
fun writeSystemClipboardText(value: String): Boolean = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
}.onFailure {
    Diagnostics.log("clipboard write failed: ${it.message}")
}.isSuccess

private fun readImage(
    clipboard: Clipboard,
    previousSignature: String?,
): ClipboardContent? =
    readAwtImage(clipboard, previousSignature)
        ?: readEncodedImage(clipboard, previousSignature)
        ?: readImageFile(clipboard, previousSignature)

private fun readAwtImage(
    clipboard: Clipboard,
    previousSignature: String?,
): ClipboardContent? {
    if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
    val image = runCatching {
        clipboard.getData(DataFlavor.imageFlavor) as? java.awt.Image
    }.getOrNull()
    val buffered = image?.toBufferedImage() ?: return null
    val signature = buffered.clipboardSignature()
    return if (signature == previousSignature) {
        ClipboardContent.ImageRepeat(signature)
    } else {
        ClipboardContent.Image(buffered, signature)
    }
}

/**
 * Some toolkits only expose the encoded bytes (image/png and friends) and not
 * the generic java.awt.Image flavor.
 */
private fun readEncodedImage(
    clipboard: Clipboard,
    previousSignature: String?,
): ClipboardContent? {
    for (mimeType in imageMimeTypes) {
        val flavor = runCatching { DataFlavor(mimeType, mimeType) }.getOrNull() ?: continue
        if (!clipboard.isDataFlavorAvailable(flavor)) continue
        val bytes = runCatching {
            when (val data = clipboard.getData(flavor)) {
                is InputStream -> data.use { it.readBytes() }
                is ByteArray -> data
                else -> null
            }
        }.getOrNull() ?: continue

        val signature = bytes.encodedSignature()
        if (signature == previousSignature) return ClipboardContent.ImageRepeat(signature)

        val buffered = runCatching {
            ImageIO.read(ByteArrayInputStream(bytes))
        }.getOrNull() ?: continue
        return ClipboardContent.Image(buffered, signature)
    }
    return null
}

/** A picture copied from the file manager comes as a file list. */
private fun readImageFile(
    clipboard: Clipboard,
    previousSignature: String?,
): ClipboardContent? {
    if (!clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) return null
    val files = runCatching {
        clipboard.getData(DataFlavor.javaFileListFlavor) as? List<*>
    }.getOrNull().orEmpty()
    val file = files.filterIsInstance<File>()
        .firstOrNull { it.extension.lowercase() in imageFileExtensions }
        ?: return null

    val signature = "file:${file.absolutePath}:${file.length()}:${file.lastModified()}"
    if (signature == previousSignature) return ClipboardContent.ImageRepeat(signature)

    val buffered = runCatching { ImageIO.read(file) }.getOrNull() ?: return null
    return ClipboardContent.Image(buffered, signature)
}

private fun java.awt.Image.toBufferedImage(): BufferedImage? {
    if (this is BufferedImage) return this
    val width = getWidth(null)
    val height = getHeight(null)
    if (width <= 0 || height <= 0) return null
    return runCatching {
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { copy ->
            val graphics = copy.createGraphics()
            try {
                graphics.drawImage(this, 0, 0, null)
            } finally {
                graphics.dispose()
            }
        }
    }.getOrNull()
}

/**
 * Fingerprint cheap enough to run on every poll: the encoded size plus a
 * sparse sample of the bytes catches a new screenshot without decoding it.
 */
private fun ByteArray.encodedSignature(): String {
    val samples = 64
    val builder = StringBuilder("bytes:$size")
    val last = (size - 1).coerceAtLeast(0)
    for (index in 0 until samples) {
        val position = if (last == 0) 0 else last * index / (samples - 1)
        builder.append(':').append(this[position].toInt() and 0xFF)
    }
    return builder.toString()
}

/** Converts the pixels read from the clipboard for Compose to draw. */
fun ClipboardContent.Image.toImageBitmap(): ImageBitmap = pixels.toComposeImageBitmap()

/**
 * Fingerprint cheap enough to run on every poll: the size plus a sparse grid
 * of pixels catches a new screenshot without hashing every byte of it.
 */
private fun BufferedImage.clipboardSignature(): String {
    val columns = 8
    val rows = 8
    val builder = StringBuilder("${width}x${height}")
    for (row in 0 until rows) {
        val y = ((height - 1) * row / (rows - 1)).coerceIn(0, height - 1)
        for (column in 0 until columns) {
            val x = ((width - 1) * column / (columns - 1)).coerceIn(0, width - 1)
            builder.append(':').append(getRGB(x, y))
        }
    }
    return builder.toString()
}
