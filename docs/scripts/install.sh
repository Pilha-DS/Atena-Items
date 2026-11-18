#!/usr/bin/env bash
#
# Installs Atena Docs as a desktop app for the current user.
#
#   ./scripts/install.sh            build + install (app, launcher, icon)
#   ./scripts/install.sh uninstall  remove everything that was installed
#
# Layout after installing:
#   ~/.local/opt/docs                       app image (bundled JRE)
#   ~/.local/bin/docs-toggle                show/hide helper used by the shortcut
#   ~/.local/share/applications/docs.desktop
#   ~/.local/share/icons/hicolor/256x256/apps/docs.png
#
# The library itself is NOT touched: it stays in ~/Documentos/AtenaData/Docs
# (or wherever ~/.config/docs/root points), and uninstall never deletes a
# document, the index database or the settings.
#
set -euo pipefail

APP_ID="docs"

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
OPT_DIR="$HOME/.local/opt/$APP_ID"
DESKTOP_DIR="$DATA_HOME/applications"
ICON_DIR="$DATA_HOME/icons/hicolor/256x256/apps"
BIN_DIR="$HOME/.local/bin"
CONFIG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/$APP_ID"

say() { printf '==> %s\n' "$*"; }

uninstall() {
    say "Removing Atena Docs"
    rm -rf "$OPT_DIR"
    rm -f "$BIN_DIR/docs-toggle" "$DESKTOP_DIR/$APP_ID.desktop" "$ICON_DIR/$APP_ID.png"
    # Stale pointer to a runtime directory that will not exist after logout.
    rm -f "$CONFIG_DIR/ipc-dir"

    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database "$DESKTOP_DIR" >/dev/null 2>&1 || true
    fi

    say "Done."
    printf 'preservados: %s (configuração) e %s (índice)\n' \
        "$CONFIG_DIR" "${XDG_DATA_HOME:-$HOME/.local/share}/$APP_ID"
    printf 'preservados: os seus documentos — o Docs só apaga o que você confirmar.\n'
}

install_app() {
    say "Building app image (createDistributable)"
    ( cd "$PROJECT_DIR" && ./gradlew :app:createDistributable --console=plain )

    local built="$PROJECT_DIR/app/build/compose/binaries/main/app/$APP_ID"
    if [[ ! -x "$built/bin/$APP_ID" ]]; then
        printf 'error: launcher not found at %s/bin/%s\n' "$built" "$APP_ID" >&2
        exit 1
    fi

    say "Installing app to $OPT_DIR"
    rm -rf "$OPT_DIR"
    mkdir -p "$OPT_DIR"
    cp -r "$built/." "$OPT_DIR/"
}

install_icon() {
    say "Installing icon"
    mkdir -p "$ICON_DIR"
    cp -f "$PROJECT_DIR/app/src/main/resources/icons/$APP_ID.png" "$ICON_DIR/$APP_ID.png"
    if command -v gtk-update-icon-cache >/dev/null 2>&1; then
        gtk-update-icon-cache -f -t "$DATA_HOME/icons/hicolor" >/dev/null 2>&1 || true
    fi
}

install_launcher() {
    say "Installing launcher to $BIN_DIR/docs-toggle"
    mkdir -p "$BIN_DIR"
    install -m 755 "$PROJECT_DIR/scripts/docs-toggle" "$BIN_DIR/docs-toggle"

    say "Registering desktop entry"
    mkdir -p "$DESKTOP_DIR"
    cat > "$DESKTOP_DIR/$APP_ID.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Docs
GenericName=Document library
Comment=Open and organize the documents that live in your own folder
Exec=$BIN_DIR/docs-toggle
Icon=$APP_ID
Terminal=false
Categories=Office;
Keywords=docs;documents;pdf;docx;atena;
StartupNotify=false
StartupWMClass=docs
# Global shortcut registered by KGlobalAccel (System Settings -> Shortcuts).
# Super+D is taken by "Show Desktop" in Plasma, so this one avoids it.
X-KDE-Shortcuts=Meta+Shift+D
EOF

    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database "$DESKTOP_DIR" >/dev/null 2>&1 || true
    fi
}

# Reading inside the files needs two programs that are not Java libraries on
# purpose. Both are optional: without them the app still lists, opens and tags
# the documents, it just cannot see the words inside PDFs and images.
check_optional_tools() {
    local missing=()
    command -v pdftotext >/dev/null 2>&1 || missing+=("poppler")
    command -v tesseract >/dev/null 2>&1 || missing+=("tesseract tesseract-data-por")

    if [[ ${#missing[@]} -eq 0 ]]; then
        return 0
    fi

    printf '\n==> Optional tools missing (search inside PDFs and images)\n'
    printf '    install with: sudo pacman -S --needed %s\n' "${missing[*]}"
    printf '    without them the app indexes names, tags and favorites normally.\n'
}

# The file chooser behind "Importar…" prefers kdialog, which is the dialog
# Dolphin itself uses. Without it the app falls back to the Java dialog: it
# works, it just does not look like the rest of Plasma.
check_dialog_tool() {
    if command -v kdialog >/dev/null 2>&1; then
        return 0
    fi
    printf '\n==> kdialog not found (native file chooser)\n'
    printf '    install with: sudo pacman -S --needed kdialog\n'
    printf '    without it "Importar…" opens the Java file dialog instead.\n'
}

if [[ "${1:-}" == "uninstall" ]]; then
    uninstall
    exit 0
fi

install_app
install_icon
install_launcher
check_optional_tools
check_dialog_tool

cat <<EOF

==> Installed.

Day to day:
  Super+Shift+D   opens/closes the Docs window
  docs-toggle     same, from a terminal

The library folder is created on first run and can be changed by writing an
absolute path into $CONFIG_DIR/root

EOF
