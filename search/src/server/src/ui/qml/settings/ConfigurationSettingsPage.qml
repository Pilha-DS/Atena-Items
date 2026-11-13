pragma ComponentBehavior: Bound
import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import Vicinae

/// Single place for every Vicinae configuration action. These used to be standalone
/// root search commands, which made the configuration surface look scattered.
Flickable {
    id: root
    contentWidth: width
    contentHeight: outer.implicitHeight
    clip: true
    boundsBehavior: Flickable.StopAtBounds
    topMargin: Style.contentTopInset
    Component.onCompleted: contentY = -topMargin

    ViciWheelHandler {
        target: root
    }

    ScrollBar.vertical: ViciScrollBar {
        topPadding: Style.contentTopInset
        bottomPadding: 16
        policy: root.contentHeight > root.height ? ScrollBar.AsNeeded : ScrollBar.AlwaysOff
    }

    ColumnLayout {
        id: outer
        anchors.horizontalCenter: parent.horizontalCenter
        width: Math.min(root.width - 32, 720)
        spacing: 0

        SettingsSectionLabel {
            text: qsTr("Configuration files")
            Layout.topMargin: 24
            Layout.bottomMargin: 10
        }

        SettingsGroup {
            SettingsActionRow {
                label: qsTr("Vicinae configuration")
                description: qsTr("Open the file holding your personal Vicinae settings.")
                buttonText: qsTr("Open")
                commandId: "core:open-config-file"
            }

            SettingsActionRow {
                label: qsTr("Default configuration")
                description: qsTr("Open a read-only copy of the default configuration, listing every available option.")
                buttonText: qsTr("Open")
                commandId: "core:open-default-config"
            }

            SettingsActionRow {
                label: qsTr("Log file")
                description: qsTr("Open the Vicinae log file in your file browser.")
                buttonText: qsTr("Show")
                showSeparator: false
                commandId: "core:show-logs"
            }
        }

        SettingsSectionLabel {
            text: qsTr("Search behavior")
            Layout.topMargin: 24
            Layout.bottomMargin: 10
        }

        SettingsGroup {
            SettingsActionRow {
                label: qsTr("Fallback commands")
                description: qsTr("Choose which commands are offered when nothing matches your search in the root search.")
                buttonText: qsTr("Configure")
                showSeparator: false
                commandId: "core:manage-fallback"
            }
        }

        SettingsSectionLabel {
            text: qsTr("Maintenance")
            Layout.topMargin: 24
            Layout.bottomMargin: 10
        }

        SettingsGroup {
            SettingsActionRow {
                label: qsTr("Reload script directories")
                description: qsTr("Trigger a new scan of your script directories.")
                buttonText: qsTr("Reload")
                commandId: "core:reload-scripts"
            }

            SettingsActionRow {
                label: qsTr("Forget past telemetry")
                description: qsTr("Ask the Vicinae server to detach telemetry already sent from your installation ID.")
                buttonText: qsTr("Forget")
                commandId: "core:forget-telemetry"
            }

            SettingsActionRow {
                label: qsTr("OAuth token sets")
                description: qsTr("Manage the OAuth token sets saved by extensions providing OAuth integrations.")
                buttonText: qsTr("Manage")
                commandId: "core:oauth-token-store"
            }

            SettingsActionRow {
                label: qsTr("Local storage")
                description: qsTr("Browse the data stored in Vicinae's local storage by builtin and third-party extensions.")
                buttonText: qsTr("Inspect")
                commandId: "core:inspect-local-storage"
            }

            SettingsActionRow {
                label: qsTr("Builtin icons")
                description: qsTr("Search the set of icons built into Vicinae, useful when building extensions.")
                buttonText: qsTr("Search")
                showSeparator: false
                commandId: "core:search-builtin-icons"
            }
        }

        Item {
            Layout.preferredHeight: 24
        }
    }
}
