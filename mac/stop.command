#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PID_FILE="$ROOT/.runtime/server.pid"
BUNDLE="$ROOT/AndPan Mac.app"
if [ ! -f "$PID_FILE" ]; then echo "AndPan 未运行。"; exit 0; fi
pid="$(cat "$PID_FILE")"
if kill -0 "$pid" 2>/dev/null; then
  if ! ps -p "$pid" -o command= | grep -Fq "$BUNDLE/Contents/MacOS/AndPan"; then
    echo "记录的进程不属于 AndPan，未进行更改。"
    exit 1
  fi
  kill "$pid"
  for _ in $(seq 1 30); do
    if ! kill -0 "$pid" 2>/dev/null; then break; fi
    sleep 0.2
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "AndPan 正在退出，请稍后再启动。"
    exit 1
  fi
fi
rm -f "$PID_FILE"
echo "AndPan 及内置语音服务已停止；Mac 输入保持静音。"
