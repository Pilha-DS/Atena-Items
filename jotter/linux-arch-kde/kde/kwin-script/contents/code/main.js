/*
 * KWin script: makes the Jotter windows behave like desktop widgets.
 *
 * - never shows up in the taskbar, the pager or the window switcher
 *   (Alt+Tab / Ctrl+Tab)
 * - stays above normal windows (the app also sets AlwaysOnTop itself)
 *
 * The window is matched by caption, resource class and desktop entry, so the
 * localized titles ("Jotter · Nota geral", "O Jotter está bloqueado") and the
 * companion window (general note) is covered too.
 */

var APP_ID = "jotter";

function mentions(value, needle) {
    return ("" + value).toLowerCase().indexOf(needle) !== -1;
}

function isJotter(window) {
    try {
        if (mentions(window.caption, APP_ID)) {
            return true;
        }
    } catch (e) {
        // caption not readable on this window
    }
    try {
        if (mentions(window.resourceClass, APP_ID)) {
            return true;
        }
    } catch (e) {
        // property not available in this KWin version
    }
    try {
        if (mentions(window.desktopFileName, APP_ID + ".desktop")) {
            return true;
        }
    } catch (e) {
        // property not available in this KWin version
    }
    return false;
}

function applyWidgetRules(window) {
    if (!isJotter(window)) {
        return;
    }
    // One try per flag: not every KWin version exposes all three.
    try {
        window.skipTaskbar = true;
    } catch (e) {
        // property not available in this KWin version
    }
    try {
        window.skipPager = true;
    } catch (e) {
        // property not available in this KWin version
    }
    // This one is what keeps the window out of Ctrl+Tab/Alt+Tab.
    try {
        window.skipSwitcher = true;
    } catch (e) {
        // property not available in this KWin version
    }
}

function init() {
    var windows = workspace.windowList();
    for (var i = 0; i < windows.length; i++) {
        applyWidgetRules(windows[i]);
    }
    workspace.windowAdded.connect(applyWidgetRules);
}

init();
