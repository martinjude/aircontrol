# AirControl

Hands-free control of an Android phone or tablet with upper-body, hand and finger gestures, using
the front/back camera or a network (IP) camera. Everything runs on the device.

- **🤟 hold 1 s** turns gesture mode on. A glowing border around the screen shows that AirControl is
  in control. Hold 🤟 again to turn it off; it also turns off after 20 s without a hand.
- **Live pointer** follows your index fingertip ☝️. Upper-body tracking anchors it to your
  shoulders, so the same arm movement works at any distance. **👍 then point** recenters it.
- **Quick push towards the camera** = tap (the pointer freezes during the push).
- **Quick pinch 🤏** = tap · **pinch, move, release** = drag/scroll · **pinch and hold** = long press
- **Flat hand held 0.3 s** arms swipes ("Swipe armed"): palm sideways = left/right strokes, palm
  down = up/down strokes. Stays armed while the hand stays flat.
- **👎** = Back · **✊ hold** = Home · **✌️** = Recents (remappable; 👍 = recenter by default)

## Install

Build the APK with `./scripts/build-apk.sh` (see *Build from source*); it lands in
`dist/AirControl-<version>.apk` (arm64, Android 10+). APKs are not committed to the repository. It
was tested on a Pixel 8 and a Pixel 8-class emulator running Android 17.

### Option A: adb (recommended)
1. On the phone/tablet: Settings → About → tap *Build number* 7× → Developer options →
   enable *USB debugging* (or *Wireless debugging*).
2. On the Mac:
   ```sh
   export PATH=/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH
   adb devices                                  # accept the prompt on the device
   adb install -r dist/AirControl-2.0.0.apk
   ```
   For Wireless debugging use `adb pair <ip>:<pair-port>` then `adb connect <ip>:<port>`.

### Option B: copy the APK to the device
Open the file and allow your file manager to install unknown apps.
- **Samsung:** if the install is blocked, turn off *Settings → Security and privacy → Auto Blocker*
  temporarily.
- If Android warns about an unverified developer (2026 developer-verification rollout), use
  Option A instead.

## First run
1. Open **AirControl → Control** and work through the *Setup* list:
   - **Camera** and **Notifications**: allow.
   - **Accessibility service**: *Open* → enable *AirControl gestures*. If the switch is greyed
     out ("Restricted setting"): *App info → ⋮ → Allow restricted settings*, then enable it.
   - **Battery: unrestricted**: allow, so tracking isn't killed in the background (important on
     Samsung).
2. Use *Test without the camera* to check that the glow, taps, swipes and Back/Home/Recents work on
   your device.
3. Tap **Start**. A notification stays visible while AirControl watches the camera; it has
   *Arm now / Disarm* and *Stop* buttons.
4. Face the camera at 0.5–2 m with head and shoulders in frame, then hold 🤟 for a second.

**Camera → Live tracking** shows what the model sees (skeleton, detected sign, pinch meter).
**Settings → Debug HUD** shows fps, latency and the detected sign on top of any app.

## Network / IP camera
Camera tab → *Network* → enter the stream URL (same Wi‑Fi):

| Source | URL |
|---|---|
| IP Webcam (Android app) | `http://<ip>:8080/video` |
| DroidCam | `http://<ip>:4747/video` |
| ESP32-CAM | `http://<ip>:81/stream` |
| Snapshot-only cameras | `http://<ip>/snapshot.jpg` (polled) |
| RTSP cameras | `rtsp://user:pass@<ip>:554/stream` |

MJPEG has the lowest latency. RTSP works too but usually lags more. Credentials can go in the URL
(`http://user:pass@host/...`). Keep *Mirror* on when the camera faces you, and use *Rotation* if
the image is sideways.

## Settings worth knowing
- **Pointer sensitivity / smoothing**: how much arm movement covers the screen, and jitter vs lag.
- **Anchor to shoulders**: on = body-relative box (needs upper-body tracking); off = the box is
  centred where your hand first appears.
