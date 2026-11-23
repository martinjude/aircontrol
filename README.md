# AirControl

Hands-free control of an Android phone or tablet with upper-body, hand and finger gestures, using
the front/back camera or a network (IP) camera. Everything runs on the device.

- **🤟 hold 1 s** turns gesture mode on. A glowing border around the screen shows that AirControl is
  in control. Hold 🤟 again to turn it off; it also turns off after 20 s without a hand.
- **The cursor sits on the centre of your palm.** The camera view maps directly onto the screen: the
  middle of the view is the middle of the screen and the corners of the view are the corners of the
  screen (Settings → *Cursor speed* raises the gain if you can't reach the edges).
- **Click = curl and release your index finger** (bend it and straighten it again, quickly). The click
  fires on the release, where the cursor was.
- **Pinch 🤏 with your hand upright** (fingers up, thumb and index facing the camera): quick pinch = tap ·
  pinch, move, release = drag/scroll · pinch and hold = long press. A horizontal hand does not pinch.
- **Flat hand held 0.3 s** arms swipes ("Swipe armed"): palm sideways = left/right strokes, palm
  down = up/down strokes. Stays armed while the hand stays flat.
- **Binds** (Binds tab): hold a hand pose to trigger an action. One hand or two hands together,
  optionally only the left or right hand. Poses: ✊ 👍 👎 ✌️ 🖐 plus 🤙 (thumb + pinky), 3️⃣ and 4️⃣ fingers.
  Actions: Back, Home, Recents, Notifications, Quick settings, Lock screen, Screenshot, Power menu,
  Play/pause, Next/Previous track, Volume up/down, Mute, and **open any installed app**.
  Defaults: 👎 Back · ✊ hold Home · ✌️ Recents.

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
   adb install -r dist/AirControl-2.1.0.apk
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

**Camera → Live tracking** shows what the model sees: the skeleton, the cursor point (palm centre), the
camera region that spans the screen, meters for pinch and click with their thresholds, an index-reach
graph and an event log. **Settings → Look → Live tracking preview** shows the same in a panel in a screen
corner on top of any app (it hops to the other corner when the cursor goes under it). It also works
while gesture control is off, so you can tune clicks and pinches without touching anything.

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
- **Cursor speed**: camera → screen gain around the centre (1.00× = view edges are screen edges).
  **Smoothing**: jitter vs lag.
- **Click sensitivity**: higher = a smaller finger flick counts; lower it if clicks fire by accident. The
  *click* meter shows the dip of the index-finger reach and the dip that is needed (tick); a rejected
  curl is explained in the event log ("too shallow 0.18/0.30", "too slow", "hand moving").
- **Pinch tilt tolerance**: how far from upright the hand may be to start a pinch.
- **Stroke sensitivity**: how short/slow a stroke can be once swipe mode is armed.
- Every slider shows a *reset* button when it differs from its default.
- **Swap left / right hand**: flips the detected hand if left/right binds fire for the wrong hand.
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
                                              │ Armed / Pointer / Tap / TouchDown·Move·Up / Swipe / BindFired
                                              ▼ ControlBus
                       AirControlAccessibilityService ─► dispatchGesture / ActionRunner (global actions, media keys, app launch)
                                              └────────► OverlayController (glow, pointer, pill)
```

| Path | What |
|---|---|
| `app/src/main/java/app/aircontrol/gesture/` | Engine, click detector, bind model + matcher, pose classifier, One-Euro filter, hand features |
| `app/src/main/java/app/aircontrol/vision/` | MediaPipe pipeline (GPU→CPU fallback, one frame in flight) |
| `app/src/main/java/app/aircontrol/source/` | CameraX, MJPEG and RTSP frame sources |
| `app/src/main/java/app/aircontrol/service/` | Foreground tracking service, accessibility service, gesture dispatcher |
| `app/src/main/java/app/aircontrol/overlay/` | Glow border / pointer overlay (`TYPE_ACCESSIBILITY_OVERLAY`) |
| `app/src/main/java/app/aircontrol/ui/` | Compose app (Control, Camera, Binds, Settings, Help) |
| `app/src/test/` | Engine tests with synthetic hand landmarks |

Design notes:
- The cursor is the mean of the wrist and the four knuckles (the palm centre), which does not move when
  the fingers do, so a finger flick or a pinch never drags the cursor. It is mapped linearly from the
  camera frame to the screen (`PointerMap`).
- Click detection watches the index-finger reach (|wrist→tip| / |wrist→knuckle| from the MediaPipe world
  landmarks, ≈2 straight, ≈1 curled). The finger must have been extended, dip by ≥ 0.30 within 0.45 s
  and regain 60 % of the dip; slow curls (making a fist), shallow ones and curls with a moving hand are
  rejected. The click lands where the cursor was just before the curl started.
- Pinch uses hysteresis (closes below 0.30 × palm size, opens above 0.45), rejects fists, and only starts
  with the hand upright (wrist→knuckle axis within 45° of vertical).
- Swipe mode is armed by a still, flat hand whose palm normal (from MediaPipe world landmarks)
  points sideways (left/right strokes) or up/down (up/down strokes). A stroke needs ≥ 0.45 shoulder
  widths in 220 ms along the armed axis; the opposite direction is blocked for 0.9 s so the return
  stroke is ignored.
- Binds are matched per frame against every visible hand; two-hand binds beat side-specific ones, which
  beat plain ones. The pointer freezes while a hold builds up. 🤙, 3️⃣ and 4️⃣ are classified from finger
  extension flags, the others come from MediaPipe's gesture recognizer.
- Only one frame is analysed at a time, so latency can't build up on slower devices. The glow is
  pre-rendered once and drawn at 30 fps to leave the GPU to the hand model.

## Privacy
Frames are analysed in memory on the device and never stored or sent anywhere. The models ship
inside the APK. The accessibility service only injects gestures and draws the overlay; it is
configured without permission to read screen content.

## License
MIT, see [LICENSE](LICENSE). The hand and pose models are Google's MediaPipe models (Apache 2.0),
downloaded at build time by `scripts/fetch-models.sh`.
