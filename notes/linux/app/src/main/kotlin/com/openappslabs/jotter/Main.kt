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

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.openappslabs.jotter

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.navigation.compose.rememberNavController
import com.openappslabs.jotter.data.model.AppTheme
import com.openappslabs.jotter.data.repository.UserPreferences
import com.openappslabs.jotter.data.source.DatabaseFactory
import com.openappslabs.jotter.di.AppGraph
import com.openappslabs.jotter.navigation.AppNavHost
import com.openappslabs.jotter.navigation.AppRoutes
import com.openappslabs.jotter.platform.AppLock
import com.openappslabs.jotter.platform.AutoSave
import com.openappslabs.jotter.platform.BackDispatcher
import com.openappslabs.jotter.platform.ClipboardWindow
import com.openappslabs.jotter.platform.Diagnostics
import com.openappslabs.jotter.platform.ExternalLinks
import com.openappslabs.jotter.platform.IpcBridge
import com.openappslabs.jotter.platform.LocalBackDispatcher
import com.openappslabs.jotter.platform.PinStore
import com.openappslabs.jotter.platform.ScratchpadSaver
import com.openappslabs.jotter.platform.ScratchpadWindow
import com.openappslabs.jotter.platform.Startup
import com.openappslabs.jotter.platform.Toaster
import com.openappslabs.jotter.platform.WindowStateStore
import com.openappslabs.jotter.platform.Xdg
import com.openappslabs.jotter.ui.PinDialog
import com.openappslabs.jotter.ui.screens.clipboardscreen.ClipboardScreen
import com.openappslabs.jotter.ui.screens.linksscreen.LinksPanel
import com.openappslabs.jotter.ui.screens.scratchpadscreen.ScratchpadScreen
import com.openappslabs.jotter.ui.i18n.LocalStrings
import com.openappslabs.jotter.ui.i18n.stringsFor
import com.openappslabs.jotter.ui.theme.JotterTheme
import com.openappslabs.jotter.ui.theme.rememberKdePalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Cursor
import java.awt.SystemTray
import kotlin.math.roundToInt

private val WidgetShape = RoundedCornerShape(18.dp)

/**
 * How the left companion window is divided: the clipboard editor keeps the top
 * 70%, the link inbox gets the bottom 30%.
 */
private const val CLIPBOARD_SHARE = 0.7f
private const val LINKS_SHARE = 0.3f

/** How long the widget waits, unfocused, before it closes itself. */
private const val FOCUS_LOSS_DELAY_MS = 250L

/**
 * Focus must be held this long before losing it counts as "the user clicked
 * outside". Without it, the brief focus blip when the window is first shown
 * (and the focus-stealing prevention of the compositor) would close it.
 */
private const val FOCUS_ARM_DELAY_MS = 600L

/** No self-closing right after opening, whatever the focus state is. */
private const val FOCUS_GRACE_AFTER_SHOW_MS = 1_200L

/**
 * How long the notes list waits for the general note before loading anyway.
 * Only reached when that window is shown but could not read its row.
 */
private const val GENERAL_NOTE_WAIT_MS = 4_000L

