/*
 * Jotter panel widget (KDE Plasma 6).
 *
 * The app UI is Compose/JVM and cannot be embedded inside a plasmoid, so the
 * panel widget is a compact launcher: it shows the app icon plus the number of
 * notes, and shows/hides the Jotter window through the `jotter-toggle` helper.
 *
 * Note: in Plasma 6 the icon item is Kirigami.Icon; PlasmaCore.IconItem no
 * longer exists.
 */

pragma ComponentBehavior: Bound

import QtQuick
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import org.kde.plasma.plasmoid
import org.kde.plasma.components as PlasmaComponents3
import org.kde.plasma.plasma5support as P5Support

PlasmoidItem {
    id: root

    preferredRepresentation: compactRepresentation

    property int noteCount: 0
    property string lastNote: ""

    // Bundled with the package, so it works even without an icon theme entry.
    readonly property string appIcon: Qt.resolvedUrl("../icons/jotter.png")

    toolTipMainText: "Jotter"
    toolTipSubText: {
        if (root.noteCount <= 0) {
            return "Nenhuma nota ainda";
        }
        var label = root.noteCount === 1 ? "1 nota" : root.noteCount + " notas";
        if (root.lastNote.length > 0) {
            label += " · " + root.lastNote;
        }
        return label;
    }

    // The plasmoid cannot talk to the JVM directly: `jotter-toggle` touches a
    // file inside XDG_RUNTIME_DIR, which the running widget watches.
    P5Support.DataSource {
        id: toggleSource
        engine: "executable"
        connectedSources: []
        onNewData: function (sourceName, data) {
            toggleSource.disconnectSource(sourceName);
        }
    }

    P5Support.DataSource {
        id: statusSource
        engine: "executable"
        connectedSources: []
        onNewData: function (sourceName, data) {
            statusSource.disconnectSource(sourceName);
            try {
                const parsed = JSON.parse((data["stdout"] || "").trim() || "{}");
                root.noteCount = parsed.count || 0;
                root.lastNote = parsed.last || "";
            } catch (e) {
                // keep the previous values when the app is not running
            }
        }
    }

    Timer {
        interval: 5000
        running: true
        repeat: true
        triggeredOnStart: true
        onTriggered: root.refreshStatus()
    }

    Timer {
        id: refreshTimer
        interval: 900
        onTriggered: root.refreshStatus()
    }

    function refreshStatus() {
        statusSource.connectSource("sh -c 'exec \"$HOME/.local/bin/jotter-toggle\" status'");
    }

    function toggleWidget() {
        // Map the panel icon to screen coordinates so the card opens right
        // below it (or beside it, for a vertical panel).
        const p = root.mapToGlobal(0, 0);
        const w = Math.max(1, Math.round(root.width > 0 ? root.width : 24));
        const h = Math.max(1, Math.round(root.height > 0 ? root.height : 24));
        const anchor = " anchor " + Math.round(p.x) + " " + Math.round(p.y) + " " + w + " " + h;
        toggleSource.connectSource("sh -c 'exec \"$HOME/.local/bin/jotter-toggle\"" + anchor + "'");
        refreshTimer.restart();
    }

    compactRepresentation: MouseArea {
        id: compact

        implicitWidth: content.implicitWidth + Kirigami.Units.smallSpacing * 2
        implicitHeight: content.implicitHeight + Kirigami.Units.smallSpacing

        Layout.minimumWidth: implicitWidth
        Layout.maximumWidth: implicitWidth
        Layout.minimumHeight: implicitHeight

        hoverEnabled: true
        onClicked: root.toggleWidget()

        RowLayout {
            id: content
            anchors.centerIn: parent
            spacing: Kirigami.Units.smallSpacing

            Kirigami.Icon {
                source: root.appIcon
                fallback: "text-x-generic"
                implicitWidth: Kirigami.Units.iconSizes.smallMedium
                implicitHeight: Kirigami.Units.iconSizes.smallMedium
            }

            PlasmaComponents3.Label {
                text: root.noteCount
                visible: root.noteCount > 0
            }
        }
    }

    fullRepresentation: ColumnLayout {
        Layout.minimumWidth: Kirigami.Units.gridUnit * 13
        Layout.minimumHeight: Kirigami.Units.gridUnit * 9
        spacing: Kirigami.Units.smallSpacing

        Kirigami.Icon {
            source: root.appIcon
            fallback: "text-x-generic"
            implicitWidth: Kirigami.Units.iconSizes.huge
            implicitHeight: Kirigami.Units.iconSizes.huge
            Layout.alignment: Qt.AlignHCenter
        }

        PlasmaComponents3.Label {
            Layout.fillWidth: true
            horizontalAlignment: Text.AlignHCenter
            text: root.noteCount <= 0
                ? "Nenhuma nota ainda"
                : (root.noteCount === 1 ? "1 nota" : root.noteCount + " notas")
        }

        PlasmaComponents3.Label {
            Layout.fillWidth: true
            horizontalAlignment: Text.AlignHCenter
            visible: root.lastNote.length > 0
            opacity: 0.7
            elide: Text.ElideRight
            text: root.lastNote
        }

        PlasmaComponents3.Button {
            Layout.alignment: Qt.AlignHCenter
            icon.name: "jotter"
            text: "Abrir Jotter"
            onClicked: root.toggleWidget()
        }

        Item {
            Layout.fillHeight: true
        }
    }
}
