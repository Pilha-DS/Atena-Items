/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Agenda
 *
 * Agenda is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Agenda is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Agenda.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.agenda.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openappslabs.agenda.ui.theme.KdeColors
import com.openappslabs.agenda.ui.theme.readKdeColors
import java.awt.GraphicsEnvironment
import java.awt.geom.RoundRectangle2D
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * The app's toasts: a borderless card in the top-right corner that goes away
 * on its own after a couple of seconds, instead of the system notification
 * popup.
 *
 * It is a window of its own (`JWindow`) rather than an overlay inside the main
 * window: the agenda is prewarmed at login and spends most of its life hidden,
 * and the timer alarm has to be visible exactly then. The window is created
 * once and only hidden, so the second toast does not pay for building a
 * Compose scene — and it is not focusable, so showing a toast never steals the
 * focus (which would make the agenda hide itself, thinking the user clicked
 * outside).
 *
 * The window is **opaque** and rounded through `setShape`: a transparent
 * `JWindow` painted by a `ComposePanel` does not composite correctly in Plasma
 * 6 (the card adds its color over whatever is behind it instead of covering
 * it), while solid windows are painted as expected. The corner radius is the
 * price to pay for a card that actually looks like a card.
 *
 * `show` never throws and never blocks: it is called from the notification
 * threads. When the toast cannot exist (a headless run), it falls back to the
 * regular desktop notification.
 */
object AgendaToasts {

    /** Long enough to read a title and a line, short enough to stay out of the way. */
    const val VISIBLE_MS = 2_000L

    private const val WIDTH_DP = 340
    private const val HEIGHT_DP = 88
    private const val MARGIN_DP = 16
    private const val CORNER_DP = 10
    private const val BAR_DP = 2
    /** The bar moves in these steps: smooth enough, cheap enough to redraw. */
    private const val TICK_MS = 50L
    private const val FADE_STEP_MS = 60
    private const val FADE_STEPS = 3

    private val content = mutableStateOf<ToastContent?>(null)

    /** How much of the visible time is left, 1 → 0, drawn as the thin bar. */
    private val progress = mutableFloatStateOf(1f)

    /** AWT is single threaded: everything below only runs on the event thread. */
    private var window: JWindow? = null
    private var panel: ComposePanel? = null
    private var countdown: Timer? = null
    private var fadeTimer: Timer? = null

    fun show(title: String, body: String, urgent: Boolean = false): Boolean {
        if (GraphicsEnvironment.isHeadless()) {
            return DesktopNotifier.send(title, body, urgent)
        }

        val colors = readKdeColors(Xdg.kdeGlobalsFile)
        val toast = ToastContent(
            title = title.trim(),
            body = body.trim(),
            urgent = urgent,
            colors = colors
        )
        SwingUtilities.invokeLater {
            runCatching { present(toast) }
                .onFailure {
                    System.err.println("agenda: toast failed: $it")
                    DesktopNotifier.send(title, body, urgent)
                }
        }
        return true
    }

    /**
     * Builds the window ahead of the first notification. Loading the Compose
     * scene takes a good part of the two seconds the toast is on screen, and
     * the first one usually arrives right when the user is waiting for it.
     */
    fun prewarm() {
        if (GraphicsEnvironment.isHeadless()) return
        SwingUtilities.invokeLater {
            runCatching { window ?: createWindow().also { window = it } }
                .onFailure { System.err.println("agenda: toast prewarm failed: $it") }
        }
    }

    private fun present(toast: ToastContent) {
        val toastWindow = window ?: createWindow().also { window = it }
        val scale = scaleFactor()
        val width = (WIDTH_DP * scale).toInt()
        val height = (HEIGHT_DP * scale).toInt()
        val margin = (MARGIN_DP * scale).toInt()
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val placed = ToastPlacement.topRight(screen, width, height, margin)
        val card = java.awt.Color(toast.colors.surfaceVariant.toArgb(), true)

        countdown?.stop()
        // A toast arriving during the fade-out replaces it: the old fade would
        // otherwise hide the new one mid-animation.
        fadeTimer?.stop()
        restoreOpacity(toastWindow)
        toastWindow.background = card
        panel?.background = card
        toastWindow.setBounds(placed.x, placed.y, width, height)
        // Shaping is what gives the opaque window its rounded corners. Where
        // the platform refuses it, a square card is still a card.
        runCatching {
            val radius = 2 * CORNER_DP * scale.toFloat()
            toastWindow.shape = RoundRectangle2D.Float(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                radius,
                radius
            )
        }
        // Only after the colors: a window shown with the previous card visible
        // would flash the old text for a frame.
        content.value = toast
        progress.floatValue = 1f
        if (!toastWindow.isVisible) {
            toastWindow.isVisible = true
        }
        startCountdown(toastWindow)
    }

