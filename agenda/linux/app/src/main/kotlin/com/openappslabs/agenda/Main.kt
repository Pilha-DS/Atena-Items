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

package com.openappslabs.agenda

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.openappslabs.agenda.data.repository.EventRepository
import com.openappslabs.agenda.data.source.AgendaDatabase
import com.openappslabs.agenda.data.sync.AgendaSyncEngine
import com.openappslabs.agenda.data.sync.SyncPreferences
import com.openappslabs.agenda.platform.IpcBridge
import com.openappslabs.agenda.platform.Startup
import com.openappslabs.agenda.platform.StatusPublisher
import com.openappslabs.agenda.platform.WindowPlacement
import com.openappslabs.agenda.platform.WindowPreferences
import com.openappslabs.agenda.platform.Xdg
import com.openappslabs.agenda.ui.AgendaApp
import com.openappslabs.agenda.ui.AgendaState
import com.openappslabs.agenda.ui.i18n.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import kotlin.concurrent.thread

private val WindowWidth = 460.dp
private val WindowHeight = 700.dp

/**
 * Focus must be held this long before losing it counts as "the user clicked
 * outside". Without it, the focus blip when the window is first shown (and the
 * focus-stealing prevention of the compositor) would hide it at once.
 */
private const val FOCUS_ARM_DELAY_MS = 700L

/** No self-hiding right after opening, whatever the focus state is. */
private const val FOCUS_GRACE_AFTER_SHOW_MS = 1_500L

/**
 * Any click or key press inside the window this recent means the user is still
 * working with it: a focus loss at that moment is not "clicked outside".
 */
private const val INPUT_GRACE_MS = 1_200L

/** How often the focus watcher looks at the window state. */
private const val FOCUS_POLL_MS = 100L

/**
 * Countdown before hiding when focus was really held and then lost. The
 * compositor moves focus around; a short bounce is not a click outside.
 */
private const val FOCUS_LOSS_DELAY_MS = 300L

/**
 * Same countdown when the window never held focus (KWin can refuse it for a
 * window mapped by a background process). Longer, because here the pointer is
 * the only evidence, and passing the mouse over the window must not close it.
 */
private const val UNFOCUSED_DWELL_MS = 900L

/**
 * Clicking the panel card also takes the focus away, so the watcher usually
 * hides the window before the toggle request is read. Within this window the
 * request means "close", not "reopen".
 */
private const val TOGGLE_AFTER_BLUR_MS = 700L

