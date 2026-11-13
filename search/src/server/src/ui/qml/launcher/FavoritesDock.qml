pragma ComponentBehavior: Bound
import QtQuick
import Vicinae

/// Thin vertical strip of favorite item icons, attached to the left of the launcher.
/// Left click activates the item, right click opens its action panel. It can also take
/// keyboard focus: the arrow keys move the highlight, Enter activates and the action
/// panel shortcut opens the tools of the highlighted item.
Item {
    id: root

    required property LauncherAppearance appearance
    readonly property int cellSize: 44
    readonly property int spacing: 4
    readonly property int count: Launcher.favoriteDockItems.length
    readonly property bool keyboardMode: Launcher.favoriteDockKeyboardActive
    readonly property string currentId: (root.currentIndex >= 0 && root.currentIndex < root.count) ? Launcher.favoriteDockItems[root.currentIndex].id : ""
    property int currentIndex: -1

    signal itemActivated(string id)
    signal itemActionsRequested(string id)
    signal searchRequested()
    signal textTyped(string text)

    /// Hands the keyboard to the dock, selecting the current or first favorite.
    function focusDock() {
        if (root.count === 0 || !root.visible)
            return;
        _select(root.currentIndex < 0 ? 0 : root.currentIndex);
        Launcher.favoriteDockKeyboardActive = true;
        root.forceActiveFocus();
    }

    /// Hands the keyboard back to whoever asked for it (the search input, usually).
    function releaseKeyboard() {
        if (root.keyboardMode)
            Launcher.favoriteDockKeyboardActive = false;
        root.focus = false;
    }

    function _select(index) {
        if (root.count === 0)
            return;
        const next = Math.max(0, Math.min(index, root.count - 1));
        if (next === root.currentIndex)
            return;
        root.currentIndex = next;
        _ensureVisible(next);
    }

    function _ensureVisible(index) {
        const y = index * (root.cellSize + root.spacing);
        const top = flickable.contentY;
        const bottom = top + flickable.height;
        if (y < top)
            flickable.contentY = y;
        else if (y + root.cellSize > bottom)
            flickable.contentY = y + root.cellSize - flickable.height;
    }

    onCountChanged: {
        if (root.count === 0) {
            root.currentIndex = -1;
            root.releaseKeyboard();
            return;
        }
        if (root.currentIndex >= root.count) {
            root.currentIndex = root.count - 1;
            _ensureVisible(root.currentIndex);
        }
    }

    // The native side drops keyboard ownership by itself (view pushed, window hidden),
    // so the dock has to let go of the focus and hand typing back to the search input.
    onKeyboardModeChanged: {
        if (root.keyboardMode) {
            root.forceActiveFocus();
            return;
        }
        root.focus = false;
        root.searchRequested();
    }

    // The dock disappears on compaction and overlays; the keyboard must not stay
    // trapped in an invisible strip.
    onVisibleChanged: {
        if (!root.visible)
            root.releaseKeyboard();
    }

    Keys.onPressed: function (event) {
        if (!root.keyboardMode)
            return;

        switch (event.key) {
        case Qt.Key_Up:
            root._select(Launcher.favoriteDockNeighbor(root.currentIndex, -1));
            event.accepted = true;
            return;
        case Qt.Key_Down:
            root._select(Launcher.favoriteDockNeighbor(root.currentIndex, 1));
            event.accepted = true;
            return;
        case Qt.Key_PageUp:
            root._select(root.currentIndex - 4);
            event.accepted = true;
            return;
        case Qt.Key_PageDown:
            root._select(root.currentIndex + 4);
            event.accepted = true;
            return;
        case Qt.Key_Home:
            root._select(0);
            event.accepted = true;
            return;
        case Qt.Key_End:
            root._select(root.count - 1);
            event.accepted = true;
            return;
        case Qt.Key_Return:
        case Qt.Key_Enter:
            if (root.currentId !== "")
                root.itemActivated(root.currentId);
            event.accepted = true;
            return;
        case Qt.Key_Right:
        case Qt.Key_Tab:
            root.releaseKeyboard();
            event.accepted = true;
            return;
        default:
            break;
        }

        // Anything printable is meant for the search input, so hand the keyboard
        // back and forward the character instead of swallowing the keystroke.
        if (event.text !== "" && event.text.charCodeAt(0) >= 0x20) {
            const text = event.text;
            root.releaseKeyboard();
            root.textTyped(text);
            event.accepted = true;
        }
    }

    Rectangle {
        id: strip
        anchors.fill: parent
        anchors.margins: 6
        radius: root.appearance.rowRadius
        color: Config.withAlpha(Theme.listItemHoverBg, 0.4 * Config.windowOpacity)
    }

    Rectangle {
        anchors.fill: parent
        anchors.margins: 6
        radius: root.appearance.rowRadius
        visible: root.keyboardMode
        color: "transparent"
        border.width: 2
        border.color: Config.withAlpha(Theme.buttonFocusOutline, Config.windowOpacity)
    }

    Flickable {
        id: flickable
        anchors.fill: strip
        anchors.topMargin: 8
        anchors.bottomMargin: 8
        contentWidth: width
        contentHeight: column.implicitHeight
        clip: true
        interactive: contentHeight > height
        boundsBehavior: Flickable.StopAtBounds

        ViciWheelHandler {
            target: flickable
        }

        Column {
            id: column
            width: flickable.width
            spacing: root.spacing

            Repeater {
                model: Launcher.favoriteDockItems

                Item {
                    id: cell
                    required property int index
                    required property var modelData

                    width: column.width
                    height: root.cellSize

                    Accessible.role: Accessible.Button
                    Accessible.name: cell.modelData.title

                    Rectangle {
                        anchors.fill: parent
                        anchors.margins: 2
                        radius: root.appearance.rowRadius
                        color: {
                            if (root.keyboardMode && cell.index === root.currentIndex)
                                return root.appearance.selectionFill;
                            if (mouse.containsMouse)
                                return root.appearance.hoverFill;
                            return "transparent";
                        }
                    }

                    ViciImage {
                        anchors.centerIn: parent
                        anchors.verticalCenterOffset: cell.modelData.isActive ? -2 : 0
                        width: 26
                        height: 26
                        source: cell.modelData.iconSource
                        safetyMargins: true
                    }

                    Rectangle {
                        visible: cell.modelData.isActive
                        width: 4
                        height: 4
                        radius: 2
                        color: Theme.textMuted
                        anchors.horizontalCenter: parent.horizontalCenter
                        anchors.bottom: parent.bottom
                        anchors.bottomMargin: 8
                    }

                    MouseArea {
                        id: mouse
                        anchors.fill: parent
                        hoverEnabled: true
                        acceptedButtons: Qt.LeftButton | Qt.RightButton
                        onClicked: function (event) {
                            root._select(cell.index);
                            if (event.button === Qt.RightButton)
                                root.itemActionsRequested(cell.modelData.id);
                            else
                                root.itemActivated(cell.modelData.id);
                        }

                        ViciToolTip {
                            visible: mouse.containsMouse && HoverActivation.active
                            text: cell.modelData.title
                        }
                    }
                }
            }
        }
    }
}
