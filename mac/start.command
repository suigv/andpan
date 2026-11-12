#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME="$ROOT/.runtime"
PORT="${ANDPAN_PORT:-43125}"
WIFI_PORT="$((PORT + 1))"
AUDIO_PORT="${ANDPAN_AUDIO_PORT:-8554}"
BUNDLE="$ROOT/AndPan Mac.app"
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH"
NODE="${NODE_BIN:-$(command -v node || true)}"
ADB="${ADB_BIN:-$(command -v adb || true)}"
[ -n "$NODE" ] || { echo "需要安装 Node.js。"; exit 1; }
mkdir -p "$RUNTIME"
chmod 700 "$RUNTIME"

devices=""
if [ -n "$ADB" ]; then devices="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"; fi
serial=""
if [ -n "${ANDROID_SERIAL:-}" ] && [ -n "$ADB" ]; then
  serial="$ANDROID_SERIAL"
elif [ "$(printf '%s\n' "$devices" | sed '/^$/d' | wc -l | tr -d ' ')" = 1 ]; then
  serial="$devices"
elif [ -n "$devices" ]; then
  echo "连接了多台安卓设备，跳过 USB 自动配对；可在手机连接设置中无线配对。"
fi
if [ -n "$serial" ]; then
  "$ADB" -s "$serial" get-state >/dev/null
  if ! "$ADB" -s "$serial" shell pm path com.andpan.deck | grep -q '^package:'; then
    echo "手机尚未安装 AndPan，跳过 USB 自动配对。"
    serial=""
  fi
  if [ -n "$serial" ]; then
    existing="$("$ADB" -s "$serial" reverse --list | awk -v local="tcp:$PORT" '$2==local {print $3}')"
    if [ -n "$existing" ] && [ "$existing" != "tcp:$PORT" ]; then
      echo "手机端口 $PORT 已被其他转发使用，跳过 USB 自动配对。"
      serial=""
    fi
  fi
fi
umask 077
if [ ! -f "$RUNTIME/wifi-key.pem" ] && [ ! -f "$RUNTIME/wifi-cert.pem" ]; then
  openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 3650 -subj "/CN=AndPan" \
    -keyout "$RUNTIME/wifi-key.pem" -out "$RUNTIME/wifi-cert.pem" >/dev/null 2>&1
fi
[ -f "$RUNTIME/wifi-key.pem" ] && [ -f "$RUNTIME/wifi-cert.pem" ] || { echo "无线连接证书不完整，请检查 .runtime。"; exit 1; }
if [ ! -s "$RUNTIME/token" ]; then
  "$NODE" -e "process.stdout.write(require('node:crypto').randomBytes(32).toString('base64url'))" > "$RUNTIME/token"
fi
token="$(cat "$RUNTIME/token")"
fingerprint="$("$NODE" -e 'process.stdout.write(new (require("node:crypto").X509Certificate)(require("node:fs").readFileSync(process.argv[1])).fingerprint256.replace(/:/g, "").toLowerCase())' "$RUNTIME/wifi-cert.pem")"
interface="$(route -n get default 2>/dev/null | awk '$1=="interface:" {print $2; exit}')"
wifi_host=""
if [ -n "$interface" ]; then wifi_host="$(ipconfig getifaddr "$interface" 2>/dev/null || true)"; fi
if [ -f "$RUNTIME/server.pid" ] && kill -0 "$(cat "$RUNTIME/server.pid")" 2>/dev/null; then
  pid="$(cat "$RUNTIME/server.pid")"
  if ! ps -p "$pid" -o command= | grep -Fq "$BUNDLE/Contents/MacOS/AndPan"; then
    echo "记录的进程不属于 AndPan，未进行更改。"
    exit 1
  fi
