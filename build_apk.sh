#!/bin/sh
# Builds Music2NES.apk with the plain SDK tools (no Gradle).
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-/opt/android-sdk}
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-35/android.jar
OUT=build
rm -rf $OUT && mkdir -p $OUT/cls $OUT/res
$BT/aapt2 compile --dir res -o $OUT/res.zip
$BT/aapt2 link -I $JAR --manifest AndroidManifest.xml -o $OUT/base.apk $OUT/res.zip
javac --release 8 -classpath $JAR -Xlint:-options -d $OUT/cls src/com/music2nes/*.java
$BT/d8 --release --min-api 21 --lib $JAR --output $OUT $(find $OUT/cls -name '*.class')
cp $OUT/base.apk $OUT/unsigned.apk
(cd $OUT && zip -q unsigned.apk classes.dex)
$BT/zipalign -f 4 $OUT/unsigned.apk $OUT/aligned.apk
[ -f music2nes.keystore ] || keytool -genkeypair -keystore music2nes.keystore -storepass music2nes \
    -keypass music2nes -alias music2nes -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Music2NES" >/dev/null 2>&1
$BT/apksigner sign --ks music2nes.keystore --ks-pass pass:music2nes --ks-key-alias music2nes \
    --out Music2NES.apk $OUT/aligned.apk
$BT/apksigner verify Music2NES.apk && echo "built $(pwd)/Music2NES.apk"