fun main() {
    Xdg.ensureDirectories()
    System.setProperty("awt.useSystemAAFontSettings", "on")

    // A widget is a singleton: a second launch becomes a show/hide request.
    if (!IpcBridge.acquireSingleInstanceLock()) {
        IpcBridge.requestToggle()
        return
    }
    IpcBridge.publishRuntimeDir()

    // Logout/reboot sends SIGTERM, which never runs the Compose code: the only
    // chance to persist what was typed is a shutdown hook.
    Runtime.getRuntime().addShutdownHook(Thread({
        AutoSave.flushAllBlocking()
        ScratchpadSaver.checkpointBlocking()
    }, "jotter-shutdown-flush"))

    application {
        val (initialWidth, initialHeight) = remember { WindowStateStore.defaultSize() }
        val windowState = rememberWindowState(
            size = DpSize(initialWidth.dp, initialHeight.dp),
            // Compose must not place the window itself: it would override the
            // anchor position applied below via AWT.
            position = WindowPosition.PlatformDefault
        )
        val backDispatcher = remember { BackDispatcher() }
        val navController = rememberNavController()
        DisposableEffect(navController) {
            backDispatcher.setEscapeHandler {
                navController.popBackStack()
            }
            onDispose { backDispatcher.setEscapeHandler(null) }
        }

        var isVisible by remember { mutableStateOf(true) }
        var alwaysOnTop by remember { mutableStateOf(true) }
        // Handles of the notes window and of its two companions: the clipboard
        // editor on the left and the "general note" on the right.
        var mainWindowHandle by remember { mutableStateOf<java.awt.Window?>(null) }
        var mainBounds by remember { mutableStateOf<java.awt.Rectangle?>(null) }
        var clipboardWindowHandle by remember { mutableStateOf<java.awt.Window?>(null) }
        var scratchpadWindowHandle by remember { mutableStateOf<java.awt.Window?>(null) }
        // Set when the panel icon asks for the window, so it opens right next
        // to (or below) that icon instead of at the saved position.
        var pendingAnchor by remember { mutableStateOf<IpcBridge.Anchor?>(null) }
        val traySupported = remember { SystemTray.isSupported() }

        val preferencesRepository = AppGraph.userPreferencesRepository
        val preferences by preferencesRepository.userPreferencesFlow.collectAsState(initial = UserPreferences())
        // Strings used outside the themed content (window titles, tray menu).
        val appStrings = remember(preferences.appLanguage) {
            stringsFor(preferences.appLanguage)
        }
        val scratchpadTitle = appStrings.generalNoteTitle
        val clipboardTitle = appStrings.clipboardTitle
        val kdePalette = rememberKdePalette()

        val isDark = when (preferences.appTheme) {
            AppTheme.LIGHT -> false
            AppTheme.DARK -> true
            AppTheme.SYSTEM -> kdePalette.isDark
        }

        LaunchedEffect(preferences.isAppLockEnabled) {
            if (preferences.isAppLockEnabled && PinStore.hasPin()) {
                AppLock.lock()
            }
        }

        // The widget behaves like a popup: losing focus closes it, and whatever
        // was being typed is written to the database first.
        // JOTTER_KEEP_OPEN=1 disables that (useful for screenshots/debugging).
        val autoHideOnBlur = remember { System.getenv("JOTTER_KEEP_OPEN") == null }
        var focusGainedAt by remember { mutableStateOf(0L) }
        var focusArmed by remember { mutableStateOf(false) }
        var shownAt by remember { mutableStateOf(System.currentTimeMillis()) }
        var hiddenByFocusAt by remember { mutableStateOf(0L) }

        LaunchedEffect(Unit) {
            var unfocusedSince = 0L
            // Links open the browser, which takes the focus away. That is not
            // "the user clicked outside", so the automatic close is suspended
            // until this window is focused again.
            var lastExternalOpen = ExternalLinks.lastOpenedAt
            while (true) {
                delay(100)
                if (!isVisible || !autoHideOnBlur) {
                    unfocusedSince = 0L
                    lastExternalOpen = ExternalLinks.lastOpenedAt
                    continue
                }
                val now = System.currentTimeMillis()
                if (ExternalLinks.lastOpenedAt != lastExternalOpen) {
                    lastExternalOpen = ExternalLinks.lastOpenedAt
                    focusArmed = false
                    unfocusedSince = 0L
                    continue
                }
                // Our own popups/dialogs keep the widget alive while they are
                // focused (dropdowns, PIN dialog, action sheets).
                val ownFocused = java.awt.Window.getWindows().any { it.isShowing && it.isFocused }

                if (ownFocused) {
                    if (focusGainedAt == 0L) focusGainedAt = now
                    if (now - focusGainedAt >= FOCUS_ARM_DELAY_MS) focusArmed = true
                    unfocusedSince = 0L
                    continue
                }

                focusGainedAt = 0L
                // Not armed yet: the window was never really focused, so this
                // is not the user clicking away.
                if (!focusArmed || now - shownAt < FOCUS_GRACE_AFTER_SHOW_MS) {
                    unfocusedSince = 0L
                    continue
                }
                if (unfocusedSince == 0L) {
                    unfocusedSince = now
                } else if (now - unfocusedSince >= FOCUS_LOSS_DELAY_MS) {
                    unfocusedSince = 0L
                    focusArmed = false
                    AutoSave.flushAll()
                    isVisible = false
                    hiddenByFocusAt = System.currentTimeMillis()
                }
            }
        }

        // `jotter-toggle`, the plasmoid or a second launch all touch this file.
        LaunchedEffect(Unit) {
            var lastStamp = IpcBridge.toggleStamp()
            while (true) {
                delay(160)
                val stamp = IpcBridge.toggleStamp()
                if (stamp != lastStamp) {
                    lastStamp = stamp
                    // Clicking the panel icon while the widget is open also
                    // removes its focus. In that case the request means "close",
                    // which the focus watcher already took care of.
                    if (System.currentTimeMillis() - hiddenByFocusAt < 700) {
                        continue
                    }
                    // Only re-anchor when the window is about to be shown, so
                    // reopening it from the tray keeps the position the user
                    // dragged it to.
                    pendingAnchor = if (!isVisible) IpcBridge.loadAnchor() else null
                    if (isVisible) AutoSave.flushAll() // about to hide
                    isVisible = !isVisible
                }
            }
        }

        // Sync keeps running on the app scope even while the widget is hidden,
        // so a note written on the phone is already here when it reopens. It
        // no-ops until a server URL and token are saved.
        LaunchedEffect(Unit) {
            // A sync problem (packaging missing a module, bad saved token,
            // unreachable server) must never take the notes UI down with it.
            runCatching { AppGraph.syncEngine.start() }
                .onFailure { Diagnostics.log("sync start failed: ${it.stackTraceToString()}") }
        }

        // Keep the plasmoid counter in sync with the database. Only the count
        // and the last title are read, and only after the general note is on
        // screen: opening Jotter shows the text first, everything else after.
        LaunchedEffect(Unit) {
            Startup.awaitGeneralNote()
            AppGraph.notesRepository.observeStatus().collect { status ->
                IpcBridge.publishStatus(status.count, status.latestTitle)
            }
        }

        // Safety net: the gate must never stay closed. Without a general note
        // window there is nothing to wait for, and a failing read must not keep
        // the list from ever loading.
        LaunchedEffect(Unit) {
            if (!ScratchpadWindow.isVisible) {
                Startup.onGeneralNoteReady()
                return@LaunchedEffect
            }
            withTimeoutOrNull(GENERAL_NOTE_WAIT_MS) { Startup.awaitGeneralNote() }
            Startup.onGeneralNoteReady()
        }

        // Never lose an in-flight auto-save when the app is closed: persist and
        // checkpoint before tearing the composition down.
        val appScope = rememberCoroutineScope()
        val quit: () -> Unit = {
            AutoSave.flushAllBlocking()
            appScope.launch {
                DatabaseFactory.checkpoint()
                exitApplication()
            }
        }

        Window(
            onCloseRequest = {
                if (traySupported) {
                    AutoSave.flushAllBlocking()
                    isVisible = false
                } else {
                    quit()
                }
            },
            visible = isVisible,
            state = windowState,
            title = "Jotter",
            icon = painterResource("icons/jotter.png"),
            undecorated = true,
            transparent = true,
            resizable = false,
            onPreviewKeyEvent = { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when {
                        event.isCtrlPressed && event.key == Key.Q -> {
                            quit()
                            true
                        }
                        event.key == Key.Escape -> backDispatcher.handleBack()
                        else -> false
                    }
                } else false
            }
        ) {
            LaunchedEffect(Unit) {
                // Let the first layout pass happen so the size is final before
                // deciding where the card goes.
                delay(150)
                runCatching {
                    // Launched by the panel icon: use that icon as the anchor.
                    val anchor = IpcBridge.loadAnchor()
                    val target = if (anchor != null) {
                        WindowStateStore.positionForAnchor(anchor, window.width, window.height)
                    } else {
                        WindowStateStore.loadPosition()
                            ?: WindowStateStore.defaultPosition(window.width, window.height)
                    }
                    window.setLocation(target.x, target.y)
                }
            }
            LaunchedEffect(alwaysOnTop) { window.isAlwaysOnTop = alwaysOnTop }

            // The companion window follows this one, so its bounds have to be
            // tracked even when it is moved by the window manager.
            SideEffect { mainWindowHandle = window }
            DisposableEffect(window) {
                val listener = object : java.awt.event.ComponentAdapter() {
                    override fun componentMoved(e: java.awt.event.ComponentEvent) {
                        mainBounds = window.bounds
                    }

                    override fun componentResized(e: java.awt.event.ComponentEvent) {
                        mainBounds = window.bounds
                    }
                }
                window.addComponentListener(listener)
                mainBounds = window.bounds
                onDispose { window.removeComponentListener(listener) }
            }

            // Tracks whether this showing of the widget got real keyboard focus.
            // Without that, a window that cannot grab focus (some Wayland
            // setups) would close itself immediately.
            DisposableEffect(window) {
                val listener = object : java.awt.event.WindowFocusListener {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent) {
                        if (focusGainedAt == 0L) focusGainedAt = System.currentTimeMillis()
                    }

                    override fun windowLostFocus(e: java.awt.event.WindowEvent) = Unit
                }
                window.addWindowFocusListener(listener)
                onDispose { window.removeWindowFocusListener(listener) }
            }

            LaunchedEffect(isVisible) {
                window.isVisible = isVisible
                if (isVisible) {
                    runCatching {
                        window.toFront()
                        window.requestFocus()
                    }
                } else {
                    // Next time it opens it has to earn focus again.
                    focusArmed = false
                    focusGainedAt = 0L
                }
                shownAt = System.currentTimeMillis()
            }
            // Re-anchor every time the panel icon shows the window again.
            LaunchedEffect(pendingAnchor, isVisible) {
                val anchor = pendingAnchor ?: return@LaunchedEffect
                if (!isVisible) return@LaunchedEffect
                runCatching {
                    val target = WindowStateStore.positionForAnchor(anchor, window.width, window.height)
                    window.setLocation(target.x, target.y)
                }
                pendingAnchor = null
            }

            JotterTheme(
                isDarkTheme = isDark,
                isTrueBlackEnabled = preferences.isTrueBlackEnabled,
                isDynamicColor = preferences.isDynamicColor,
                isHapticEnabled = preferences.isHapticEnabled,
                language = preferences.appLanguage
            ) {
                CompositionLocalProvider(LocalBackDispatcher provides backDispatcher) {
                    // Transparent margin so the card can cast a soft shadow.
                    Box(modifier = Modifier.fillMaxSize().padding(7.dp)) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = WidgetShape,
                            color = MaterialTheme.colorScheme.background,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            ),
                            shadowElevation = 14.dp
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                DragGrip(window = window)
                                Box(Modifier.weight(1f)) {
                                    if (preferences.isAppLockEnabled && PinStore.hasPin() && !AppLock.isUnlocked) {
                                        LockedScreen()
                                    } else {
                                        AppNavHost(
                                            navController = navController,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }
                        ToastOverlay()
                        AppLock.request?.let { PinDialog(it) }
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // Clipboard: a third, title-less window glued to the left of the widget.
        // It edits the system clipboard itself, not a note.
        // ------------------------------------------------------------------
        if (ClipboardWindow.isVisible) {
            val clipboardState = rememberWindowState(
                size = DpSize(
                    WindowStateStore.CLIPBOARD_WIDTH_DP.dp,
                    initialHeight.dp
                ),
                position = WindowPosition.PlatformDefault
            )
            val clipboardAllowed =
                !preferences.isAppLockEnabled || !PinStore.hasPin() || AppLock.isUnlocked

            Window(
                onCloseRequest = { ClipboardWindow.hide() },
                visible = isVisible && clipboardAllowed,
                state = clipboardState,
                title = clipboardTitle,
                icon = painterResource("icons/jotter.png"),
                undecorated = true,
                transparent = true,
                resizable = false,
                alwaysOnTop = true
            ) {
                LaunchedEffect(alwaysOnTop) { window.isAlwaysOnTop = alwaysOnTop }
                SideEffect { clipboardWindowHandle = window }

                var positioned by remember { mutableStateOf(false) }
                LaunchedEffect(
                    isVisible,
                    clipboardAllowed,
                    mainBounds,
                    scratchpadWindowHandle,
                    ScratchpadWindow.isVisible
                ) {
                    if (!isVisible || !clipboardAllowed) return@LaunchedEffect
                    val main = mainWindowHandle ?: return@LaunchedEffect
                    if (!positioned) {
                        // Let the widget finish its own placement (150 ms) so the
                        // companion does not jump right after appearing.
                        delay(250)
                        positioned = true
                    }
                    val bounds = mainBounds ?: main.bounds
                    val placement = WindowStateStore.glueCluster(
                        main = bounds,
                        clipboardWidth = window.width,
                        scratchpadWidth = if (ScratchpadWindow.isVisible) {
                            scratchpadWindowHandle?.width ?: 0
                        } else {
                            0
                        },
                        height = window.height
                    )
                    placement.clipboard?.let { point ->
                        runCatching { window.setLocation(point.x, point.y) }
                    }
                    if (placement.main.x != bounds.x || placement.main.y != bounds.y) {
                        runCatching { main.setLocation(placement.main.x, placement.main.y) }
                    }
                }

                JotterTheme(
                    isDarkTheme = isDark,
                    isTrueBlackEnabled = preferences.isTrueBlackEnabled,
                    isDynamicColor = preferences.isDynamicColor,
                    isHapticEnabled = preferences.isHapticEnabled,
                    language = preferences.appLanguage
                ) {
                    Box(modifier = Modifier.fillMaxSize().padding(7.dp)) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = WidgetShape,
                            color = MaterialTheme.colorScheme.background,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            ),
                            shadowElevation = 14.dp
                        ) {
                            // The left column is two desks: the clipboard on
                            // top, the link inbox as a strip below it. 70/30,
                            // so the editor still gets the room it had.
                            Column(modifier = Modifier.fillMaxSize()) {
                                ClipboardScreen(
                                    window = window,
                                    onClose = { ClipboardWindow.hide() },
                                    modifier = Modifier.weight(CLIPBOARD_SHARE)
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    thickness = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                )
                                LinksPanel(
                                    modifier = Modifier.weight(LINKS_SHARE),
                                    onOpenFullScreen = {
                                        isVisible = true
                                        navController.navigate(AppRoutes.Links)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // General note: a second, title-less window glued to the right of the
        // widget. Same app theme, no navigation, just plain text.
        // ------------------------------------------------------------------
        if (ScratchpadWindow.isVisible) {
            val scratchpadState = rememberWindowState(
                size = DpSize(
                    WindowStateStore.SCRATCHPAD_WIDTH_DP.dp,
                    initialHeight.dp
                ),
                position = WindowPosition.PlatformDefault
            )
            // Hidden while Jotter is locked, shown again as soon as it unlocks.
            val scratchpadAllowed =
                !preferences.isAppLockEnabled || !PinStore.hasPin() || AppLock.isUnlocked

            Window(
                onCloseRequest = { ScratchpadWindow.hide() },
                visible = isVisible && scratchpadAllowed,
                state = scratchpadState,
                title = scratchpadTitle,
                icon = painterResource("icons/jotter.png"),
                undecorated = true,
                transparent = true,
                resizable = false,
                alwaysOnTop = true
            ) {
                LaunchedEffect(alwaysOnTop) { window.isAlwaysOnTop = alwaysOnTop }
                SideEffect { scratchpadWindowHandle = window }

                // Glue to the right edge of the notes window, following it when
                // it is dragged or re-anchored. The clipboard window counts
                // too, so the three of them stay in a single row.
                var positioned by remember { mutableStateOf(false) }
                LaunchedEffect(
                    isVisible,
                    scratchpadAllowed,
                    mainBounds,
                    clipboardWindowHandle,
                    ClipboardWindow.isVisible
                ) {
                    if (!isVisible || !scratchpadAllowed) return@LaunchedEffect
                    val main = mainWindowHandle ?: return@LaunchedEffect
                    if (!positioned) {
                        // Let the widget finish its own placement (150 ms) so the
                        // companion does not jump right after appearing.
                        delay(250)
                        positioned = true
                    }
                    val bounds = mainBounds ?: main.bounds
                    val placement = WindowStateStore.glueCluster(
                        main = bounds,
                        clipboardWidth = if (ClipboardWindow.isVisible) {
                            clipboardWindowHandle?.width ?: 0
                        } else {
                            0
                        },
                        scratchpadWidth = window.width,
                        height = window.height
                    )
                    placement.scratchpad?.let { point ->
                        runCatching { window.setLocation(point.x, point.y) }
                    }
                    if (placement.main.x != bounds.x || placement.main.y != bounds.y) {
                        runCatching { main.setLocation(placement.main.x, placement.main.y) }
                    }
                }

                JotterTheme(
                    isDarkTheme = isDark,
                    isTrueBlackEnabled = preferences.isTrueBlackEnabled,
                    isDynamicColor = preferences.isDynamicColor,
                    isHapticEnabled = preferences.isHapticEnabled,
                    language = preferences.appLanguage
                ) {
                    Box(modifier = Modifier.fillMaxSize().padding(7.dp)) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = WidgetShape,
                            color = MaterialTheme.colorScheme.background,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            ),
                            shadowElevation = 14.dp
                        ) {
                            ScratchpadScreen(
                                window = window,
                                onClose = { ScratchpadWindow.hide() },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }

        if (traySupported) {
            Tray(
                icon = painterResource("icons/jotter.png"),
                tooltip = appStrings.appName,
                onAction = { isVisible = true }
            ) {
                Item(
                    text = if (isVisible) appStrings.hideWidget else appStrings.showWidget,
                    onClick = { isVisible = !isVisible }
                )
                CheckboxItem(
                    appStrings.alwaysOnTop,
                    checked = alwaysOnTop,
                    onCheckedChange = { alwaysOnTop = it }
                )
                CheckboxItem(
                    appStrings.generalNote,
                    checked = ScratchpadWindow.isVisible,
                    onCheckedChange = { if (it) ScratchpadWindow.show() else ScratchpadWindow.hide() }
                )
                CheckboxItem(
                    appStrings.clipboard,
                    checked = ClipboardWindow.isVisible,
                    onCheckedChange = { if (it) ClipboardWindow.show() else ClipboardWindow.hide() }
                )
                Item(
                    text = appStrings.linkInbox,
                    onClick = {
                        isVisible = true
                        navController.navigate(AppRoutes.Links)
                    }
                )
                Separator()
                Item(appStrings.quit, onClick = quit)
            }
        }
    }
}

/**
 * Invisible handle at the top of the card. Undecorated windows have no title
 * bar, so this is what lets the widget be dragged around the desktop.
 */
@Composable
private fun DragGrip(window: java.awt.Window) {
    val density = LocalDensity.current.density
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)))
            .pointerInput(window) {
                detectDragGestures(
                    onDragEnd = {
                        WindowStateStore.savePosition(window.location.x, window.location.y)
                    }
                ) { change, dragAmount ->
                    change.consume()
                    val location = window.location
                    window.setLocation(
                        location.x + (dragAmount.x / density).roundToInt(),
                        location.y + (dragAmount.y / density).roundToInt()
                    )
                }
            }
    )
}

@Composable
private fun ToastOverlay() {
    val message = Toaster.message ?: return
    LaunchedEffect(message.id) {
        delay(2_400)
        Toaster.dismiss(message.id)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun LockedScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val strings = LocalStrings.current
            Text(strings.jotterLocked, style = MaterialTheme.typography.headlineSmall)
            Text(strings.enterPinToContinue, style = MaterialTheme.typography.bodyMedium)
            TextButton(
                onClick = {
                    AppLock.requestUnlock(
                        title = strings.jotterLocked,
                        subtitle = strings.enterYourPin,
                        onSuccess = { AppLock.onUnlocked() }
                    )
                }
            ) { Text(strings.unlock) }
        }
    }
}
