#!/usr/bin/env bash
# Compilation de l'APK sans Gradle ni Android Studio :
#   aapt2 (ressources) -> javac (Java 8) -> dx (bytecode Dalvik) -> zipalign -> apksigner
#
# Prérequis (Debian/Ubuntu) : sudo apt install openjdk-17-jdk aapt apksigner zipalign dalvik-exchange
#   Fedora : utiliser plutôt le SDK Android officiel (build-tools) et définir les variables ci-dessous.
# android.jar : plate-forme API 34, par ex. depuis le SDK Android ($ANDROID_HOME/platforms/android-34/android.jar).
#
# Variables : ANDROID_JAR (obligatoire), KEYSTORE / KS_PASS (signature ; une clé de debug est créée sinon)
set -euo pipefail
cd "$(dirname "$0")"

: "${ANDROID_JAR:?Définir ANDROID_JAR vers platforms/android-34/android.jar}"
AAPT2=${AAPT2:-aapt2}
DX=${DX:-dalvik-exchange}
ZIPALIGN=${ZIPALIGN:-zipalign}
APKSIGNER=${APKSIGNER:-apksigner}
KEYSTORE=${KEYSTORE:-$PWD/debug.keystore}
KS_PASS=${KS_PASS:-android}
OUT=build

rm -rf "$OUT" && mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes"

echo "1/5 ressources"
"$AAPT2" compile --dir app/res -o "$OUT/res/res.zip"
"$AAPT2" link -o "$OUT/unsigned.apk" -I "$ANDROID_JAR" \
    --manifest app/AndroidManifest.xml --java "$OUT/gen" \
    --min-sdk-version 30 --target-sdk-version 34 \
    "$OUT/res/res.zip"

echo "2/5 java"
find app/src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
    -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "3/5 dex"
"$DX" --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes"
(cd "$OUT" && zip -q -j unsigned.apk classes.dex)

echo "4/5 alignement"
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "5/5 signature"
if [[ ! -f "$KEYSTORE" ]]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -alias julia -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Julia Wallpaper, O=PROJ-julia-wallpaper-android" >/dev/null 2>&1
fi
"$APKSIGNER" sign --v2-signing-enabled true --v3-signing-enabled true --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --ks-key-alias julia \
    --out "$OUT/julia-wallpaper.apk" "$OUT/aligned.apk"
"$APKSIGNER" verify "$OUT/julia-wallpaper.apk"
ls -l "$OUT/julia-wallpaper.apk"
