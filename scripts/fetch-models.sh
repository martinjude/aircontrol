#!/usr/bin/env bash
# Downloads the MediaPipe models bundled in the APK.
set -euo pipefail
cd "$(dirname "$0")/.."
ASSETS=app/src/main/assets
mkdir -p "$ASSETS"
base=https://storage.googleapis.com/mediapipe-models
fetch() {
  local url="$1" out="$ASSETS/$2"
  if [[ -s "$out" ]]; then echo "ok   $out"; return; fi
  echo "get  $out"
  curl -fL --retry 3 -o "$out.part" "$url" && mv "$out.part" "$out"
}
fetch "$base/gesture_recognizer/gesture_recognizer/float16/latest/gesture_recognizer.task" gesture_recognizer.task
fetch "$base/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task" pose_landmarker_lite.task
