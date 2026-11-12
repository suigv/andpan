#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH"
ADB="${ADB_BIN:-$(command -v adb || true)}"
APK="$ROOT/.build/android/andpan.apk"
[ -n "$ADB" ] || { echo "找不到安卓连接工具 adb。"; exit 1; }
[ -f "$APK" ] || { echo "找不到手机安装包，请先构建 AndPan 手机端。"; exit 1; }
devices="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
count="$(printf '%s\n' "$devices" | sed '/^$/d' | wc -l | tr -d ' ')"
if [ "$count" != 1 ]; then
  echo "请仅连接一台 Android 手机，并在手机上允许 USB 调试。"
  exit 1
fi
"$ADB" -s "$devices" install -r "$APK"
ANDROID_SERIAL="$devices" "$ROOT/mac/start.command"
echo "手机端已安装并完成连接设置；连接成功后可以拔掉 USB。"
