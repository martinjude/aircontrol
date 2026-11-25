# AirControl

Hands-free control of an Android phone or tablet with upper-body, hand and finger gestures, using
the front/back camera or a network (IP) camera. Everything runs on the device.

- **🤟 hold 1 s** turns gesture mode on. While you hold it the screen edge fills up from the bottom;
  when control turns on, light runs up both sides and a glowing border stays while AirControl is in
  control. Hold 🤟 again to turn it off (the glow drains away). Without a hand the glow dims and a
  message counts down to auto-off (20 s by default).
- **The cursor follows the centre of your palm** inside a *hand-safe frame*: the part of the camera
  view where the palm can be while the whole hand (raised fingers included) stays in view. That frame
  spans the screen, so the corners are reachable without the index leaving the camera. It scales with
  your hand size, i.e. your distance (Settings → *Cursor speed* shrinks it further).
- **Click and hold, two ways** (Settings → *Click and hold*, default both):
  - **Finger tap 👌**: touch thumb and index tips quickly → click where the cursor was. Keep them
    together → finger on the screen: move to drag/scroll, stay still for a long press.
  - **Air push 👉**: push your hand toward the screen through an invisible plane parallel to the
    camera. A ring closes in on the cursor as you get close; pushing through touches, pulling
    back releases. While pushed in the cursor moves slowly (0.35×) for precise drags. *Calibration →
    Air plane* measures the plane at 5 screen points so it is fixed to the camera.
  - Every tap or push is scored (palm angle, steady hand, other fingers still, a quick deliberate
    touch, gaze) against the **strictness**; the event log says why one was rejected.
  - **Pause after a pose**: for 600 ms after a bind pose, a fist or 🤟, nothing clicks, pushes or
    swipes, so going back to pointing is safe.
- **Hand angle**: keep the palm facing the screen (*Calibration → Hand angle* records your comfortable
  angle). When it turns away the screen edges and the cursor turn orange, then red, and confidence
  drops; far off, clicks are blocked.
- **Pointer confidence**: a full ring is trusted; a thin arc shows the confidence when it drops (palm
  angle, shaky tracking, frame edge, gaze). A green dot above the ring means your eyes are on the
  screen (after the gaze calibration; *Only click when I look at the screen* can also block clicks).
  The ring tightens as thumb and index close, is dashed grey during the pause after a pose, red with
  a slash when the palm angle blocks taps, and shows an orange arc on the side where the hand reaches
  the edge of what the camera can follow.
- **Swipe = open palm held still, then flicked** left/right/up/down. Distances are in palm sizes, a
  flick that leaves the frame still counts, and the return stroke is ignored.
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
   ./scripts/install.sh                         # installs/updates dist/AirControl-<version>.apk
   ```
   For Wireless debugging use `adb pair <ip>:<pair-port>` then `adb connect <ip>:<port>`.

   `install.sh` also grants AirControl one extra permission (`WRITE_SECURE_SETTINGS`) and switches
   the accessibility service on. With that permission, which stays through updates and is lost only
   on uninstall, AirControl switches its accessibility service back on by itself after every update,
   however the update is installed. Run it once per device; afterwards Option B works too.

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

**Tracking view** (Settings → Look): *Compact* (default) shows, while control is on, a small view of
your hand in a screen corner with the hand-safe frame (an edge lights up when your palm reaches it),
the air-push depth gauge and one status word. *Diagnostics* adds meters with thresholds for pointer
confidence, palm angle, the thumb–index gap and the tap confidence factors, push and flick speed, a
gap-over-time graph and an event log that says why each tap or push was rejected; it also works while control is off. **Camera → Live tracking**
draws the same view inside the app.

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
- **Calibration**: *Calibrate everything* runs hand angle → air plane (5 points) → gaze; each can
  also be run alone or cleared. A card explains each step and counts down before anything is
  measured; the notification has *Stop calibration*.
- **Pointer**: cursor speed (gain of the hand-safe frame), smoothing, hand angle tolerance.
- **Click and hold**: method (finger tap / air push / both), strictness, click length (shorter touch
  = click, longer = hold), pause after a pose, *only click when I look at the screen*.
- **Air push**: drag speed while pushed in; push depth for the uncalibrated floating plane.
- **Swipes**: on/off, flick sensitivity, swipe length.
- **Detection**: auto-off, require a person in view, upper-body tracking, swap left/right, processor.
- **Look**: glow colour; tracking view (off / compact / diagnostics), its opacity (70 % by default) and
  position: drag the handle on the panel's top edge, double-tap it to put the panel back. The panel
  lets taps through and fades while the cursor is over it.
- Every slider shows a reset button when it differs from its default.

## Gesture test clips
Record short videos of real gestures, extract their landmarks once on the Mac, and every engine change
is checked against them (`ClipReplayTest`): see [testdata/README.md](testdata/README.md) for the list
of videos, the naming rules (the file name says what must happen) and the commands.
`extract-clips.py --augment all` also makes degraded copies (dark, overexposed, noisy, blurred, low
resolution, heavy compression) to see how bad conditions affect tracking; they stay out of git.

## Build from source
Requirements (already installed on this Mac): JDK 17 (`brew install openjdk@17`) and the Android
command-line tools (`brew install --cask android-commandlinetools`, SDK at
`/opt/homebrew/share/android-commandlinetools`).

```sh
./scripts/fetch-models.sh       # downloads the MediaPipe models into app/src/main/assets (not in git)
./scripts/build-apk.sh          # downloads models, creates a release key on first run, builds dist/AirControl-<version>.apk
./gradlew testDebugUnitTest     # gesture-engine unit tests + recorded clip replay (JVM, no device needed)
./gradlew assembleDebug         # debug build
```

`keystore/` and `keystore.properties` hold the release signing key. **Keep them.** Updates must be
signed with the same key, otherwise Android refuses to install over the existing app.

## How it works
```
camera / MJPEG / RTSP ──► VisionPipeline (MediaPipe GestureRecognizer + PoseLandmarker + FaceLandmarker, on-device)
        (TrackingService, foreground)         │ hand landmarks + sign + shoulders + gaze, user-view coords
                                              ▼
                                   GestureEngine (pure Kotlin, unit-tested)
                                              │ Armed / Pointer / Tap / TouchDown·Move·Up / Swipe / BindFired
                                              ▼ ControlBus
                       AirControlAccessibilityService ─► dispatchGesture / ActionRunner (global actions, media keys, app launch)
                                              └────────► OverlayController (glow, pointer, pill)
