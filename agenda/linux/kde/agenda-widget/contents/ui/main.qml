/*
 * Agenda panel widget (KDE Plasma 6).
 *
 * The app itself is Compose/JVM and cannot be embedded inside a plasmoid, so
 * the panel widget is a compact card: time on top, date below (the layout in
 * the reference screenshot) and a click shows/hides the Agenda window through
 * the `agenda-toggle` helper.
 *
 * Everything that needs logic (which events are today, what comes next) is
 * computed by the app and published as JSON; the widget only formats it.
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

    // Bundled with the package, so it works even without an icon theme entry.
    readonly property string appIcon: Qt.resolvedUrl("../icons/agenda.png")

    // Ticked every second so the clock stays accurate without polling the app.
    property double now: Date.now()

    property var events: []
    property var todayEvents: []
    property string dayKey: ""
    property bool syncConfigured: false
    property string syncError: ""

    readonly property string timeText: Qt.formatTime(new Date(root.now), "hh:mm")
    readonly property string dateText: Qt.formatDate(new Date(root.now), "dd/MM/yy")
    readonly property string fullDateText: Qt.formatDate(new Date(root.now), "dddd, d 'de' MMMM")

    // Compact card: 2 lines, like the reference image. The pixels scale with
    // the panel font so the card keeps the same proportion on HiDPI screens.
    readonly property int timePixelSize: Math.max(11, Math.round(Kirigami.Units.gridUnit * 0.72))
    readonly property int datePixelSize: Math.max(10, Math.round(Kirigami.Units.gridUnit * 0.66))

    toolTipMainText: "Agenda"
    toolTipSubText: {
        var total = root.todayEvents.length;
        if (total === 0) {
            return "Nenhum evento hoje";
        }
        var label = total === 1 ? "1 evento hoje" : total + " eventos hoje";
        var next = root.nextEvent();
        if (next) {
            var when = next.allDay ? "Dia inteiro" : Qt.formatTime(new Date(next.start), "hh:mm");
            label += " · " + when + " " + next.title;
        }
        return label;
    }

    // The plasmoid cannot talk to the JVM directly: `agenda-toggle` touches a
    // file inside XDG_RUNTIME_DIR, which the running app watches.
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
                root.events = parsed.events || [];
                root.syncConfigured = !!(parsed.sync && parsed.sync.configured);
                root.syncError = (parsed.sync && parsed.sync.error) || "";
                root.refreshToday(true);
            } catch (e) {
                // keep the previous values when the app is not running
            }
        }
    }

    P5Support.DataSource {
        id: actionSource
        engine: "executable"
        connectedSources: []
        onNewData: function (sourceName, data) {
            actionSource.disconnectSource(sourceName);
        }
    }

    Timer {
        interval: 1000
        running: true
        repeat: true
        triggeredOnStart: true
        onTriggered: {
            root.now = Date.now();
            root.refreshToday(false);
        }
    }

    Timer {
        interval: 5000
        running: true
        repeat: true
        triggeredOnStart: true
        onTriggered: root.refreshStatus()
    }

    // Starts the app hidden as soon as the panel loads: the JVM/Compose warm-up
    // (~2 s) happens here, not on the first click.
    Timer {
        interval: 1000
        running: true
        onTriggered: root.prewarm()
    }

    Timer {
        id: refreshTimer
        interval: 900
        onTriggered: root.refreshStatus()
    }

    function refreshStatus() {
        statusSource.connectSource("sh -c 'exec \"$HOME/.local/bin/agenda-toggle\" status'");
    }

    function prewarm() {
        statusSource.connectSource("sh -c '$HOME/.local/bin/agenda-toggle prewarm'");
    }

    function requestSync() {
        actionSource.connectSource("sh -c 'exec \"$HOME/.local/bin/agenda-toggle\" sync'");
        refreshTimer.restart();
    }

    function toggleWidget() {
        // Map the panel icon to screen coordinates so the window opens right
        // below it (or beside it, for a vertical panel).
        const p = root.mapToGlobal(0, 0);
        const w = Math.max(1, Math.round(root.width > 0 ? root.width : 24));
        const h = Math.max(1, Math.round(root.height > 0 ? root.height : 24));
        const anchor = " anchor " + Math.round(p.x) + " " + Math.round(p.y) + " " + w + " " + h;
        toggleSource.connectSource("sh -c 'exec \"$HOME/.local/bin/agenda-toggle\"" + anchor + "'");
        refreshTimer.restart();
    }

    // Local midnight in milliseconds; all-day events already carry the range.
    function startOfDay(ms) {
        const d = new Date(ms);
        d.setHours(0, 0, 0, 0);
        return d.getTime();
    }

    // Rebuilds the "today" list only when the day changes or new data arrives,
    // so the list view is not reset every second by the clock tick.
    function refreshToday(force) {
        const key = Qt.formatDate(new Date(root.now), "yyyy-MM-dd");
        if (!force && key === root.dayKey) {
            return;
        }
        root.dayKey = key;

        const from = root.startOfDay(root.now);
        const to = from + 24 * 60 * 60 * 1000;
        const list = (root.events || []).filter(function (event) {
            return event.end > from && event.start < to;
        });
        list.sort(function (a, b) {
            if (a.allDay !== b.allDay) {
                return a.allDay ? -1 : 1;
            }
            return a.start - b.start;
        });
        root.todayEvents = list;
    }

    function nextEvent() {
        var candidate = null;
        for (var i = 0; i < root.todayEvents.length; i++) {
            const event = root.todayEvents[i];
            if (event.end > root.now) {
                if (!candidate || event.start < candidate.start) {
                    candidate = event;
                }
            }
        }
        return candidate;
    }

    function eventTimeLabel(event) {
        return event.allDay ? "Dia inteiro" : Qt.formatTime(new Date(event.start), "hh:mm");
    }

    fullRepresentation: Item {
        implicitWidth: Kirigami.Units.gridUnit * 19
        implicitHeight: Kirigami.Units.gridUnit * 11
        Layout.minimumWidth: Kirigami.Units.gridUnit * 17
        Layout.minimumHeight: Kirigami.Units.gridUnit * 10

        ColumnLayout {
            anchors.fill: parent
            anchors.margins: Kirigami.Units.largeSpacing
            spacing: Kirigami.Units.smallSpacing

            RowLayout {
                Layout.fillWidth: true

                Kirigami.Icon {
                    source: root.appIcon
                    fallback: "office-calendar"
                    implicitWidth: Kirigami.Units.iconSizes.smallMedium
                    implicitHeight: Kirigami.Units.iconSizes.smallMedium
                }

                ColumnLayout {
                    spacing: 0

                    PlasmaComponents3.Label {
                        text: "Agenda"
                        font.weight: Font.DemiBold
                    }

                    PlasmaComponents3.Label {
                        text: root.fullDateText
                        opacity: 0.7
                        font.pixelSize: root.datePixelSize
                        elide: Text.ElideRight
                        Layout.fillWidth: true
                    }
                }

                Item {
                    Layout.fillWidth: true
                }

                PlasmaComponents3.ToolButton {
                    icon.name: "view-refresh"
                    text: "Sincronizar"
                    display: PlasmaComponents3.AbstractButton.IconOnly
                    onClicked: root.requestSync()

                    PlasmaComponents3.ToolTip {
                        text: root.syncConfigured
                            ? "Sincronizar agora"
                            : "Configure a sincronização no app"
                    }
                }
            }

            PlasmaComponents3.Label {
                Layout.fillWidth: true
                visible: root.syncError.length > 0
                text: root.syncError
                color: Kirigami.Theme.negativeTextColor
                font.pixelSize: root.datePixelSize
                elide: Text.ElideRight
            }

            Kirigami.Separator {
                Layout.fillWidth: true
            }

            PlasmaComponents3.Label {
                Layout.fillWidth: true
                visible: root.todayEvents.length === 0
                text: "Nenhum evento hoje"
                opacity: 0.7
            }

            ListView {
                id: eventList
                Layout.fillWidth: true
                Layout.fillHeight: true
                clip: true
                spacing: 2
                model: root.todayEvents
                visible: root.todayEvents.length > 0

                delegate: RowLayout {
                    id: eventRow
                    required property var modelData
                    width: eventList.width

                    PlasmaComponents3.Label {
                        text: root.eventTimeLabel(eventRow.modelData)
                        opacity: 0.75
                        font.pixelSize: root.datePixelSize
                        Layout.preferredWidth: Kirigami.Units.gridUnit * 3
                        elide: Text.ElideRight
                    }

                    PlasmaComponents3.Label {
                        text: eventRow.modelData.title
                        Layout.fillWidth: true
                        elide: Text.ElideRight
                    }
                }
            }

            PlasmaComponents3.Button {
                Layout.fillWidth: true
                text: "Abrir Agenda"
                icon.source: root.appIcon
                onClicked: root.toggleWidget()
            }
        }
    }

    compactRepresentation: MouseArea {
        id: compact

        implicitWidth: card.implicitWidth
        implicitHeight: card.implicitHeight

        Layout.minimumWidth: implicitWidth
        Layout.maximumWidth: implicitWidth
        Layout.minimumHeight: implicitHeight

        hoverEnabled: true
        onClicked: root.toggleWidget()

        Rectangle {
            id: card
            anchors.fill: parent
            radius: Math.round(Kirigami.Units.smallSpacing / 2)
            // A hair darker than the panel, like the reference card.
            color: Qt.darker(Kirigami.Theme.backgroundColor, 1.05)
            border.width: 1
            border.color: compact.containsMouse ? Kirigami.Theme.highlightColor : "transparent"

            implicitWidth: content.implicitWidth + Kirigami.Units.smallSpacing
            implicitHeight: content.implicitHeight + Math.round(Kirigami.Units.smallSpacing / 2)

            ColumnLayout {
                id: content
                anchors.centerIn: parent
                // Negative spacing cancels the labels' leading, so the two
                // lines sit as tight as in the reference card.
                spacing: -Math.round(Kirigami.Units.gridUnit * 0.33)

                PlasmaComponents3.Label {
                    Layout.alignment: Qt.AlignHCenter
                    text: root.timeText
                    color: Kirigami.Theme.textColor
                    font.weight: Font.DemiBold
                    font.pixelSize: root.timePixelSize
                }

                PlasmaComponents3.Label {
                    Layout.alignment: Qt.AlignHCenter
                    text: root.dateText
                    color: Kirigami.Theme.textColor
                    opacity: 0.88
                    font.pixelSize: root.datePixelSize
                }
            }
        }
    }
}
