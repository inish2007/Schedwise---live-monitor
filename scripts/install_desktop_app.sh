#!/usr/bin/env bash
# SchedWise Desktop App Installer
# Registers SchedWise in the Linux Application Menu, GNOME Dash, and Desktop environment.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APPS_DIR="$HOME/.local/share/applications"
ICONS_DIR="$HOME/.local/share/icons/hicolor/256x256/apps"
PIXMAPS_DIR="$HOME/.local/share/pixmaps"

echo "=== Installing SchedWise Native Linux Desktop App ==="

# 1. Ensure target directories exist
mkdir -p "$APPS_DIR"
mkdir -p "$ICONS_DIR"
mkdir -p "$PIXMAPS_DIR"

# 2. Install Desktop Icon
ICON_SRC="$PROJECT_DIR/assets/schedwise-icon.png"
if [[ ! -f "$ICON_SRC" ]]; then
  echo "Error: Icon file not found at $ICON_SRC" >&2
  exit 1
fi

cp "$ICON_SRC" "$ICONS_DIR/schedwise.png"
cp "$ICON_SRC" "$PIXMAPS_DIR/schedwise.png"
echo "✔ Installed 256x256 icon to $ICONS_DIR/schedwise.png"

# 3. Ensure launcher script is executable
chmod +x "$PROJECT_DIR/scripts/launch_desktop_app.sh"

# 4. Generate and Install .desktop Entry
TARGET_DESKTOP="$APPS_DIR/schedwise.desktop"
sed "s|@@PROJECT_DIR@@|$PROJECT_DIR|g" "$PROJECT_DIR/schedwise.desktop" > "$TARGET_DESKTOP"
chmod +x "$TARGET_DESKTOP"
echo "✔ Installed desktop entry to $TARGET_DESKTOP"

# 5. Validate Desktop Entry if tool is available
if command -v desktop-file-validate >/dev/null 2>&1; then
  desktop-file-validate "$TARGET_DESKTOP"
  echo "✔ Validated desktop file structure"
fi

# 6. Update Desktop Database & Icon Cache
if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "$APPS_DIR"
  echo "✔ Updated local desktop database"
fi

if command -v gtk-update-icon-cache >/dev/null 2>&1; then
  gtk-update-icon-cache -f -t "$HOME/.local/share/icons/hicolor" 2>/dev/null || true
fi

# 7. Pin to GNOME Favorites / Dock
if command -v gsettings >/dev/null 2>&1; then
  python3 -c "
import subprocess, ast
try:
    res = subprocess.check_output(['gsettings', 'get', 'org.gnome.shell', 'favorite-apps']).decode().strip()
    favs = ast.literal_eval(res)
    if 'schedwise.desktop' not in favs:
        favs.append('schedwise.desktop')
        val_str = str(favs).replace('\"', '\'')
        subprocess.check_call(['gsettings', 'set', 'org.gnome.shell', 'favorite-apps', val_str])
        print('✔ Automatically pinned SchedWise to GNOME Dock / Favorites')
    else:
        print('✔ SchedWise is already pinned to GNOME Favorites')
except Exception:
    pass
" 2>/dev/null || true
fi

echo ""
echo "🎉 SchedWise is successfully installed and pinned to your dock!"
echo "You can now click the SchedWise icon directly from your Ubuntu Dock to launch the app."