fun main(args: Array<String>) {
    Startup.mark("main")
    Xdg.ensureDirectories()

    // The panel can be clicked while this process is still booting: the helper
    // sees the pid, so it only writes a request instead of starting another
    // app. Reading that request here — before the window exists — is what lets
    // the first click still open. It is acknowledged right away, so a prewarm
    // (which writes nothing) never opens a window at login.
    val bootToggle = IpcBridge.toggleToken()
    val toggleRequestedAtBoot = IpcBridge.isFreshToggle(bootToggle)
    IpcBridge.acknowledgeToggle(bootToggle)

    // A widget is a singleton: a second launch becomes a show/hide request.
    if (!IpcBridge.acquireSingleInstanceLock()) {
        IpcBridge.requestToggle()
        return
    }
    IpcBridge.publishRuntimeDir()
    Startup.mark("lock")

    // Skiko's native teardown can hang, and this process holds the
    // single-instance lock: a hung exit would swallow the next click. If the
    // JVM is still around a couple of seconds after closing, end it hard.
    Runtime.getRuntime().addShutdownHook(Thread {
        Thread({
            Thread.sleep(2_000)
            Runtime.getRuntime().halt(0)
        }, "agenda-exit-watchdog").apply {
            isDaemon = true
            start()
        }
    })

    val database = AgendaDatabase(Xdg.databaseFile)
    val repository = EventRepository(database)
    val preferences = SyncPreferences(Xdg.configFile)
    val windowPreferences = WindowPreferences(Xdg.windowFile)

    // SQLite loads its native driver on the first connection. Doing that off
    // the UI thread means the window can be painted while the driver is still
    // unpacking, instead of the first frame waiting for it.
    thread(name = "agenda-db-init", isDaemon = true) {
        runCatching { database.init() }
            .onFailure { System.err.println("agenda: database init failed: $it") }
        Startup.mark("database")
    }

    application {
        LaunchedEffect(Unit) { Startup.mark("application") }
        val scope = rememberCoroutineScope()
        val statusPublisher = remember { StatusPublisher(repository, preferences) }
        val syncEngine = remember {
            AgendaSyncEngine(
                repository = repository,
                preferences = preferences,
                scope = scope,
                onChange = { statusPublisher.publish() }
            )
        }
        val state = remember {
            AgendaState(
                repository = repository,
                syncEngine = syncEngine,
                preferences = preferences,
                windowPreferences = windowPreferences,
                scope = scope,
                onDataChanged = { statusPublisher.publish() }
            )
        }

        // Pre-warmed at login (or by AGENDA_START_HIDDEN): the process is ready
        // and the first click only has to show the window.
        val startHidden = remember {
            System.getenv("AGENDA_START_HIDDEN") != null || args.contains(START_HIDDEN_ARG)
        }
        var isVisible by remember { mutableStateOf(!startHidden) }

        // The app behaves like a panel popup: a click outside hides it. The
        // user can turn that off in Settings, and AGENDA_KEEP_OPEN=1 forces it
        // off (useful for screenshots/debugging).
        val forceKeepOpen = remember { System.getenv("AGENDA_KEEP_OPEN") != null }
        var focusGainedAt by remember { mutableStateOf(0L) }
        var focusArmed by remember { mutableStateOf(false) }
        var shownAt by remember { mutableStateOf(System.currentTimeMillis()) }
        var lastInputAt by remember { mutableStateOf(0L) }
        var hiddenByFocusAt by remember { mutableStateOf(0L) }
        // Set when the focus watcher hides the window: the click that took the
        // focus away still has its toggle in flight, and that one means
        // "close". The next click must open, not be swallowed.
        var pendingCloseAck by remember { mutableStateOf(false) }

        // A click that arrived while the JVM was still booting, or before the
        // window existed, must open it as soon as it does.
        var pendingOpen by remember { mutableStateOf(toggleRequestedAtBoot) }

        // The AWT window, so show/hide does not depend on a composition.
        // Compose pauses the window scene while it is hidden, and a
        // `visible = false` window only comes back on the next frame of that
        // very scene: showing it through state alone can wait many seconds.
        var appWindow by remember { mutableStateOf<java.awt.Window?>(null) }

        // Shows/hides the real window at once; the state below only mirrors it
        // for the focus watcher, Esc and the toggle logic.
        fun applyVisibility(visible: Boolean) {
            if (visible) {
                shownAt = System.currentTimeMillis()
                focusArmed = false
                focusGainedAt = 0L
                val window = appWindow
                if (window == null) {
                    // Still composing: the window does not exist yet, so the
                    // request is honoured when it appears.
                    pendingOpen = true
                    return
                }
                pendingOpen = false
                window.isVisible = true
                window.toFront()
                window.requestFocus()
                // A change made elsewhere — the AI chat writes straight to the
                // database, and the sync pulls from the hub — has to be on
                // screen as soon as the window opens, not on the next tick.
                state.tick()
                statusPublisher.publish()
                Startup.mark("window-shown")
            } else {
                pendingOpen = false
                appWindow?.isVisible = false
                focusArmed = false
                focusGainedAt = 0L
                Startup.mark("window-hidden")
            }
        }

        val windowState = rememberWindowState(
            size = DpSize(WindowWidth, WindowHeight),
            position = WindowPosition(androidx.compose.ui.Alignment.Center)
        )

        /** Runs on the AWT event thread: the toggle request from the panel. */
        fun onToggleRequest() {
            // The click that took the focus away from the window already
            // closed it; that same request must not reopen it.
            if (pendingCloseAck &&
                System.currentTimeMillis() - hiddenByFocusAt < TOGGLE_AFTER_BLUR_MS
            ) {
                pendingCloseAck = false
                return
            }
            if (isVisible) {
                applyVisibility(false)
                isVisible = false
            } else {
                IpcBridge.loadAnchor()?.let { anchor ->
                    val (width, height) = windowSizePx()
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .maximumWindowBounds
                    val placed = WindowPlacement.below(anchor, width, height, screen)
                    windowState.size = DpSize(WindowWidth, WindowHeight)
                    windowState.position = WindowPosition(placed.x.dp, placed.y.dp)
                }
                applyVisibility(true)
                isVisible = true
            }
        }

        // Any click or key press inside the app counts as activity, so a focus
        // loss right after it is never mistaken for "clicked outside".
        DisposableEffect(Unit) {
            val listener = java.awt.event.AWTEventListener { event ->
                val pressed = when (event) {
                    is java.awt.event.MouseEvent ->
                        event.id == java.awt.event.MouseEvent.MOUSE_PRESSED
                    is java.awt.event.KeyEvent ->
                        event.id == java.awt.event.KeyEvent.KEY_PRESSED
                    else -> false
                }
                if (pressed) lastInputAt = System.currentTimeMillis()
            }
            val toolkit = java.awt.Toolkit.getDefaultToolkit()
            toolkit.addAWTEventListener(
                listener,
                java.awt.AWTEvent.MOUSE_EVENT_MASK or java.awt.AWTEvent.KEY_EVENT_MASK
            )
            onDispose { toolkit.removeAWTEventListener(listener) }
        }

        LaunchedEffect(Unit) { syncEngine.start() }

        // Auto-hide when the user goes elsewhere.
        //
        // A Swing timer, not a coroutine: it runs on the AWT event thread that
        // owns the window, so it keeps ticking regardless of Compose's frame
        // clock (which pauses while the window is hidden).
        //
        // Two signals, because focus alone is not reliable here: KWin's focus
        // stealing prevention can refuse focus to a window that was mapped by
        // a background process, and then "lost focus" never happens. So the
        // pointer leaving the window and staying out is accepted as well,
        // after a longer delay so a brief pass over the pointer does not close
        // anything. On Wayland the pointer position cannot be trusted (see
        // [pointerOutsideWindow]), so there only focus and the input grace
        // decide — which is the path that actually works on a Plasma session.
        DisposableEffect(Unit) {
            var awaySince = 0L
            val timer = javax.swing.Timer(FOCUS_POLL_MS.toInt()) {
                // The event editor and the settings hold unsaved text: never
                // hide under them, or the typing would be lost.
                val autoHide = !forceKeepOpen &&
                    state.closeOnClickOutside &&
                    state.editor == null &&
                    !state.syncOpen
                if (!isVisible || !autoHide) {
                    awaySince = 0L
                    return@Timer
                }

                val now = System.currentTimeMillis()
                // Our own popups/dialogs keep the window alive while focused.
                val ownFocused = java.awt.Window.getWindows().any { it.isShowing && it.isFocused }
                if (ownFocused) {
                    if (focusGainedAt == 0L) focusGainedAt = now
                    if (now - focusGainedAt >= FOCUS_ARM_DELAY_MS) focusArmed = true
                    awaySince = 0L
                    return@Timer
                }
                focusGainedAt = 0L

                // Give the window its moment: focus arrives a little after the
                // window is mapped, and a click that opened it is not "outside".
                if (now - shownAt < FOCUS_GRACE_AFTER_SHOW_MS ||
                    now - lastInputAt < INPUT_GRACE_MS
                ) {
                    awaySince = 0L
                    return@Timer
                }

                // The pointer still over the window means the compositor is
                // shuffling windows, not that the user clicked away. When the
                // pointer cannot be read at all, focus is the only signal left,
                // so treat it as "outside" instead of disabling auto-hide.
                if (pointerOutsideWindow() == false) {
                    awaySince = 0L
                    return@Timer
                }

                val delay = if (focusArmed) FOCUS_LOSS_DELAY_MS else UNFOCUSED_DWELL_MS
                if (awaySince == 0L) awaySince = now
                if (now - awaySince < delay) return@Timer

                // Re-check right before hiding: focus that bounced back, or a
                // pointer that came back, is not a click outside.
                awaySince = 0L
                val nobodyFocused = java.awt.Window.getWindows().none { it.isShowing && it.isFocused }
                if (!nobodyFocused || pointerOutsideWindow() == false) return@Timer
                Startup.mark(
                    "hide-focus armed=$focusArmed sinceShown=${now - shownAt} " +
                        "sinceInput=${now - lastInputAt} delay=$delay"
                )

                applyVisibility(false)
                isVisible = false
                hiddenByFocusAt = System.currentTimeMillis()
                pendingCloseAck = true
            }
            timer.start()
            onDispose { timer.stop() }
        }

        // The plasmoid cannot call the JVM: it touches a file, and this
        // thread turns that into "show/hide" or "sync now".
        //
        // A plain thread, not a coroutine: Compose pauses its own dispatcher
        // while the window is hidden, so a coroutine here would only wake up
        // after the window was already visible — which it never would be. The
        // work is posted to the AWT event thread, where the window lives.
        DisposableEffect(Unit) {
            val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var lastToggle = bootToggle
            var lastSyncRequest = IpcBridge.syncStamp()
            val thread = Thread({
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        Thread.sleep(IPC_POLL_MS)
                    } catch (interrupted: InterruptedException) {
                        break
                    }
                    val toggle = IpcBridge.toggleToken()
                    if (toggle != lastToggle) {
                        lastToggle = toggle
                        IpcBridge.acknowledgeToggle(toggle)
                        SwingUtilities.invokeLater { onToggleRequest() }
                    }
                    val syncRequest = IpcBridge.syncStamp()
                    if (syncRequest != lastSyncRequest) {
                        lastSyncRequest = syncRequest
                        io.launch { state.syncNow() }
                    }
                }
            }, "agenda-ipc").apply {
                isDaemon = true
                start()
            }
            onDispose {
                thread.interrupt()
                io.cancel()
            }
        }

        // Keeps "today", the month dots and the widget's status file current
        // even when the window stays open across midnight.
        LaunchedEffect(Unit) {
            while (true) {
                delay(REFRESH_MS)
                state.tick()
            }
        }

        Window(
            // The X button quits for real (there is no tray to hide into);
            // clicking outside only hides, so the widget stays warm.
            onCloseRequest = ::exitApplication,
            state = windowState,
            visible = isVisible,
            title = Strings.appName,
            icon = painterResource("icons/agenda.png"),
            onPreviewKeyEvent = { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else if (event.key != Key.Escape) {
                    false
                } else {
                    // Esc backs out one level: dialog, then window.
                    when {
                        state.editor != null -> { state.dismissEditor(); true }
                        state.syncOpen -> { state.closeSyncSettings(); true }
                        isVisible -> { applyVisibility(false); isVisible = false; true }
                        else -> false
                    }
                }
            }
        ) {
            // Launched by the panel icon: let the first layout pass settle,
            // then place the window right below/next to that icon. A menu
            // launch has no fresh anchor and keeps the centered position.
            LaunchedEffect(Unit) {
                delay(150)
                IpcBridge.loadAnchor()?.let { anchor ->
                    val (width, height) = windowSizePx()
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .maximumWindowBounds
                    val placed = WindowPlacement.below(anchor, width, height, screen)
                    windowState.position = WindowPosition(placed.x.dp, placed.y.dp)
                }
            }

            // Tracks whether this showing of the window got real keyboard
            // focus. Without that, a window the compositor refuses to focus
            // (some Wayland setups) would hide itself immediately.
            DisposableEffect(window) {
                appWindow = window
                val listener = object : WindowFocusListener {
                    override fun windowGainedFocus(e: WindowEvent) {
                        Startup.mark("focus-gained")
                        if (focusGainedAt == 0L) focusGainedAt = System.currentTimeMillis()
                    }

                    override fun windowLostFocus(e: WindowEvent) {
                        Startup.mark("focus-lost")
                    }
                }
                window.addWindowFocusListener(listener)
                onDispose {
                    window.removeWindowFocusListener(listener)
                    if (appWindow === window) appWindow = null
                }
            }

            // A click that arrived before the window existed (booting, or the
            // first frame still being composed) opens it here.
            LaunchedEffect(appWindow) {
                if (appWindow != null && pendingOpen) {
                    applyVisibility(true)
                    isVisible = true
                }
            }

            LaunchedEffect(Unit) { Startup.mark("content-composed") }
            AgendaApp(state, Xdg.kdeGlobalsFile)
        }
    }
}

