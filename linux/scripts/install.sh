#!/usr/bin/env bash
#
# Installs Jotter as a KDE Plasma widget for the current user.
#
#   ./scripts/install.sh            build + install (app, launcher, plasmoid, KWin script)
#   ./scripts/install.sh uninstall  remove everything that was installed
#
# Layout after installing:
#   ~/.local/opt/jotter                    app image (bundled JRE)
#   ~/.local/bin/jotter-toggle             show/hide helper used by the plasmoid
#   ~/.local/share/applications/jotter.desktop
#   ~/.local/share/icons/hicolor/256x256/apps/jotter.png
#   ~/.local/share/plasma/plasmoids/org.jotter.widget
#   ~/.local/share/kwin/scripts/jotter-widget
#
set -euo pipefail

APP_ID="jotter"
PLASMOID_ID="org.jotter.widget"
KWIN_SCRIPT_ID="jotter-widget"

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
OPT_DIR="$HOME/.local/opt/$APP_ID"
DESKTOP_DIR="$DATA_HOME/applications"
ICON_DIR="$DATA_HOME/icons/hicolor/256x256/apps"
PLASMOID_DIR="$DATA_HOME/plasma/plasmoids/$PLASMOID_ID"
KWIN_SCRIPT_DIR="$DATA_HOME/kwin/scripts/$KWIN_SCRIPT_ID"
BIN_DIR="$HOME/.local/bin"
CONFIG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/jotter"

uninstall() {
    echo "==> Removing Jotter"
    rm -rf "$OPT_DIR" "$PLASMOID_DIR" "$KWIN_SCRIPT_DIR"
    rm -f "$BIN_DIR/jotter-toggle" "$DESKTOP_DIR/jotter.desktop" "$ICON_DIR/jotter.png"

    if command -v kwriteconfig6 >/dev/null 2>&1; then
        kwriteconfig6 --file kwinrc --group Plugins --key "${KWIN_SCRIPT_ID}Enabled" false
        command -v qdbus6 >/dev/null 2>&1 && qdbus6 org.kde.KWin /KWin reconfigure >/dev/null 2>&1 || true
    fi

    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database "$DESKTOP_DIR" >/dev/null 2>&1 || true
    fi
    echo "Done."
}

install_icon() {
    mkdir -p "$ICON_DIR"
    cp -f "$PROJECT_DIR/app/src/main/resources/icons/jotter.png" "$ICON_DIR/$APP_ID.png"
    if command -v gtk-update-icon-cache >/dev/null 2>&1; then
        gtk-update-icon-cache -f -t "$DATA_HOME/icons/hicolor" >/dev/null 2>&1 || true
    fi
}

install_app() {
    echo "==> Building app image (createDistributable)"
    (cd "$PROJECT_DIR" && ./gradlew :app:createDistributable --console=plain)

    local built="$PROJECT_DIR/app/build/compose/binaries/main/app/$APP_ID"
    if [[ ! -x "$built/bin/$APP_ID" ]]; then
        echo "error: launcher not found at $built/bin/$APP_ID" >&2
        exit 1
    fi

    echo "==> Installing app to $OPT_DIR"
    rm -rf "$OPT_DIR"
    mkdir -p "$OPT_DIR"
    cp -r "$built/." "$OPT_DIR/"
}

install_launcher() {
    echo "==> Installing launcher to $BIN_DIR/jotter-toggle"
    mkdir -p "$BIN_DIR"
    install -m 755 "$PROJECT_DIR/scripts/jotter-toggle" "$BIN_DIR/jotter-toggle"

    echo "==> Registering desktop entry"
    mkdir -p "$DESKTOP_DIR"
    cat > "$DESKTOP_DIR/$APP_ID.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Jotter
GenericName=Notes
Comment=Quick notes widget for your desktop
Exec=$BIN_DIR/jotter-toggle
Icon=$APP_ID
Terminal=false
Categories=Utility;TextTools;
Keywords=notes;jotter;notas;
StartupNotify=false
StartupWMClass=jotter
EOF

    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database "$DESKTOP_DIR" >/dev/null 2>&1 || true
    fi
}

install_plasmoid() {
    echo "==> Installing Plasma widget to $PLASMOID_DIR"

    # Catch QML type errors (e.g. Plasma 5 types removed in Plasma 6) before
    # installing, otherwise the panel only shows an "error loading" popup.
    local lint="/usr/lib/qt6/bin/qmllint"
    if [[ -x "$lint" ]]; then
        if ! "$lint" "$PROJECT_DIR/kde/jotter-widget/contents/ui/main.qml"; then
            echo "error: QML inválido em kde/jotter-widget/contents/ui/main.qml" >&2
            exit 1
        fi
    fi

    rm -rf "$PLASMOID_DIR"
    mkdir -p "$PLASMOID_DIR"
    cp -r "$PROJECT_DIR/kde/jotter-widget/." "$PLASMOID_DIR/"
}

install_kwin_script() {
    echo "==> Installing KWin script (hides the window from taskbar/pager)"
    rm -rf "$KWIN_SCRIPT_DIR"
    mkdir -p "$KWIN_SCRIPT_DIR"
    cp -r "$PROJECT_DIR/kde/kwin-script/." "$KWIN_SCRIPT_DIR/"

    if command -v kwriteconfig6 >/dev/null 2>&1; then
        kwriteconfig6 --file kwinrc --group Plugins --key "${KWIN_SCRIPT_ID}Enabled" true
        if command -v qdbus6 >/dev/null 2>&1; then
            qdbus6 org.kde.KWin /KWin reconfigure >/dev/null 2>&1 || true
        fi
    else
        echo "    (kwriteconfig6 not found; enable \"$KWIN_SCRIPT_ID\" in System Settings -> KWin Scripts)"
    fi
}

if [[ "${1:-}" == "uninstall" ]]; then
    uninstall
    exit 0
fi

install_app
install_icon
install_launcher
install_plasmoid
install_kwin_script

# Leftovers from older builds: the IPC directory used to be the runtime dir
# itself, and the published path may point to the previous layout.
rm -f "$CONFIG_DIR/ipc-dir"
if [[ -n "${XDG_RUNTIME_DIR:-}" ]]; then
    rm -f "$XDG_RUNTIME_DIR/jotter.lock" "$XDG_RUNTIME_DIR/jotter.status.json" \
        "$XDG_RUNTIME_DIR/jotter.toggle" 2>/dev/null || true
fi

cat <<EOF

==> Installed.

Add the widget to a panel:
  right click the panel -> "Add Widgets..." -> search for "Jotter"

If the widget does not show up in that list, reload the shell:
  systemctl --user restart plasma-plasmashell

Day to day:
  panel icon    shows/hides the notes widget
  Jotter menu   same, via $BIN_DIR/jotter-toggle
  drag the top 12px of the card to move it around the desktop

EOF
