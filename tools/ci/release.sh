#!/bin/sh
# The release build of CI (release.Dockerfile): the TV's APK on the release key, which BuildKit hands in as secrets,
# and the desktop's .deb.
#   $1/sleepyowl.apk          the APK (R8 without obfuscation: no mapping needed for stack traces)
#   $1/sleepyowl_*_amd64.deb  the .deb
#   $1/cert-sha256.txt        the APK's signing certificate, SHA-256 (as apksigner prints it)
#   $1/version.txt            "<versionName> <versionCode>", as the APK has them
# VERSION_NAME overrides gradle.properties' sleepyowl.versionName.
set -eu

OUT=$1
APK=app/build/outputs/apk/release/app-release.apk

# Without the key Gradle signs with a debug key made up in the container: such an APK installs over nothing.
[ -s /run/secrets/keystore ] || { echo "No keystore secret: the release key is required." >&2; exit 1; }
ANDROID_KEYSTORE=/run/secrets/keystore
ANDROID_KEYSTORE_PASSWORD=$(cat /run/secrets/keystore_password)
ANDROID_KEY_ALIAS=$(cat /run/secrets/key_alias 2>/dev/null || true)
export ANDROID_KEYSTORE ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS

./gradlew :app:assembleRelease :desktop:packageDeb \
  ${VERSION_NAME:+"-Psleepyowl.versionName=$VERSION_NAME"}

buildtools=$(ls -d "$ANDROID_HOME"/build-tools/* | tail -n 1)
digest=$("$buildtools/apksigner" verify --print-certs "$APK" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
[ -n "$digest" ] || { echo "apksigner printed no certificate digest." >&2; exit 1; }
line=$("$buildtools/aapt2" dump badging "$APK" | sed -n '1p')
name=$(printf '%s' "$line" | sed -n "s/.* versionName='\([^']*\)'.*/\1/p")
code=$(printf '%s' "$line" | sed -n "s/.* versionCode='\([0-9]*\)'.*/\1/p")
[ -n "$name" ] && [ -n "$code" ] || { echo "No version in the APK's manifest: $line" >&2; exit 1; }

mkdir -p "$OUT"
cp "$APK" "$OUT/sleepyowl.apk"
cp desktop/build/deb/sleepyowl_*_amd64.deb "$OUT/"
printf '%s\n' "$digest" > "$OUT/cert-sha256.txt"
printf '%s %s\n' "$name" "$code" > "$OUT/version.txt"

# What the build left in /work would land in this RUN's layer, and BuildKit keeps such a layer from every run
# (--no-cache): only the sources stay. (.dockerignore keeps every build/ out of the context, so these are all Gradle's.)
find . -type d -name build -prune -exec rm -rf {} +
rm -rf .gradle .kotlin
