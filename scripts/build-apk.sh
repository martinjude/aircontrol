#!/usr/bin/env bash
# Builds the signed release APK and copies it to dist/.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
./scripts/fetch-models.sh
if [[ ! -f keystore.properties ]]; then
  mkdir -p keystore
  pw=$(openssl rand -hex 16)
  "$JAVA_HOME/bin/keytool" -genkeypair -v -keystore keystore/aircontrol-release.jks -alias aircontrol \
    -keyalg RSA -keysize 4096 -validity 36500 -storepass "$pw" -keypass "$pw" \
    -dname "CN=AirControl, O=AirControl" >/dev/null 2>&1
  printf 'storeFile=keystore/aircontrol-release.jks\nstorePassword=%s\nkeyAlias=aircontrol\nkeyPassword=%s\n' "$pw" "$pw" > keystore.properties
  echo "Created release keystore in keystore/ (keep it: updates must be signed with the same key)"
fi
./gradlew --no-daemon assembleRelease
version=$(grep -m1 'versionName' app/build.gradle.kts | sed -E 's/.*"(.*)".*/\1/')
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk "dist/AirControl-$version.apk"
echo "APK: dist/AirControl-$version.apk"
