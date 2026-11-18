pragma ComponentBehavior: Bound
import QtQuick
import Vicinae

/// Settings row whose control runs a builtin command by its serialized entrypoint id.
SettingsRow {
    id: root
    required property string commandId
    property string buttonText: qsTr("Open")

    ViciButton {
        anchors.right: parent.right
        anchors.verticalCenter: parent.verticalCenter
        implicitHeight: 26
        horizontalPadding: 10
        variant: "ghost"
        bordered: true
        text: root.buttonText
        activeFocusOnTab: true
        onClicked: Settings.runCommand(root.commandId)
    }
}