- **Push sensitivity**: higher = a shorter push clicks; lower it if clicks fire by accident.
- **Stroke sensitivity**: how short/slow a stroke can be once swipe mode is armed.
- **Debug HUD** shows `push` (hand growth, a click needs about +0.10 by default) and `blade`
  (V / H / -), useful for tuning.
- **Require a person in view**: ignores hands unless shoulders are visible.
- **Processor**: Auto tries the GPU, then falls back to the CPU. Force CPU if tracking misbehaves.

## Build from source
Requirements (already installed on this Mac): JDK 17 (`brew install openjdk@17`) and the Android
command-line tools (`brew install --cask android-commandlinetools`, SDK at
`/opt/homebrew/share/android-commandlinetools`).

```sh
./scripts/fetch-models.sh       # downloads the MediaPipe models into app/src/main/assets (not in git)
./scripts/build-apk.sh          # downloads models, creates a release key on first run, builds dist/AirControl-<version>.apk
./gradlew testDebugUnitTest     # gesture-engine unit tests (JVM, no device needed)
./gradlew assembleDebug         # debug build
```

`keystore/` and `keystore.properties` hold the release signing key. **Keep them.** Updates must be
signed with the same key, otherwise Android refuses to install over the existing app.

## How it works
```
camera / MJPEG / RTSP ──► VisionPipeline (MediaPipe GestureRecognizer + PoseLandmarker, on-device)
        (TrackingService, foreground)         │ hand landmarks + sign + shoulders, user-view coords
                                              ▼
                                   GestureEngine (pure Kotlin, unit-tested)
                                              │ Armed / Pointer / Tap / TouchDown·Move·Up / Swipe / Global
                                              ▼ ControlBus
                       AirControlAccessibilityService ─► dispatchGesture / performGlobalAction
                                              └────────► OverlayController (glow, pointer, pill)
```

| Path | What |
|---|---|
| `app/src/main/java/app/aircontrol/gesture/` | Engine, One-Euro filter, body-anchored control box, hand features |
| `app/src/main/java/app/aircontrol/vision/` | MediaPipe pipeline (GPU→CPU fallback, one frame in flight) |
| `app/src/main/java/app/aircontrol/source/` | CameraX, MJPEG and RTSP frame sources |
| `app/src/main/java/app/aircontrol/service/` | Foreground tracking service, accessibility service, gesture dispatcher |
| `app/src/main/java/app/aircontrol/overlay/` | Glow border / pointer overlay (`TYPE_ACCESSIBILITY_OVERLAY`) |
| `app/src/main/java/app/aircontrol/ui/` | Compose app (Control, Camera, Settings, Help) |
| `app/src/test/` | Engine tests with synthetic hand landmarks |

Design notes:
- The pointer follows the index fingertip. Wherever the hand changes shape (pinch closing, a sign
  forming, a push), the pointer is frozen at its position from just before, so actions land where
  aimed.
- Push-to-click watches ln(hand size): a press starts when it grows faster than 0.5/s and clicks
  once the hand is ~10 % bigger, with a peak rate ≥ 0.8/s and little sideways motion.
- Swipe mode is armed by a still, flat hand whose palm normal (from MediaPipe world landmarks)
  points sideways (left/right strokes) or up/down (up/down strokes).
- Pinch uses hysteresis (closes below 0.30 × palm size, opens above 0.45) and rejects fists.
- In swipe mode a stroke needs ≥ 0.45 shoulder widths in 220 ms along the armed axis. After a
  swipe, the opposite direction is blocked for 0.9 s so the return stroke is ignored.
- Only one frame is analysed at a time, so latency can't build up on slower devices. The glow is
  pre-rendered once and drawn at 30 fps to leave the GPU to the hand model.

## Privacy
Frames are analysed in memory on the device and never stored or sent anywhere. The models ship
inside the APK. The accessibility service only injects gestures and draws the overlay; it is
configured without permission to read screen content.

## License
MIT, see [LICENSE](LICENSE). The hand and pose models are Google's MediaPipe models (Apache 2.0),
downloaded at build time by `scripts/fetch-models.sh`.
