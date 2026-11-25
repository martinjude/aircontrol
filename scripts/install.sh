#!/usr/bin/env bash
# Installs (or updates) dist/AirControl-<version>.apk on the connected device over adb and keeps the
# accessibility service switched on: grants WRITE_SECURE_SETTINGS once (kept across updates), so the
# app can switch its own service back on after an update, and switches it on now if it is off.
#
#   ./scripts/install.sh            # the APK for the version in app/build.gradle.kts
#   ./scripts/install.sh path.apk   # a specific APK
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/platform-tools/adb"
command -v adb >/dev/null 2>&1 && ADB=adb
version=$(grep -m1 'versionName' app/build.gradle.kts | sed -E 's/.*"(.*)".*/\1/')
apk="${1:-dist/AirControl-$version.apk}"
[[ -f "$apk" ]] || { echo "No $apk: build it with ./scripts/build-apk.sh"; exit 1; }

"$ADB" install -r "$apk"
"$ADB" shell pm grant app.aircontrol android.permission.WRITE_SECURE_SETTINGS
service="app.aircontrol/app.aircontrol.service.AirControlAccessibilityService"
current=$("$ADB" shell settings get secure enabled_accessibility_services | tr -d '\r')
if [[ "$current" != *"$service"* ]]; then
  if [[ -z "$current" || "$current" == "null" ]]; then next="$service"; else next="$current:$service"; fi
  "$ADB" shell settings put secure enabled_accessibility_services "$next"
  "$ADB" shell settings put secure accessibility_enabled 1
  echo "Accessibility service switched on"
else
  echo "Accessibility service already on"
fi
