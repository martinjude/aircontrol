# AGENTS.md: AirControl in five minutes

AirControl is a sideloaded Android app (Kotlin, Jetpack Compose, MediaPipe) that lets one person
control **their own** phone or tablet from 0.5–2 m away with hand gestures seen by the camera. Users:
a Pixel 8 and a Galaxy Tab. Everything runs on the device. Version and history: `app/build.gradle.kts`,
`README.md`.

## Commands
```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew testDebugUnitTest                 # all JVM tests (engine + recorded clips), ~10 s
./gradlew compileDebugKotlin                # quick compile check
./scripts/build-apk.sh                      # signed release APK → dist/AirControl-<version>.apk (runs R8)
./gradlew testDebugUnitTest --tests '*ClipReplayTest*' -Pclips=   # replay report for recorded gesture clips
```
Always build the **release** APK before shipping: R8 breaks MediaPipe without the keep rules in
`app/proguard-rules.pro`. Never touch `keystore/` or `keystore.properties`: updates must be signed with
the same key. Don't commit unless asked.

## How data flows
```
Camera / MJPEG / RTSP  (source/)
  → VisionPipeline      (vision/)   MediaPipe hands every frame; pose and face every 3rd frame;
                                    converts to user-view FrameObservation (mirrored like a selfie)
  → GestureEngine       (gesture/)  pure Kotlin, single-threaded, unit-tested; emits ControlEvents
  → ControlBus          (service/)  in-process flows
  → AirControlAccessibilityService  injects taps/drags/swipes (GestureDispatcher), runs bind actions
                                    (ActionRunner), draws the overlay (overlay/)
TrackingService (foreground) owns the camera, the pipeline and the engine thread.
UI (ui/) = Compose app: Control, Camera, Binds, Settings, Help.
```

## Where things live
| Need to change… | File |
|---|---|
| Any gesture threshold or default | `gesture/Model.kt` → `EngineConfig` (each field is documented) |
| Event types the engine emits | `gesture/Model.kt` → `ControlEvent` |
| Engine logic (arming, touch state machine, cooldown, pointer, calibrations) | `gesture/GestureEngine.kt` |
| Is a thumb–index tap / air push a real click? (confidence factors, hard gates) | `gesture/TapGate.kt` |
| Air plane (camera-fixed calibrated plane, floating fallback) | `gesture/PushPlane.kt` |
| Swipes (open palm still, then flick) | `gesture/FlickDetector.kt` |
| Gaze / attention score | `gesture/Attention.kt` |
| Camera → screen mapping (hand-safe region) | `gesture/PointerMap.kt` |
| Hand geometry (palm size, palm normal, finger extension, hand angle) | `gesture/HandFeatures.kt` |
| Bindable poses and binds | `gesture/Binds.kt`, `gesture/HandPose.kt`, `gesture/BindMatcher.kt` |
| User settings ↔ engine config, persistence, migrations | `settings/SettingsRepository.kt` |
| Overlay drawing (pointer, warning shade, pills, calibration targets, tracking view) | `overlay/OverlayView.kt` |
| Camera-view drawing shared by the overlay and the Camera tab | `overlay/TrackingRenderer.kt` |
| Colours, type, radii, icons | `ui/Palette.kt`, `ui/Theme.kt`, `ui/Icons.kt`, `ui/Components.kt` |
| Calibration orchestration and saving results | `service/TrackingService.kt` (`calibrate`, `onCalibrated`) |

## The gestures (current design, v2.3)
- **🤟 held 1 s**: arm/disarm. Disarmed, the engine still analyses the hand for the preview but emits no actions.
- **Cursor** = palm centre, mapped through the *hand-safe region* (room left for the fingers) onto the screen.
- **Finger tap** = thumb and index tips touch (`HandFeatures.pinchRatio` < `tapEnter`). Released within
  `tapMaxMs` → `Tap(click = true)`. Held longer or moved → `TouchDown`/`TouchMove`/`TouchUp` (drag, long press).