/**
 * `true` when the pointer is outside our window, `false` while it hovers the
 * window, and `null` when the position cannot be trusted — in that case focus
 * is the only signal, so auto-hide stays enabled.
 *
 * Wayland is exactly that case: Compose runs through XWayland, and
 * `MouseInfo` keeps reporting the last place the pointer was over an X11
 * surface. Moving to a native Wayland window (the panel, the editor, almost
 * everything) never updates it, so a pointer that was inside the window when
 * it lost focus stays "inside" forever and the click outside never closes
 * anything. On a real X11 session the check is accurate and is kept.
 */
private fun pointerOutsideWindow(): Boolean? {
    if (isWaylandSession) return null
    return runCatching {
        val pointer = java.awt.MouseInfo.getPointerInfo()?.location ?: return@runCatching null
        val bounds = java.awt.Window.getWindows()
            .filter { it.isShowing }
            .map { it.bounds }
            .firstOrNull { it.contains(pointer) }
        bounds == null
    }.getOrNull()
}

/** `WAYLAND_DISPLAY` is set for the whole session, XWayland included. */
private val isWaylandSession: Boolean =
    !System.getenv("WAYLAND_DISPLAY").isNullOrBlank() ||
        System.getenv("XDG_SESSION_TYPE").equals("wayland", ignoreCase = true)

/** Window size in AWT pixels, which is what the placement calculations use. */
private fun windowSizePx(): Pair<Int, Int> {
    val scale = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .defaultScreenDevice
        .defaultConfiguration
        .defaultTransform
        .scaleX
    return (WindowWidth.value * scale).toInt() to (WindowHeight.value * scale).toInt()
}

/** Safety net only: the file watch normally wakes the IPC loop at once. */
private const val IPC_POLL_MS = 50L

/** `agenda --hidden`: start with only the process, no window. */
private const val START_HIDDEN_ARG = "--hidden"
private const val REFRESH_MS = 60_000L
