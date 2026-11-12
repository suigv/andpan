#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${ANDPAN_TOOLCHAIN:-$HOME/.cache/andpan-toolchain}"
export JAVA_HOME="${JAVA_HOME:-$TOOLS/jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_SDK_ROOT:-$TOOLS/android-sdk}"
BUILD_TOOLS="$SDK/build-tools/36.0.0"
ANDROID_JAR="$SDK/platforms/android-25/android.jar"
BUILD="$ROOT/.build/android"
KEYSTORE="$TOOLS/debug.keystore"
for file in "$JAVA_HOME/bin/javac" "$ANDROID_JAR" "$BUILD_TOOLS/aapt" "$BUILD_TOOLS/d8" "$BUILD_TOOLS/apksigner"; do
  if [ ! -e "$file" ]; then echo "缺少安卓构建工具：$file" >&2; exit 1; fi
done
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex"
"$BUILD_TOOLS/aapt" package -f -M "$ROOT/android/AndroidManifest.xml" \
  -S "$ROOT/android/res" -I "$ANDROID_JAR" -F "$BUILD/resources.apk"
"$JAVA_HOME/bin/javac" --release 8 -encoding UTF-8 -classpath "$ANDROID_JAR" \
  -d "$BUILD/classes" "$ROOT"/android/src/com/andpan/deck/*.java
"$JAVA_HOME/bin/jar" cf "$BUILD/classes.jar" -C "$BUILD/classes" .
"$BUILD_TOOLS/d8" --min-api 24 --lib "$ANDROID_JAR" --output "$BUILD/dex" "$BUILD/classes.jar"
cp "$BUILD/resources.apk" "$BUILD/unsigned.apk"
(cd "$BUILD/dex" && zip -q "$BUILD/unsigned.apk" classes.dex)
"$BUILD_TOOLS/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
if [ ! -f "$KEYSTORE" ]; then
  umask 077
  "$JAVA_HOME/bin/keytool" -genkeypair -noprompt -keystore "$KEYSTORE" \
    -storepass android -keypass android -alias andpan -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=AndPan local development"
fi
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android \
  --key-pass pass:android --out "$BUILD/andpan.apk" "$BUILD/aligned.apk"
"$BUILD_TOOLS/apksigner" verify --min-sdk-version 24 "$BUILD/andpan.apk"
echo "APK：$BUILD/andpan.apk"