- **Air push** = hand pushed through a plane parallel to the camera (`PushPlane`). Same touch machine,
  `TouchSource.PUSH`, `Tap(click = false)`; while pushed in the cursor moves at `pushDragGain`.
- **Swipe** = open palm still, then flicked.
- **Binds** = held poses → system actions/app launch.
- **Pause after a pose** (`poseCooldownMs`): after a bound pose, a fist, 🤟 or a fired bind, taps,
  pushes and swipes are blocked. This prevents clicks while the hand changes shape.
- **Hand angle**: palm normal vs the calibrated reference (`HandFeatures.angleTo`). Beyond tolerance:
  overlay warning shade and lower confidence; beyond tolerance + 25°: taps and pushes blocked.
- **Pointer confidence** (angle, jitter, frame edge, gaze) travels in `ControlEvent.Pointer`.

There is no pinch mode and no index-curl click any more (both were removed in v2.3).

## Conventions
- `GestureEngine` and everything in `gesture/` is plain Kotlin with no Android imports, so it runs
  in JVM tests. Keep it that way.
- Coordinates: observations are in *user view* (x mirrored for a facing camera), normalized 0..1;
  `HandFeatures` works in aspect-corrected space (x and z scaled by width/height). Distances and speeds
  are in **palm sizes** so behaviour does not depend on the distance to the camera. Depth uses `ln(scale)`.
- New behaviour needs a test in `app/src/test/.../GestureEngineTest.kt`, driven by `SyntheticHands`
  (`hand(x, y, shape, pinchRatio=…, s=palm size, yawDeg=…, rollDeg=…)`) and `EngineHarness` (30 fps clock).
  `EngineHarness.arm()` also waits out the pose pause.
- New settings: add the field to `AppSettings`, map it in `applyTo`, add a key and read/write it in
  `SettingsRepository`, and bump `SETTINGS_VERSION` when an old value changes meaning.
- Every engine decision that rejects a user action should emit `ControlEvent.ClickRejected(reason)`.
  The diagnostics log and the clip report show these reasons; they are the main tuning tool.
- UI follows the "unslop" rules: one graphite ramp plus one coral accent (`Palette`), weights 400/500,
  hairline borders, sentence case, no exclamation marks, no trailing periods on labels. Gestures are shown
  as emoji (`Emoji` composable, `Pose.emoji`). Line icons come from `AirIcons`.
- Write user-facing copy in plain English.

## Recorded gesture clips (the regression suite)
The user films gestures on the device (`testdata/videos/<device>/`, gitignored). `scripts/extract-clips.py`
(venv in `scripts/.venv`, mediapipe 1.2.3, run with `python -I`) turns them into landmark clips in
`app/src/test/resources/clips/<device>/*.jsonl`, and `ClipReplayTest` replays them. **The file name is the
expectation** (`click_center__click3`, `neg_*` = no actions at all). See `testdata/README.md`. When the
user reports misfires, ask for clips, replay them and read the reject reasons before changing thresholds.

## Pitfalls already paid for
- MediaPipe LIVE_STREAM queues frames when inference is slow; `VisionPipeline.busy()` keeps one frame in flight.
- R8 needs keep rules for `com.google.mediapipe.**`, `com.google.protobuf.**`, `com.google.common.flogger.**`.
- The overlay glow is a pre-rendered ALPHA_8 mask drawn at 30 fps; stacked blurred strokes every frame starved the GPU.
- `adb shell uiautomator dump` suspends other accessibility services (AirControl appears "not enabled").
- Emulator testing without a hand: serve MediaPipe sample photos as an MJPEG stream and set the network
  camera to `http://10.0.2.2:<port>/video`. Photos can't test motion (taps, pushes, flicks); unit tests cover those.
- Unit tests compile against android.jar's stubbed `org.json`: use `keys()` (not `keySet()`); the real
  library is a `testImplementation`.
- macOS: no `timeout`; `sed -i ''`; only Python 3.9 is installed.
- The v2.0 velocity "jab" push click failed on a real phone; the air push is position-based on purpose.
- The palm normal's sign flips between hands and between palm and back: always use `canonicalNormal` / `angleTo`.
