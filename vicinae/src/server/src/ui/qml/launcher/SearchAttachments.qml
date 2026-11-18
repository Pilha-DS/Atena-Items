pragma ComponentBehavior: Bound
import QtQuick
import Vicinae

Item {
    id: root
    required property AttachmentModel model

    implicitHeight: list.implicitHeight

    AttachmentPreview {
        id: preview
    }

    AttachmentList {
        id: list
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        attachments: root.model.items
        removable: true
        onRemoveRequested: attachmentId => root.model.remove(attachmentId)
        onPreviewRequested: content => preview.show(content)
    }
}
