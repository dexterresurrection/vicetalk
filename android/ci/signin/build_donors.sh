#!/bin/bash
# Test donors for the shared sign-in check:  build_donors.sh <out dir>   (Android SDK of the runner, no Gradle)
#  ci-vpn    uk.zjd.cloudvpn    key «donor», account of cabinet.zjd.uk          — can confirm for Cloud Talk
#  ci-old    uk.zjd.cloudmail   key «donor», no «origin» in the answer          — can confirm for Cloud Talk only
#  ci-wave   uk.zjd.vicewave    key «donor», account of vice.zjd.uk             — can confirm for Vice Talk only
#  ci-fake   uk.zjd.cloudsocial key «other»: the package is in the list, the certificate is not the listed one
set -euo pipefail
OUT=$(mkdir -p "$1" && cd "$1" && pwd)
HERE=$(cd "$(dirname "$0")" && pwd)
BT=$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)
JAR=$ANDROID_HOME/platforms/$(ls "$ANDROID_HOME/platforms" | grep -E '^android-[0-9]+$' | sort -V | tail -1)/android.jar
for k in donor other; do
  [ -f "$OUT/$k.jks" ] || keytool -genkeypair -keystore "$OUT/$k.jks" -storepass android -keypass android -alias ci -keyalg RSA -keysize 2048 -validity 30 -dname "CN=$k" >/dev/null 2>&1
done
make() {   # make <file> <package> <label> <name> <origin> <key>
  local w; w=$(mktemp -d)
  for f in Donor.java SignInApproveActivity.java AndroidManifest.xml; do
    sed -e "s|@PKG@|$2|g" -e "s|@LABEL@|$3|g" -e "s|@NAME@|$4|g" -e "s|@ORIGIN@|$5|g" "$HERE/donor/$f" > "$w/$f"
  done
  "$BT/aapt2" link -o "$w/base.apk" -I "$JAR" --manifest "$w/AndroidManifest.xml"
  mkdir "$w/classes" && javac -nowarn -source 8 -target 8 -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" -d "$w/classes" "$w"/*.java
  "$BT/d8" --release --min-api 26 --lib "$JAR" --output "$w" $(find "$w/classes" -name '*.class')
  (cd "$w" && zip -q base.apk classes.dex)
  "$BT/zipalign" -f 4 "$w/base.apk" "$w/aligned.apk"
  "$BT/apksigner" sign --ks "$OUT/$6.jks" --ks-pass pass:android --key-pass pass:android --out "$OUT/$1.apk" "$w/aligned.apk"
  rm -rf "$w"
}
make ci-vpn uk.zjd.cloudvpn "CI VPN" "CI Anna" "https://cabinet.zjd.uk" donor
make ci-old uk.zjd.cloudmail "CI Old" "CI Anna" "" donor
make ci-wave uk.zjd.vicewave "CI Wave" "CI Vera" "https://vice.zjd.uk" donor
make ci-fake uk.zjd.cloudsocial "CI Fake" "CI Mallory" "https://cabinet.zjd.uk" other
"$BT/apksigner" verify --print-certs "$OUT/ci-vpn.apk" | sed -n 's/.*SHA-256 digest: //p' | head -1 > "$OUT/donor.sha256"
ls -la "$OUT"/*.apk; echo "donor certificate $(cat "$OUT/donor.sha256")"
