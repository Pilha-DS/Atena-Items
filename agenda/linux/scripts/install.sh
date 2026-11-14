#!/usr/bin/env bash
#
# Installs Agenda as a KDE Plasma 6 widget for the current user.
#
#   ./scripts/install.sh            build + install (app, launcher, plasmoid)
#   ./scripts/install.sh uninstall  remove everything that was installed
#
# Layout after installing:
#   ~/.local/opt/agenda                    app image (bundled JRE)
#   ~/.local/bin/agenda-toggle             show/hide helper used by the plasmoid
#   ~/.local/share/applications/agenda.desktop
#   ~/.local/share/icons/hicolor/256x256/apps/agenda.png
#   ~/.local/share/plasma/plasmoids/org.agenda.widget
#
set -euo pipefail

APP_ID="agenda"
PLASMOID_ID="org.agenda.widget"

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
OPT_DIR="$HOME/.local/opt/$APP_ID"
DESKTOP_DIR="$DATA_HOME/applications"
ICON_DIR="$DATA_HOME/icons/hicolor/256x256/apps"
PLASMOID_DIR="$DATA_HOME/plasma/plasmoids/$PLASMOID_ID"
BIN_DIR="$HOME/.local/bin"
CONFIG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/agenda"

# Everything here belongs to the user: under sudo, HOME becomes /root and the
# plasmoid is installed where the running Plasma shell never looks.
if [[ -n "${SUDO_USER:-}" || "$(id -u)" -eq 0 && "$HOME" == "/root" ]]; then
    echo "error: não rode este script com sudo." >&2
    echo "       ele instala em \$HOME/.local (app, lançador e widget do Plasma)." >&2
    echo "       rode: cd agenda/linux && ./scripts/install.sh" >&2
    exit 1
fi

uninstall() {
    echo "==> Removing Agenda"
    if command -v kpackagetool6 >/dev/null 2>&1; then
        kpackagetool6 --type Plasma/Applet --remove "$PLASMOID_ID" >/dev/null 2>&1 || true
    fi
    rm -rf "$OPT_DIR" "$PLASMOID_DIR"
    rm -f "$BIN_DIR/agenda-toggle" "$DESKTOP_DIR/agenda.desktop" "$ICON_DIR/agenda.png"

    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database "$DESKTOP_DIR" >/dev/null 2>&1 || true
    fi

    # User data is kept on purpose: the local database and the sync settings
    # survive a reinstall. Remove them by hand to start over.
    echo "Kept your data: $DATA_HOME/$APP_ID and $CONFIG_DIR"
    echo "Done."
}

install_icon() {
    mkdir -p "$ICON_DIR"
    cp -f "$PROJECT_DIR/app/src/main/resources/icons/agenda.png" "$ICON_DIR/$APP_ID.png"
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
    echo "==> Installing launcher to $BIN_DIR/agenda-toggle"
    mkdir -p "$BIN_DIR"
    install -m 755 "$PROJECT_DIR/scripts/agenda-toggle" "$BIN_DIR/agenda-toggle"

    echo "==> Registering desktop entry"
    mkdir -p "$DESKTOP_DIR"
    cat > "$DESKTOP_DIR/$APP_ID.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Agenda
GenericName=Calendar
Comment=Calendar widget for your desktop
Exec=$BIN_DIR/agenda-toggle
Icon=$APP_ID
Terminal=false
Categories=Office;Calendar;
Keywords=agenda;calendar;calendario;eventos;
StartupNotify=false
StartupWMClass=agenda
EOF

    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database "$DESKTOP_DIR" >/dev/null 2>&1 || true
    fi
}

lint_plasmoid() {
    local lint="/usr/lib/qt6/bin/qmllint"
    if [[ ! -x "$lint" ]]; then
        lint="$(command -v qmllint || true)"
    fi
    # Only Qt 6 qmllint understands this QML; skip when it is not available.
    local version=""
    if [[ -n "$lint" ]]; then
        version="$("$lint" --version 2>/dev/null || true)"
    fi
    if [[ -z "$lint" ]] || [[ "$version" != qmllint\ 6* ]]; then
        return 0
    fi

    local imports=()
    [[ -d /usr/lib/qt6/qml ]] && imports=(-I /usr/lib/qt6/qml)
    if ! "$lint" "${imports[@]}" "$PROJECT_DIR/kde/agenda-widget/contents/ui/main.qml"; then
        echo "error: QML inválido em kde/agenda-widget/contents/ui/main.qml" >&2
        exit 1
    fi
}

install_plasmoid() {
    echo "==> Installing Plasma widget to $PLASMOID_DIR"

    # Catch QML type errors (e.g. Plasma 5 types removed in Plasma 6) before
    # installing, otherwise the panel only shows an "error loading" popup.
    lint_plasmoid

    # kpackagetool6 registers the package with Plasma; a plain `cp` leaves the
    # widget invisible in "Add Widgets" until the shell is restarted.
    if command -v kpackagetool6 >/dev/null 2>&1; then
        if [[ -d "$PLASMOID_DIR" ]]; then
            kpackagetool6 --type Plasma/Applet --upgrade "$PROJECT_DIR/kde/agenda-widget"
        else
            kpackagetool6 --type Plasma/Applet --install "$PROJECT_DIR/kde/agenda-widget"
        fi
    else
        echo "    (kpackagetool6 not found; copying manually)"
        rm -rf "$PLASMOID_DIR"
        mkdir -p "$PLASMOID_DIR"
        cp -r "$PROJECT_DIR/kde/agenda-widget/." "$PLASMOID_DIR/"
    fi
}

# The image was just replaced under any running instance, so stop it and start
# the new one hidden: the process is warm and the first click only shows the
# window (~50 ms), instead of paying the JVM/Compose cold start (~2 s).
restart_app() {
    pkill -f "$OPT_DIR/bin/$APP_ID" >/dev/null 2>&1 || true
    # TERM alone can hang in a stalled Compose shutdown hook; do not wait forever.
    for _ in 1 2 3 4 5 6; do
        pgrep -f "$OPT_DIR/bin/$APP_ID" >/dev/null 2>&1 || break
        sleep 0.5
    done
    pkill -9 -f "$OPT_DIR/bin/$APP_ID" >/dev/null 2>&1 || true
    if [[ -x "$BIN_DIR/agenda-toggle" ]]; then
        echo "==> Starting Agenda in the background (warm, no window)"
        "$BIN_DIR/agenda-toggle" prewarm || true
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
restart_app

# Leftovers from previous layouts: the IPC directory used to be the runtime
# dir itself, and the published path may point to the old one.
rm -f "$CONFIG_DIR/ipc-dir"
if [[ -n "${XDG_RUNTIME_DIR:-}" ]]; then
    rm -f "$XDG_RUNTIME_DIR/agenda.lock" "$XDG_RUNTIME_DIR/agenda.status.json" \
        "$XDG_RUNTIME_DIR/agenda.toggle" 2>/dev/null || true
    rm -f "$XDG_RUNTIME_DIR/agenda/"*.toggle "$XDG_RUNTIME_DIR/agenda/"*.sync \
        "$XDG_RUNTIME_DIR/agenda/"*.anchor 2>/dev/null || true
fi

cat <<EOF

==> Installed.

Add the widget to a panel:
  right click the panel -> "Add Widgets..." -> search for "Agenda"

If the widget does not show up in that list, reload the shell:
  systemctl --user restart plasma-plasmashell

Day to day:
  panel card    shows the time/date and opens the Agenda window
  launcher      same, via $BIN_DIR/agenda-toggle
  sync          Settings -> Sincronização inside the app

EOF
