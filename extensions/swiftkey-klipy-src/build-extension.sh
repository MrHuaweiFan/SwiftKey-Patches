#!/usr/bin/env bash
#
# Builds the KLIPY GIF extension (extensions/swiftkey-klipy.mpe) from the Java
# sources in extensions/swiftkey-klipy-src. Kept separate from the network
# extension (extensions/swiftkey.mpe) so the "GIF source" patch can be applied
# without the "Network connections" patch and vice versa, with no double merge
# of either extension.
#
# Same manual javac/ecj + d8 pipeline as the swiftkey extension: the repository
# needs no Android SDK. This sandbox has a JRE only, so ecj compiles the
# sources and the R8/D8 compiler jar produces the dex.
#
# Usage: ./build-extension.sh
set -euo pipefail

TOOLS=/home/z/my-project/work/tools
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE"
OUT="$HERE/build"
ECJ="$TOOLS/ecj.jar"
R8_JAR="$TOOLS/r8-8.5.35.jar"
ANDROID_JAR="$TOOLS/android-4.1.1.4.jar"

rm -rf "$OUT"
mkdir -p "$OUT/stub-classes" "$OUT/classes"

echo "==> Compiling compile-only stubs"
find "$SRC/stubs" -name '*.java' > "$OUT/stub-sources.txt"
java -jar "$ECJ" -source 8 -target 8 -nowarn -warn:-options \
  -classpath "$ANDROID_JAR" \
  -d "$OUT/stub-classes" @"$OUT/stub-sources.txt"

echo "==> Compiling extension sources"
find "$SRC/src" -name '*.java' > "$OUT/sources.txt"
java -jar "$ECJ" -source 8 -target 8 -nowarn -warn:-options \
  -classpath "$OUT/stub-classes:$ANDROID_JAR" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "==> Dexing only the extension classes (stubs stay behind)"
java -cp "$R8_JAR" com.android.tools.r8.D8 \
  --release --min-api 26 \
  --output "$OUT" \
  $(find "$OUT/classes" -name '*.class')

test -f "$OUT/classes.dex"

echo "==> Copying to patches resources as extensions/swiftkey-klipy.mpe"
mkdir -p "$HERE/../../patches/src/main/resources/extensions"
cp "$OUT/classes.dex" "$HERE/../../patches/src/main/resources/extensions/swiftkey-klipy.mpe"
echo "Done: patches/src/main/resources/extensions/swiftkey-klipy.mpe"
