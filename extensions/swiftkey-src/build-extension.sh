#!/usr/bin/env bash
#
# Builds the SwiftKey Morphe extension (extensions/swiftkey.mpe) from the Java
# sources in extensions/swiftkey-src and copies it into the patches project
# resources, where the patches reference it with
# extendWith("extensions/swiftkey.mpe") - the same path layout the official
# Morphe patch bundles use.
#
# The extension is produced manually with javac + d8 instead of an Android
# Gradle project on purpose: the repository (and CI) then needs no Android SDK
# at all to build the patches bundle.
#
# Requirements:
#   - JDK 11+
#   - An android.jar on the classpath path (com.google.android:android:4.1.1.4
#     from Maven Central is enough: the extension only uses framework APIs
#     available since API 16, while SwiftKey itself requires API 26).
#   - The R8/D8 compiler jar (com.android.tools:r8) from Google's Maven repo.
#
# Usage: ./build-extension.sh [path-to-r8.jar] [path-to-android.jar]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE"
OUT="$HERE/build"
R8_JAR="${1:-$HOME/r8.jar}"
ANDROID_JAR="${2:-$HOME/android.jar}"

if [ ! -f "$R8_JAR" ]; then
  echo "R8 jar not found at $R8_JAR" >&2
  echo "Download it from https://maven.google.com (com.android.tools:r8)" >&2
  exit 1
fi
if [ ! -f "$ANDROID_JAR" ]; then
  echo "android.jar not found at $ANDROID_JAR" >&2
  echo "Download com.google.android:android:4.1.1.4 from Maven Central" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/stub-classes" "$OUT/classes"

echo "==> Compiling compile-only stubs (the R8-renamed app classes the extension links against)"
find "$SRC/stubs" -name '*.java' > "$OUT/stub-sources.txt"
javac -source 8 -target 8 -nowarn \
  -classpath "$ANDROID_JAR" \
  -d "$OUT/stub-classes" @"$OUT/stub-sources.txt"

echo "==> Compiling extension sources"
find "$SRC/src" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -nowarn \
  -classpath "$OUT/stub-classes:$ANDROID_JAR" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "==> Dexing only the extension classes (stubs stay behind)"
java -cp "$R8_JAR" com.android.tools.r8.D8 \
  --release --min-api 26 \
  --output "$OUT" \
  $(find "$OUT/classes" -name '*.class')

if [ ! -f "$OUT/classes.dex" ]; then
  echo "D8 did not produce classes.dex" >&2
  exit 1
fi

echo "==> Copying to patches resources as extensions/swiftkey.mpe"
mkdir -p "$HERE/../../patches/src/main/resources/extensions"
cp "$OUT/classes.dex" "$HERE/../../patches/src/main/resources/extensions/swiftkey.mpe"

echo "Done: patches/src/main/resources/extensions/swiftkey.mpe"
