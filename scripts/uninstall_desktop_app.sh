#!/usr/bin/env bash
# SchedWise Desktop App Uninstaller
# Removes SchedWise from the Linux Application Menu and system integration.
set -euo pipefail

APPS_DIR="$HOME/.local/share/applications"
ICONS_DIR="$HOME/.local/share/icons/hicolor/256x256/apps"
PIXMAPS_DIR="$HOME/.local/share/pixmaps"

echo "=== Uninstalling SchedWise Native Linux Desktop App ==="

rm -f "$APPS_DIR/schedwise.desktop"
rm -f "$ICONS_DIR/schedwise.png"
rm -f "$PIXMAPS_DIR/schedwise.png"

if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "$APPS_DIR"
fi

if command -v gsettings >/dev/null 2>&1; then
  python3 -c "
import subprocess, ast
try:
    res = subprocess.check_output(['gsettings', 'get', 'org.gnome.shell', 'favorite-apps']).decode().strip()
    favs = ast.literal_eval(res)
    if 'schedwise.desktop' in favs:
        favs.remove('schedwise.desktop')
        val_str = str(favs).replace('\"', '\'')
        subprocess.check_call(['gsettings', 'set', 'org.gnome.shell', 'favorite-apps', val_str])
        print('✔ Unpinned SchedWise from GNOME Favorites')
except Exception:
    pass
" 2>/dev/null || true
fi

echo "✔ SchedWise desktop launcher and icons have been removed from your system."
