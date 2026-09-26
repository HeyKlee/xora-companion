#!/usr/bin/env bash
# Build the XORA Companion APK with the SDK build tools directly.
#
# Gradle 4.4.1 on this host is too old for android-35, and the plugin download is
# an extra moving part, so this drives aapt2 -> javac -> d8 -> zipalign -> apksigner
# explicitly. Every step is real and verifiable; nothing is stubbed.
set -euo pipefail

SDK=/home/kelly/.hermes/android-sdk
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
PROJ=/home/kelly/xora-companion
OUT="$PROJ/build"
KEYSTORE="$PROJ/release.jks"

rm -rf "$OUT" "$PROJ/build"
mkdir -p "$OUT/compiled" "$OUT/classes" "$OUT/apk"

echo "[1/6] aapt2 compile resources"
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/compiled/res.zip"

echo "[2/6] aapt2 link"
# The compiled archive must be a POSITIONAL arg. Passing it via -R marks it as an
# overlay, which makes aapt2 demand an existing resource to override and fails.
"$BT/aapt2" link \
  -o "$OUT/apk/base.apk" \
  -I "$PLATFORM" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --java "$OUT/gen" \
  "$OUT/compiled/res.zip" \
  --min-sdk-version 26 \
  --target-sdk-version 35 \
  --version-code 1 \
  --version-name 1.0

echo "[3/6] javac"
# JDK 21 rejects -bootclasspath together with -target, so android.jar goes on the
# regular classpath instead. The unavoidable "bootstrap class path not set" warning
# is filtered, but javac's exit status is still enforced so a real error fails the
# build rather than being swallowed.
find "$PROJ/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
if ! javac -nowarn \
      -source 17 -target 17 \
      -classpath "$PLATFORM" \
      -d "$OUT/classes" \
      @"$OUT/sources.txt" 2> "$OUT/javac.log"; then
  grep -v 'bootstrap class path' "$OUT/javac.log" >&2 || true
  echo "JAVAC FAILED" >&2
  exit 1
fi
grep -v 'bootstrap class path' "$OUT/javac.log" >&2 || true

echo "[4/6] d8 dex"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
# d8 requires the --output directory to already exist; it will not create it.
mkdir -p "$OUT/dex"
"$BT/d8" --release --min-api 26 --lib "$PLATFORM" \
  --output "$OUT/dex" \
  @"$OUT/classes.txt"

echo "[5/6] assemble apk"
cp "$OUT/apk/base.apk" "$OUT/apk/unsigned.apk"
cd "$OUT/dex"
zip -q -X "$OUT/apk/unsigned.apk" classes.dex

echo "[6/6] sign"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -storepass xora-companion -keypass xora-companion \
    -alias xora -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=XORA Companion, OU=XORA, O=XORA, L=NZ, C=NZ" >/dev/null 2>&1
fi
"$BT/zipalign" -f -p 4 "$OUT/apk/unsigned.apk" "$OUT/apk/aligned.apk"
"$BT/apksigner" sign \
  --ks "$KEYSTORE" --ks-pass pass:xora-companion --key-pass pass:xora-companion \
  --ks-key-alias xora \
  --out "$PROJ/XORACompanion.apk" \
  "$OUT/apk/aligned.apk"

"$BT/apksigner" verify --print-certs "$PROJ/XORACompanion.apk" >/dev/null
echo "BUILD OK: $PROJ/XORACompanion.apk"