    /**
     * Drives the thin bar and the moment the toast goes away. The bar is time
     * based, not frame based: a plain AWT `Timer` keeps it right even while
     * the composition is not ticking frames, and it restarts with a new toast.
     */
    private fun startCountdown(toastWindow: JWindow) {
        val startedAt = System.currentTimeMillis()
        countdown = Timer(TICK_MS.toInt()) { event ->
            val left = toastProgress(startedAt, System.currentTimeMillis(), VISIBLE_MS)
            progress.floatValue = left
            if (left <= 0f) {
                (event.source as Timer).stop()
                fadeOut(toastWindow)
            }
        }.apply {
            isRepeats = true
            start()
        }
    }

    /**
     * Fades out before hiding. Opacity is a compositor feature: where it is
     * refused the toast simply disappears, which is still correct.
     */
    private fun fadeOut(toastWindow: JWindow) {
        var step = 0
        fadeTimer = Timer(FADE_STEP_MS) { event ->
            step++
            val applied = runCatching {
                toastWindow.opacity = (1f - step.toFloat() / FADE_STEPS).coerceAtLeast(0.2f)
            }.isSuccess
            if (!applied || step >= FADE_STEPS) {
                (event.source as Timer).stop()
                dismiss(toastWindow)
            }
        }.apply {
            isRepeats = true
            start()
        }
    }

    private fun dismiss(toastWindow: JWindow) {
        countdown?.stop()
        countdown = null
        fadeTimer?.stop()
        fadeTimer = null
        toastWindow.isVisible = false
        restoreOpacity(toastWindow)
        content.value = null
    }

    private fun restoreOpacity(toastWindow: JWindow) {
        runCatching { toastWindow.opacity = 1f }
    }

    private fun createWindow(): JWindow {
        val composePanel = ComposePanel()
        composePanel.setContent {
            ToastCard(content.value)
        }
        panel = composePanel

        return JWindow().apply {
            contentPane.add(composePanel)
            isAlwaysOnTop = true
            // Never takes focus: the agenda hides itself when it loses focus,
            // and a toast is not something to interact with.
            setFocusableWindowState(false)
            setAutoRequestFocus(false)
        }
    }

    private fun scaleFactor(): Double = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice
            .defaultConfiguration
            .defaultTransform
            .scaleX
    }.getOrDefault(1.0)

    private data class ToastContent(
        val title: String,
        val body: String,
        val urgent: Boolean,
        val colors: KdeColors
    )

    @Composable
    private fun ToastCard(toast: ToastContent?) {
        if (toast == null) return

        Box(
            Modifier
                .fillMaxSize()
                .background(toast.colors.surfaceVariant)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = toast.title,
                    color = if (toast.urgent) toast.colors.accent else toast.colors.foreground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (toast.body.isNotEmpty()) {
                    Text(
                        text = toast.body,
                        color = toast.colors.foreground.copy(alpha = 0.78f),
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // The bar is the countdown: it is full when the toast appears and
            // reaches the right edge as the toast goes away. Full width at the
            // bottom edge, so the window's round corners clip its ends.
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress.floatValue)
                    .height(BAR_DP.dp)
                    .background(
                        if (toast.urgent) {
                            toast.colors.accent
                        } else {
                            toast.colors.foreground.copy(alpha = 0.38f)
                        }
                    )
            )
        }
    }
}

/**
 * How much of the toast is left, from 1 (just appeared) to 0 (goes away).
 *
 * Pure and on the wall clock: the bar is drawn from it every [TICK_MS], so a
 * dropped frame cannot make the toast outlive its time.
 */
internal fun toastProgress(startedAt: Long, now: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    val elapsed = (now - startedAt).coerceAtLeast(0L)
    return (1f - elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
}