```

| Path | What |
|---|---|
| `app/src/main/java/app/aircontrol/gesture/` | Engine, tap gate (click confidence), push plane, flick detector, attention, hand-safe pointer map, binds, pose classifier, One-Euro filter, hand features |
| `app/src/main/java/app/aircontrol/vision/` | MediaPipe pipeline (GPU→CPU fallback, one frame in flight) |
| `app/src/main/java/app/aircontrol/source/` | CameraX, MJPEG and RTSP frame sources |
| `app/src/main/java/app/aircontrol/service/` | Foreground tracking service, accessibility service, gesture dispatcher |
| `app/src/main/java/app/aircontrol/overlay/` | Overlay (`TYPE_ACCESSIBILITY_OVERLAY`): glow, angle warning shade, pointer states, calibration targets, tracking view (`TrackingRenderer`, shared with the app) |
| `app/src/main/java/app/aircontrol/ui/` | Compose app (Control, Camera, Binds, Settings, Help) |
| `app/src/test/` | Engine tests with synthetic hand landmarks; replay of recorded clips (`resources/clips/`) |
| `scripts/extract-clips.py`, `testdata/` | Gesture videos → landmark clips |

Design notes:
- The cursor is the mean of the wrist and the four knuckles (the palm centre), which does not move when
  the fingers do, so a finger tap never drags the cursor. It is mapped from the hand-safe
  region (margins of 1.7 palm sizes above, 0.7 below, 0.65 at the sides) onto the screen (`PointerMap`).
- Finger tap: thumb–index tip distance over palm size, with hysteresis (touching below 0.30, apart
  above 0.45). At the moment of contact `TapGate` checks hard gates (pause after a pose, palm angle
  beyond tolerance + 25°, gaze gate) and scores factors (angle, steadiness before, isolation of the
  other fingers, closing speed, gaze) into a weighted geometric mean that must reach the strictness
  threshold. A touch released within 300 ms is a click at the pointer position from 80 ms before
  contact; longer or moved beyond the slop it becomes TouchDown/Move/Up.
- Hand angle: palm normal from the world landmarks in a canonical form (z ≥ 0, |x|) so it does not
  depend on the hand or on palm vs back; compared with the calibrated reference.
- Air push: depth = ln(rigid palm-bone scale). Calibrated: hover and plane depths measured at 5 screen
  points, interpolated by inverse-distance weighting at the cursor, proximity = (d − hover) /
  (plane − hover), fixed to the camera. Uncalibrated: a floating plane 0.12 in front of a rest depth
  that follows the hand with a 1.5 s time constant. One-frame jumps are treated as glitches.
- Pointer confidence: weighted geometric mean of the angle score, steadiness (second difference of the
  palm position), frame edge and gaze.
- Swipes: an open palm (4 fingers extended) slower than 0.8 palm sizes/s for 120 ms is "ready"; within
  350 ms of moving it must travel 2 palm sizes along one axis with a peak speed of 6 palm sizes/s. The
  opposite direction is blocked for 0.7 s.
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
