#!/usr/bin/env bash
#
# Renders the Atena Notes / Jotter icon from the SVG source into every PNG the
# Linux build and the KDE widget need, and installs the hicolor sizes used by
# the panel and the app launcher.
#
# Usage: ./render-icons.sh [--install-into-theme]
#
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
linux_root="$(cd "$here/../.." && pwd)"
svg="$here/jotter-icon.svg"

app_png="$linux_root/app/src/main/resources/icons/jotter.png"
widget_png="$linux_root/kde/jotter-widget/contents/icons/jotter.png"

if ! command -v rsvg-convert >/dev/null 2>&1; then
    echo "rsvg-convert is required (package: librsvg)" >&2
    exit 1
fi

echo "source: $svg"

# The app window icon and both widget copies share one 512px master.
rsvg-convert -w 512 -h 512 "$svg" -o "$app_png"
cp "$app_png" "$widget_png"
echo "wrote $app_png"
echo "wrote $widget_png"

if [[ "${1:-}" == "--install-into-theme" ]]; then
    theme_root="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor"

    # A native size per hicolor bucket keeps the panel and the task bar from
    # downscaling a 512px bitmap, which is what turns the glyph into a smudge.
    for size in 16 22 24 32 48 64 128 256 512; do
        dir="$theme_root/${size}x${size}/apps"
        mkdir -p "$dir"
        rsvg-convert -w "$size" -h "$size" "$svg" -o "$dir/jotter.png"
        echo "installed $dir/jotter.png"
    done

    mkdir -p "$theme_root/scalable/apps"
    cp "$svg" "$theme_root/scalable/apps/jotter.svg"
    echo "installed $theme_root/scalable/apps/jotter.svg"

    if command -v kbuildsycoca6 >/dev/null 2>&1; then
        kbuildsycoca6 --noincremental >/dev/null 2>&1 || true
    fi
    if command -v gtk-update-icon-cache >/dev/null 2>&1; then
        gtk-update-icon-cache -f -t "$theme_root" >/dev/null 2>&1 || true
    fi
fi