else
  if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo "Mac 端口 $PORT 已被占用。"
    exit 1
  fi
  if lsof -nP -iTCP:"$WIFI_PORT" -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo "Mac 无线端口 $WIFI_PORT 已被占用。"
    exit 1
  fi
  swiftc -O "$ROOT/mac/system.swift" -o "$RUNTIME/system"
  swiftc -O "$ROOT/mac/meter.swift" -o "$RUNTIME/meter"
  swiftc -O "$ROOT/mac/microphone.swift" -o "$RUNTIME/microphone"
  swiftc -O "$ROOT/mac/fn-key.swift" -o "$RUNTIME/fn-key"
  if [ -x /Applications/MicYou.app/Contents/MacOS/micyou-cli ]; then
    if [ ! -x "$RUNTIME/micyou-cli" ] || [ /Applications/MicYou.app/Contents/MacOS/micyou-cli -nt "$RUNTIME/micyou-cli" ]; then
      cp -f /Applications/MicYou.app/Contents/MacOS/micyou-cli "$RUNTIME/micyou-cli"
      chmod 700 "$RUNTIME/micyou-cli"
    fi
  fi
  if [ ! -x "$RUNTIME/micyou-cli" ] && [ ! -x /Applications/MicYou.app/Contents/MacOS/micyou-cli ]; then
    echo "未找到 MicYou 语音接收程序（需要 /Applications/MicYou.app）。"
    exit 1
  fi
  if [ ! -x "$BUNDLE/Contents/MacOS/AndPan" ] || [ "$ROOT/mac/helper.swift" -nt "$BUNDLE/Contents/MacOS/AndPan" ] ||
     ! /usr/libexec/PlistBuddy -c 'Print NSMicrophoneUsageDescription' "$BUNDLE/Contents/Info.plist" >/dev/null 2>&1; then
    mkdir -p "$BUNDLE/Contents/MacOS"
    cat > "$BUNDLE/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>AndPan</string>
  <key>CFBundleDisplayName</key><string>AndPan</string>
  <key>CFBundleIdentifier</key><string>com.local.andpan.deck</string>
  <key>CFBundleExecutable</key><string>AndPan</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSUIElement</key><true/>
  <key>NSMicrophoneUsageDescription</key><string>显示 Mac 输入设备的实时音量。</string>
</dict></plist>
PLIST
    swiftc -O "$ROOT/mac/helper.swift" -o "$BUNDLE/Contents/MacOS/AndPan"
    codesign --force --sign - "$BUNDLE"
  fi
  : > "$RUNTIME/server.log"
  open -n "$BUNDLE" --args "$ROOT" "$NODE" "$PORT"
  ready=""
  for _ in $(seq 1 40); do
    if curl -fsS --connect-timeout 1 -H "Authorization: Bearer $token" "http://127.0.0.1:$PORT/status" >/dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 0.3
  done
  if [ -z "$ready" ]; then
    echo "AndPan 启动失败："
    cat "$RUNTIME/server.log"
    exit 1
  fi
fi
if ! curl -fsS --noproxy '*' --connect-timeout 2 --cacert "$RUNTIME/wifi-cert.pem" \
    --resolve "andpan:$WIFI_PORT:127.0.0.1" -H "Authorization: Bearer $token" \
    "https://andpan:$WIFI_PORT/status" >/dev/null 2>&1; then
  echo "无线服务尚未就绪；请先运行 mac/stop.command，再重新启动。"
  exit 1
fi
if [ -n "$serial" ]; then
  "$ADB" -s "$serial" reverse "tcp:$PORT" "tcp:$PORT"
  audio_existing="$("$ADB" -s "$serial" reverse --list | awk -v local="tcp:$AUDIO_PORT" '$2==local {print $3}')"
  if [ -z "$audio_existing" ] || [ "$audio_existing" = "tcp:$AUDIO_PORT" ]; then
    "$ADB" -s "$serial" reverse "tcp:$AUDIO_PORT" "tcp:$AUDIO_PORT"
  fi
  "$ADB" -s "$serial" shell am start -n com.andpan.deck/.MainActivity \
    --es token "$token" --ei port "$PORT" \
    --es wifiHost "$wifi_host" --es wifiFingerprint "$fingerprint" --ei wifiPort "$WIFI_PORT" >/dev/null
  echo "已通过 USB 设置手机连接；完成后可以拔线。"
else
  echo "AndPan 已启动；已配对的手机可通过局域网连接。"
fi
echo "Mac 地址：${wifi_host:-未检测到局域网地址}；已内置语音接收服务。"
echo "手机面板会显示当前 Mac 应用；发送前请先在目标应用中选中输入框。"
