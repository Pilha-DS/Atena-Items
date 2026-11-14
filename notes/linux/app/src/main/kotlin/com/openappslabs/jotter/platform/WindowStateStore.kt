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

import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Toolkit
import java.io.File
import java.util.Properties

/**
 * Remembers the compact window geometry between launches.
 *
 * The window behaves like a desktop widget: a fixed, phone-sized card anchored
 * near the bottom-right corner. Size can still be changed by editing the file,
 * but there are no decorations to drag, so the position is what matters.
 */
object WindowStateStore {

    private const val MIN_WIDTH = 360f
    private const val MAX_WIDTH = 900f
    private const val MIN_HEIGHT = 420f
    private const val MAX_HEIGHT = 1600f

    /** Default widget footprint, close to a phone screen (430 x 840 dp). */
    fun defaultSize(): Pair<Float, Float> {
        val usable = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        }.getOrNull()
        val maxHeight = when {
            usable != null -> (usable.height - 72).coerceAtLeast(560)
            else -> Toolkit.getDefaultToolkit().screenSize.height - 72
        }
        val width = 430f.coerceIn(MIN_WIDTH, MAX_WIDTH)
        val height = 840f.coerceAtMost(maxHeight.toFloat()).coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        return width to height
    }

    private fun load(): Properties {
        val file = Xdg.windowStateFile.toFile()
        val props = Properties()
        if (!file.exists()) return props
        runCatching { file.inputStream().use(props::load) }
        return props
    }

    private fun store(props: Properties) {
        runCatching {
            File(Xdg.configDir.toString()).mkdirs()
            Xdg.windowStateFile.toFile().outputStream().use { props.store(it, "Jotter widget geometry") }
        }
    }

    /** Position saved previously, or null on first launch. */
    fun loadPosition(): Point? {
        val props = load()
        val x = props.getProperty("x")?.toIntOrNull() ?: return null
        val y = props.getProperty("y")?.toIntOrNull() ?: return null
        val screen = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        }.getOrNull() ?: return Point(x, y)
        // Ignore stale coordinates from a monitor that no longer exists.
        if (x !in -screen.width..(screen.width * 3) || y !in -screen.height..(screen.height * 3)) {
            return null
        }
        return Point(x, y)
    }

    fun savePosition(x: Int, y: Int) {
        val props = load()
        props.setProperty("x", x.toString())
        props.setProperty("y", y.toString())
        store(props)
    }

    /** Whether the companion "general note" window was open last time. */
    fun loadScratchpadVisible(): Boolean =
        load().getProperty("scratchpadVisible")?.let { it != "0" } ?: true

    fun saveScratchpadVisible(visible: Boolean) {
        val props = load()
        props.setProperty("scratchpadVisible", if (visible) "1" else "0")
        store(props)
    }

    /**
     * Whether the clipboard window was open last time. Off by default: the
     * widget and the general note are the everyday pair, the clipboard editor
     * is opened on demand from the search bar or the tray.
     */
    fun loadClipboardVisible(): Boolean =
        load().getProperty("clipboardVisible")?.let { it != "0" } ?: false

    fun saveClipboardVisible(visible: Boolean) {
        val props = load()
        props.setProperty("clipboardVisible", if (visible) "1" else "0")
        store(props)
    }

    /** Width of the companion window, a bit narrower than the notes card. */
    const val SCRATCHPAD_WIDTH_DP = 340f

    /** Width of the clipboard companion: same footprint as the general note. */
    const val CLIPBOARD_WIDTH_DP = 340f
    private const val COMPANION_GAP = 10

    /**
     * Where the three windows of the desktop shell go. A null companion means
     * that window is hidden and only the others were placed.
     */
    data class ClusterPlacement(
        val clipboard: Point?,
        val main: Point,
        val scratchpad: Point?
    )

    /**
     * Lays the companions out in a row around the widget: the clipboard on the
     * left, the general note on the right.
     *
     * The row is kept on screen by sliding the widget, never the companions, so
     * the widget is the one that moves when the cluster hits a screen edge. A
     * width of 0 means that companion is hidden. The result only depends on
     * `main`, so both companion windows can apply it and converge: once the
     * widget has been moved, recomputing yields the same layout.
     */
    fun glueCluster(
        main: java.awt.Rectangle,
        clipboardWidth: Int,
        scratchpadWidth: Int,
        height: Int
    ): ClusterPlacement {
        val bounds = usableBounds()
        val y = main.y.coerceIn(bounds.y, (bounds.y + bounds.height - height).coerceAtLeast(bounds.y))

        val hasClipboard = clipboardWidth > 0
        val hasScratchpad = scratchpadWidth > 0
        val left = if (hasClipboard) clipboardWidth + COMPANION_GAP else 0
        val right = if (hasScratchpad) COMPANION_GAP + scratchpadWidth else 0
        val total = left + main.width + right

        // Keep the widget where it is when the whole row fits, otherwise pull
        // the row back inside the screen (leftmost position when it never fits).
        val maxStart = (bounds.x + bounds.width - total).coerceAtLeast(bounds.x)
        val start = (main.x - left).coerceIn(bounds.x, maxStart)
        val mainX = start + left

        return ClusterPlacement(
            clipboard = if (hasClipboard) Point(start, y) else null,
            main = Point(mainX, y),
            scratchpad = if (hasScratchpad) Point(mainX + main.width + COMPANION_GAP, y) else null
        )
    }

    /** Anchor used on first launch: bottom-right corner, like a widget. */
    fun defaultPosition(width: Int, height: Int): Point {
        val bounds = usableBounds()
        return Point(
            (bounds.x + bounds.width - width - 24).coerceAtLeast(bounds.x),
            (bounds.y + bounds.height - height - 24).coerceAtLeast(bounds.y)
        )
    }

    private fun usableBounds(): java.awt.Rectangle = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    }.getOrDefault(java.awt.Rectangle(0, 0, Toolkit.getDefaultToolkit().screenSize.width, Toolkit.getDefaultToolkit().screenSize.height))

    /** Work area of the screen that contains the anchor (multi-monitor safe). */
    private fun usableBounds(anchor: IpcBridge.Anchor): java.awt.Rectangle {
        val center = Point(anchor.x + anchor.width / 2, anchor.y + anchor.height / 2)
        return runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
                .map { it.defaultConfiguration.bounds }
                .firstOrNull { it.contains(center) }
        }.getOrNull() ?: usableBounds()
    }

    /**
     * Places the card next to the panel icon that opened it: right below a top
     * panel, right above a bottom panel, and beside a vertical panel.
     *
     * `anchor` is the icon rectangle in screen coordinates, as reported by the
     * plasmoid via `Item.mapToGlobal()`.
     */
    fun positionForAnchor(anchor: IpcBridge.Anchor, windowWidth: Int, windowHeight: Int): Point {
        val bounds = usableBounds(anchor)
        val gap = 8

        val nearTop = anchor.y - bounds.y < 96
        val nearBottom = (bounds.y + bounds.height) - (anchor.y + anchor.height) < 96
        val nearLeft = anchor.x - bounds.x < 96
        val nearRight = (bounds.x + bounds.width) - (anchor.x + anchor.width) < 96

        val centerX = anchor.x + anchor.width / 2
        val centerY = anchor.y + anchor.height / 2

        var x: Int
        var y: Int
        when {
            // Horizontal panel (top or bottom): centre the card on the icon.
            nearTop || nearBottom -> {
                x = centerX - windowWidth / 2
                y = if (nearTop) anchor.y + anchor.height + gap else anchor.y - windowHeight - gap
            }
            // Vertical panel: place the card beside it.
            nearLeft || nearRight -> {
                x = if (nearLeft) anchor.x + anchor.width + gap else anchor.x - windowWidth - gap
                y = centerY - windowHeight / 2
            }
            else -> {
                x = centerX - windowWidth / 2
                y = anchor.y + anchor.height + gap
            }
        }

        x = x.coerceIn(bounds.x, (bounds.x + bounds.width - windowWidth).coerceAtLeast(bounds.x))
        y = y.coerceIn(bounds.y, (bounds.y + bounds.height - windowHeight).coerceAtLeast(bounds.y))
        return Point(x, y)
    }
}
