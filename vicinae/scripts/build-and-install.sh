#!/usr/bin/env bash
#
# Build this Vicinae fork from source and install it under a user prefix.
#
#   ./scripts/build-and-install.sh                 # build and install in ~/.local
#   ./scripts/build-and-install.sh --build-only    # build only
#   ./scripts/build-and-install.sh --prefix DIR    # install somewhere else
#   ./scripts/build-and-install.sh --clean         # throw the build dir away first
#   ./scripts/build-and-install.sh --jobs N
#   ./scripts/build-and-install.sh --uninstall     # remove the installed files
#
# The upstream scripts/install.sh downloads the official release asset, so it never carries
# our changes. This is the installer for the fork: same install layout (bin, share, libexec),
# but everything comes from the source tree.
#
set -euo pipefail

PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$PROJECT_ROOT/build"
PREFIX="${PREFIX:-$HOME/.local}"
JOBS="$(nproc 2>/dev/null || echo 4)"
BUILD_ONLY=0
CLEAN=0
UNINSTALL=0

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
info() { printf '  %s\n' "$*"; }
step() { printf '\n==> %s\n' "$*"; }

usage() {
    sed -n '2,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help) usage; exit 0 ;;
        --build-only) BUILD_ONLY=1; shift ;;
        --clean) CLEAN=1; shift ;;
        --uninstall) UNINSTALL=1; shift ;;
        --prefix)
            shift
            [[ -n "${1:-}" ]] || die "--prefix needs a directory"
            PREFIX="$1"
            shift
            ;;
        --jobs)
            shift
            [[ -n "${1:-}" ]] || die "--jobs needs a number"
            JOBS="$1"
            shift
            ;;
        *) die "unknown option: $1 (see --help)" ;;
    esac
done

check_dependencies() {
    step "checking build dependencies"

    local missing=() tool
    for tool in cmake ninja pkg-config c++ git node; do
        command -v "$tool" >/dev/null 2>&1 || missing+=("$tool")
    done

    if [[ ${#missing[@]} -gt 0 ]]; then
        die "missing build tools: ${missing[*]} (try: sudo pacman -S --needed base-devel cmake ninja nodejs)"
    fi

    # npm is only needed to fetch the extension runtime dependencies. When the source tree already
    # carries node_modules for both packages, the build skips `npm ci` and we can go without it.
    if [[ ! -d "$PROJECT_ROOT/src/typescript/api/node_modules" ||
        ! -d "$PROJECT_ROOT/src/typescript/extension-manager/node_modules" ]]; then
        command -v npm >/dev/null 2>&1 ||
            die "npm not found and node_modules are missing (try: sudo pacman -S --needed npm)"
    fi

    # The Linux build always compiles the Wayland protocols used for the layer shell window,
    # background effect, clipboard data-control and shortcut inhibition.
    if ! pkg-config --exists wayland-protocols; then
        die "wayland-protocols not found (try: sudo pacman -S --needed wayland-protocols)"
    fi

    # Everything else is a package the CMake configure step reports by name if it is absent.
    info "tools: cmake $(cmake --version | head -n1 | awk '{print $3}'), ninja, $(c++ --version | head -n1)"
    info "wayland-protocols $(pkg-config --modversion wayland-protocols)"
}

configure() {
    step "configuring"

    if [[ "$CLEAN" -eq 1 ]]; then
        info "removing $BUILD_DIR"
        rm -rf "$BUILD_DIR"
    elif [[ -f "$BUILD_DIR/CMakeCache.txt" ]]; then
        local cached_src
        cached_src="$(sed -n 's/^CMAKE_HOME_DIRECTORY:INTERNAL=//p' "$BUILD_DIR/CMakeCache.txt")"
        if [[ "$cached_src" != "$PROJECT_ROOT" ]]; then
            # A moved checkout leaves absolute paths behind; CMake refuses to reuse that cache.
            info "build cache belongs to $cached_src, starting over"
            rm -rf "$BUILD_DIR"
        fi
    fi

    cmake -G Ninja -B "$BUILD_DIR" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$PREFIX" \
        -DBUILD_TESTS=OFF \
        -DLOCAL_AI=OFF \
        -DLTO=OFF \
        -DUSE_SYSTEM_CMARK_GFM=OFF \
        "$PROJECT_ROOT"
}

build() {
    step "building with $JOBS jobs"
    cmake --build "$BUILD_DIR" -j "$JOBS"
}

install() {
    step "installing to $PREFIX"
    cmake --install "$BUILD_DIR" --prefix "$PREFIX"

    # systemd only looks at the XDG data directory for units of a user install, not at
    # <prefix>/lib/systemd/user.
    if [[ "$PREFIX" == "$HOME" || "$PREFIX" == "$HOME"/* ]]; then
        local unit_dir="${XDG_DATA_HOME:-$HOME/.local/share}/systemd/user"
        local unit="$PREFIX/lib/systemd/user/vicinae.service"
        if [[ -f "$unit" ]]; then
            mkdir -p "$unit_dir"
            cp "$unit" "$unit_dir/vicinae.service"
            rm -f "$unit"
            info "systemd unit -> $unit_dir/vicinae.service"
        fi
    fi

    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database "$PREFIX/share/applications" >/dev/null 2>&1 || true
    fi

    if command -v systemctl >/dev/null 2>&1; then
        systemctl --user daemon-reload >/dev/null 2>&1 || true
        if systemctl --user is-active --quiet vicinae.service 2>/dev/null; then
            systemctl --user restart vicinae.service || true
            info "vicinae service restarted"
        fi
    fi

    if [[ ":$PATH:" != *":$PREFIX/bin:"* ]]; then
        info "note: add $PREFIX/bin to PATH (the monorepo installer does this)"
    fi
}

uninstall() {
    step "removing $PREFIX"

    if command -v systemctl >/dev/null 2>&1; then
        systemctl --user stop vicinae.service >/dev/null 2>&1 || true
    fi

    rm -f "$PREFIX/bin/vicinae"
    rm -rf "$PREFIX/libexec/vicinae" "$PREFIX/share/vicinae"
    rm -f "$PREFIX/share/applications/vicinae.desktop" \
          "$PREFIX/share/applications/vicinae-settings.desktop" \
          "$PREFIX/share/applications/vicinae-url-handler.desktop"
    rm -f "$PREFIX/share/icons/hicolor/512x512/apps/vicinae.png"
    rm -f "$PREFIX/lib/systemd/user/vicinae.service"
    rm -f "${XDG_DATA_HOME:-$HOME/.local/share}/systemd/user/vicinae.service"

    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database "$PREFIX/share/applications" >/dev/null 2>&1 || true
    fi
    if command -v systemctl >/dev/null 2>&1; then
        systemctl --user daemon-reload >/dev/null 2>&1 || true
    fi

    info "kept: ~/.config/vicinae, ~/.local/share/vicinae, ~/.cache/vicinae"
}

if [[ "$UNINSTALL" -eq 1 ]]; then
    uninstall
    exit 0
fi

check_dependencies
configure
build
if [[ "$BUILD_ONLY" -eq 1 ]]; then
    step "done (build only)"
    info "binary: $BUILD_DIR/bin/vicinae"
    exit 0
fi
install
step "done"
info "vicinae: $PREFIX/bin/vicinae"
