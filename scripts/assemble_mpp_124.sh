#!/usr/bin/env bash
# Assembles the patches-1.2.4.mpp bundle manually, mirroring exactly what the
# app.morphe.patches gradle plugin produces (jar + buildAndroid D8 merge):
#   META-INF/MANIFEST.MF, META-INF/patches.kotlin_module, classes.dex,
#   extensions/swiftkey.mpe, extensions/swiftkey-klipy.mpe, hooman/**/*.class
set -euo pipefail

ROOT=/home/z/my-project/work/src-1.2.3
TOOLS=/home/z/my-project/work/tools
CP="$(cat $TOOLS/patcher-cp-abs.txt):$TOOLS/runtime-libs/morphe-patcher-1.5.2.jar:$TOOLS/runtime-libs/gson-2.14.0.jar"
CLASSES=/tmp/patch-classes
OUT=$ROOT/patches/build/libs

# --- compile the patch sources (Kotlin) ---
rm -rf "$CLASSES" && mkdir -p "$CLASSES"
$TOOLS/kotlinc/bin/kotlinc -Xcontext-parameters -cp "$CP" -d "$CLASSES" \
  $ROOT/patches/src/main/kotlin/

rm -rf /tmp/mpp-staging && mkdir -p /tmp/mpp-staging/META-INF /tmp/mpp-staging/extensions
cd /tmp/mpp-staging

# --- classes.dex: D8 in release mode over the patch classes (like buildAndroid) ---
java -cp $TOOLS/r8-8.5.35.jar com.android.tools.r8.D8 \
  --release \
  --output . \
  $(find $CLASSES/hooman -name '*.class')
test -f classes.dex

# --- extension resources ---
cp $ROOT/patches/src/main/resources/extensions/swiftkey.mpe extensions/
cp $ROOT/patches/src/main/resources/extensions/swiftkey-klipy.mpe extensions/

# --- kotlin module metadata (the bundle loader expects this exact name) ---
cp $CLASSES/META-INF/main.kotlin_module META-INF/patches.kotlin_module

# --- patch classes (the Jar loader half of the bundle) ---
cp -r $CLASSES/hooman .

# --- MANIFEST.MF (same attributes the plugin writes) ---
TS=$(date +%s%3N)
cat > META-INF/MANIFEST.MF <<MANIFESTEOF
Manifest-Version: 1.0
Name: SwiftKey Patches
Description: Morphe patches for Microsoft SwiftKey 9.12.13.13.
Version: 1.2.4
Timestamp: $TS
Source: git@github.com:MrHuaweiFan/SwiftKey-Patches.git
Author: MrHuaweiFan
Contact: na
Website: https://github.com/MrHuaweiFan/SwiftKey-Patches
License: GPLv3
Patcher-Version: 1.5.2

MANIFESTEOF

# --- pack (manifest first, like the gradle jar task) ---
mkdir -p $OUT
rm -f $OUT/patches-1.2.4.mpp
zip -q -X $OUT/patches-1.2.4.mpp META-INF/MANIFEST.MF
zip -q -rX $OUT/patches-1.2.4.mpp META-INF/patches.kotlin_module classes.dex extensions hooman

echo "Assembled $OUT/patches-1.2.4.mpp:"
unzip -l $OUT/patches-1.2.4.mpp | tail -4
