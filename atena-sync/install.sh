#!/usr/bin/env bash
#
# Installs Atena Sync as a user daemon (Arch + KDE).
#
#   ./install.sh            build + install + autostart
#   ./install.sh uninstall  remove binary/autostart (keeps config + DB)
#   ./install.sh uninstall --purge  also delete config/data
#
set -euo pipefail

APP_ID="atena-sync"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
CONFIG_HOME="${XDG_CONFIG_HOME:-$HOME/.config}"
OPT_DIR="$HOME/.local/opt/$APP_ID"
BIN_DIR="$HOME/.local/bin"
AUTOSTART_DIR="$CONFIG_HOME/autostart"
APP_DATA_DIR="$DATA_HOME/$APP_ID"
APP_CONFIG_DIR="$CONFIG_HOME/$APP_ID"
LOOPBACK_PORT="${ATENA_SYNC_LOOPBACK_PORT:-18765}"
LAN_PORT="${ATENA_SYNC_LAN_PORT:-18766}"

usage() {
    cat <<EOF
Usage: ./install.sh [uninstall [--purge]]

Installs $APP_ID to ~/.local/opt/$APP_ID and registers autostart.
EOF
}

uninstall() {
    local purge=0
    if [[ "${1:-}" == "--purge" ]]; then
        purge=1
    fi
    echo "==> Stopping $APP_ID (if running)"
    if command -v pkill >/dev/null 2>&1; then
        pkill -f "$OPT_DIR/bin/$APP_ID" >/dev/null 2>&1 || true
    fi
    echo "==> Removing $APP_ID install"
    rm -rf "$OPT_DIR"
    rm -f "$BIN_DIR/$APP_ID" "$AUTOSTART_DIR/$APP_ID.desktop"
    if [[ "$purge" -eq 1 ]]; then
        echo "==> Purging user data ($APP_DATA_DIR, $APP_CONFIG_DIR)"
        rm -rf "$APP_DATA_DIR" "$APP_CONFIG_DIR"
    else
        echo "Preserved: $APP_DATA_DIR and $APP_CONFIG_DIR (use --purge to delete)"
    fi
    echo "Done."
}

install_app() {
    echo "==> Building $APP_ID"
    (cd "$PROJECT_DIR" && ./gradlew installDist --console=plain)

    local built="$PROJECT_DIR/build/install/$APP_ID"
    if [[ ! -x "$built/bin/$APP_ID" ]]; then
        echo "error: launcher not found at $built/bin/$APP_ID" >&2
        exit 1
    fi

    echo "==> Installing to $OPT_DIR"
    rm -rf "$OPT_DIR"
    mkdir -p "$OPT_DIR"
    cp -a "$built/." "$OPT_DIR/"

    mkdir -p "$BIN_DIR"
    ln -sfn "$OPT_DIR/bin/$APP_ID" "$BIN_DIR/$APP_ID"
}

install_autostart() {
    echo "==> Autostart desktop entry"
    mkdir -p "$AUTOSTART_DIR" "$APP_DATA_DIR" "$APP_CONFIG_DIR"
    cat > "$AUTOSTART_DIR/$APP_ID.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Atena Sync
Comment=Local-first sync daemon (LAN + hub fallback)
Exec=$OPT_DIR/bin/$APP_ID
Icon=network-transmit-receive
Terminal=false
Categories=Network;
X-GNOME-Autostart-enabled=true
X-KDE-autostart-phase=2
EOF
}

start_now() {
    if command -v pkill >/dev/null 2>&1; then
        pkill -f "$OPT_DIR/bin/$APP_ID" >/dev/null 2>&1 || true
        sleep 0.3
    fi
    echo "==> Starting $APP_ID"
    nohup "$OPT_DIR/bin/$APP_ID" >"$APP_DATA_DIR/daemon.log" 2>&1 &
    disown || true
    local ready=0
    local i
    for i in 1 2 3 4 5 6 7 8 9 10; do
        sleep 0.4
        if curl -fsS "http://127.0.0.1:${LOOPBACK_PORT}/health" >/dev/null 2>&1; then
            ready=1
            break
        fi
    done
    if [[ "$ready" -eq 1 ]]; then
        echo "OK: health on 127.0.0.1:${LOOPBACK_PORT}"
    else
        echo "warn: daemon started but health not ready yet (see $APP_DATA_DIR/daemon.log)" >&2
    fi
}

main() {
    case "${1:-}" in
        -h|--help) usage; exit 0 ;;
        uninstall)
            uninstall "${2:-}"
            exit 0
            ;;
        "")
            ;;
        *)
            echo "unknown argument: $1" >&2
            usage >&2
            exit 1
            ;;
    esac

    install_app
    install_autostart
    start_now
    echo
    echo "Atena Sync installed."
    echo "  loopback: http://127.0.0.1:${LOOPBACK_PORT}"
    echo "  lan port: ${LAN_PORT}"
    echo "  setup:    curl -s http://127.0.0.1:${LOOPBACK_PORT}/v1/setup"
    echo "  log:      $APP_DATA_DIR/daemon.log"
}

main "$@"
