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

    /** Width of the companion window, a bit narrower than the notes card. */
    const val SCRATCHPAD_WIDTH_DP = 340f
    private const val SCRATCHPAD_GAP = 10

    /** Where the companion window should go, and how far the widget must move. */
    data class GluePlacement(val scratchpad: Point, val mainShiftX: Int)

    /**
     * Keeps the "general note" window glued to the right side of the widget.
     *
     * If the pair does not fit (the widget sits next to the panel on the
     * right edge, which is the default), the widget slides left just enough
     * for the scratchpad to stay on screen — the scratchpad is always the
     * right-hand window. `mainShiftX` tells the caller how far to move it.
     */
    fun glueRight(main: java.awt.Rectangle, width: Int, height: Int): GluePlacement {
        val bounds = usableBounds()
        val y = main.y.coerceIn(bounds.y, (bounds.y + bounds.height - height).coerceAtLeast(bounds.y))
        val desiredX = main.x + main.width + SCRATCHPAD_GAP
        val maxX = bounds.x + bounds.width - width

        if (desiredX <= maxX) {
            return GluePlacement(Point(desiredX, y), mainShiftX = 0)
        }

        // Slide the widget left so the two windows fit side by side.
        val overflow = desiredX - maxX
        val room = (main.x - bounds.x).coerceAtLeast(0)
        val shift = -overflow.coerceAtMost(room)
        val x = (desiredX + shift).coerceIn(bounds.x, maxX.coerceAtLeast(bounds.x))
        return GluePlacement(Point(x, y), mainShiftX = shift)
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
