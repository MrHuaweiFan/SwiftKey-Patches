#!/usr/bin/env bash
# Assembles the patches-1.1.0.mpp bundle manually, mirroring exactly what the
# app.morphe.patches gradle plugin produces (jar + buildAndroid D8 merge):
#   META-INF/MANIFEST.MF, META-INF/patches.kotlin_module, classes.dex,
#   extensions/swiftkey.mpe, extensions/swiftkey-klipy.mpe, hooman/**/*.class
#
# 1.1.0: the blank elements count option (1-6), the new Remove GIFs and translator
# patch, the restored original Disable telemetry patch (replacing the removed Network
# connections patch), one-line patch descriptions, the Debloat smart clip search
# button removal, and the new SwiftKey factory settings patches.
set -euo pipefail

ROOT=/home/z/my-project/swiftkey-patches-src
TOOLS=/home/z/my-project/work/tools
VERSION=1.1.0
CP="$(cat $TOOLS/patcher-cp-abs.txt | tr '\n' ':')$TOOLS/runtime-libs/morphe-patcher-1.5.2.jar:$TOOLS/runtime-libs/gson-2.14.0.jar"
CLASSES=/tmp/patch-classes-110
OUT=$ROOT/patches/build/libs

# --- compile the patch sources (Kotlin) ---
rm -rf "$CLASSES" && mkdir -p "$CLASSES"
$TOOLS/kotlinc/bin/kotlinc -Xcontext-parameters -jvm-target 11 -cp "$CP" -d "$CLASSES" \
  $ROOT/patches/src/main/kotlin/

rm -rf /tmp/mpp-staging-110 && mkdir -p /tmp/mpp-staging-110/META-INF /tmp/mpp-staging-110/extensions
cd /tmp/mpp-staging-110

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
Description: Morphe patches for Microsoft SwiftKey 9.12.13.13 and SwiftKey factory settings 2.2.0.314.
Version: $VERSION
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
rm -f $OUT/patches-$VERSION.mpp
zip -q -X $OUT/patches-$VERSION.mpp META-INF/MANIFEST.MF
zip -q -rX $OUT/patches-$VERSION.mpp META-INF/patches.kotlin_module classes.dex extensions hooman

echo "Assembled $OUT/patches-$VERSION.mpp:"
unzip -l $OUT/patches-$VERSION.mpp | tail -4
