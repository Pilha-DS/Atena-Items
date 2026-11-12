/*
 * KWin script: makes the Jotter window behave like a desktop widget.
 *
 * - never shows up in the taskbar or the desktop pager
 * - stays above normal windows (the app also sets AlwaysOnTop itself)
 *
 * Windows are matched by caption "Jotter", which the app always uses.
 */

function isJotter(window) {
    try {
        var caption = "" + window.caption;
        return caption === "Jotter" || caption.indexOf("Jotter") === 0;
    } catch (e) {
        return false;
    }
}

function applyWidgetRules(window) {
    if (!isJotter(window)) {
        return;
    }
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
}

function init() {
    var windows = workspace.windowList();
    for (var i = 0; i < windows.length; i++) {
        applyWidgetRules(windows[i]);
    }
    workspace.windowAdded.connect(applyWidgetRules);
}

init();
