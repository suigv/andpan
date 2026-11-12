#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUNDLE="$ROOT/AndPan.app"
mkdir -p "$BUNDLE/Contents/MacOS" "$BUNDLE/Contents/Resources"
mkdir -p "$ROOT/.build"
swiftc -O "$ROOT/mac/icon.swift" -o "$ROOT/.build/icon-gen"
"$ROOT/.build/icon-gen" --icns "$BUNDLE/Contents/Resources/AppIcon.icns"
printf '%s' "$ROOT" > "$BUNDLE/Contents/Resources/root.txt"
cat > "$BUNDLE/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>AndPan</string>
  <key>CFBundleDisplayName</key><string>AndPan</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundleIdentifier</key><string>com.local.andpan.launcher</string>
  <key>CFBundleExecutable</key><string>AndPan</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>NSAppTransportSecurity</key><dict><key>NSAllowsLocalNetworking</key><true/></dict>
  <key>NSHighResolutionCapable</key><true/>
</dict></plist>
PLIST
swiftc -O "$ROOT/mac/launcher.swift" -o "$BUNDLE/Contents/MacOS/AndPan"
codesign --force --sign - "$BUNDLE"
if [ ! -e /Applications/AndPan.app ] && [ ! -L /Applications/AndPan.app ]; then
  ln -s "$BUNDLE" /Applications/AndPan.app
elif [ -L /Applications/AndPan.app ]; then
  existing="$(readlink /Applications/AndPan.app)"
  if [ "$existing" != "$BUNDLE" ] && { [ ! -e /Applications/AndPan.app ] ||
      [ "$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' /Applications/AndPan.app/Contents/Info.plist 2>/dev/null || true)" = "com.local.andpan.launcher" ]; }; then
    ln -sfn "$BUNDLE" /Applications/AndPan.app
  fi
fi
echo "Mac 应用：$BUNDLE"
