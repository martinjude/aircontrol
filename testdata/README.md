# Gesture test videos

Short videos of real gestures, turned into landmark clips that the JVM tests replay through the
gesture engine (`ClipReplayTest`). This turns "clicks misfire" into a regression test.

## 1. Record
Put the videos in `testdata/videos/<device>/`, for example `testdata/videos/pixel8/` and
`testdata/videos/tab/`. The videos stay out of git; only the extracted clips are committed.

- Put the device on its stand, exactly where you use it, and record with the **front camera** at 30 fps (1080p is fine).
- Stand about 1 m away unless the name says near (~0.5 m) or far (~1.8 m), with head and shoulders in view.
- Start each clip with your hand down for about 1 s, and leave ~2 s between repetitions.
- **The file name is the test.** The part after `__` lists what must happen. `click3` means exactly
  3 thumb–index taps; `tap` is a quick air push; `drag` and `long` are holds (thumb–index or air
  push) that move or stay still; `left`, `right`, `up`, `down` are swipes; `bind` and `arm`/`disarm` are also checked when listed. Any click, tap, touch or swipe
  that is *not* listed must not happen, so `neg_*` clips must produce no actions at all.
- Record `00_mirror_check` first on each device. The script uses it to tell whether the camera app
  mirrored the video.

### Must-have first
| File name | What to do |
|---|---|
| `00_mirror_check` | Raise your RIGHT hand beside your right shoulder, 3 s |
| `click_center__click3` | Cursor at the screen centre, touch thumb and index quickly 3× |
| `click_corners__click4` | Click near each corner: top-left, top-right, bottom-right, bottom-left |
| `neg_browse_30s` | Move the cursor naturally as if browsing, no clicks |
| `neg_fidget_20s` | Hand up, relaxed, natural small finger fidgets |
| `neg_pose_changes` | Briefly show ✊ 👍 👎 ✌️ 🖐 🤙 3 4 (no 🤟) |
| `neg_pose_to_pointing` | Show ✊, ✌️ and 👎 one after the other, each time going straight back to pointing |
| `neg_raise_lower_x5` | Bring your hand into view and drop it, 5× |
| `hold_drag_vertical__drag2` | Thumb and index together, move up, release; 2× |
| `push_tap_center__tap3` | Push your finger toward the camera ~10 cm and back, 3× |
| `swipe_left__left3` | Open palm still, flick left; return slowly; 3× |
| `swipe_right__right3` | Same, to the right |
| `neg_talking_30s` | Talk and gesture naturally, glance away now and then |

### Then
| File name | What to do |
|---|---|
| `click_edges__click4` | Click at mid-top, mid-right, mid-bottom, mid-left |
| `click_fast__click5` | 5 clicks ~0.7 s apart |
| `click_slow__click3` | 3 deliberately slow clicks |
| `click_pointing__click3` | Click with the other fingers folded (pointing hand) |
| `click_openhand__click3` | Click with the other fingers open |
| `click_near__click3` | Same as center, at ~0.5 m |
| `click_far__click3` | Same as center, at ~1.8 m |
| `click_dim__click3` | Same as center, room lights dimmed |
| `neg_corners_travel` | Move to each corner and back, no click |
| `neg_wrist_rotate` | Tilt/rotate the hand in every direction |
| `neg_wrong_angle_clicks` | Turn the palm ~60° away from the camera (edge-on-ish), then tap 3× |
| `neg_slow_close` | Let thumb and index drift together slowly (over ~1 s), 3× |
| `neg_daily_30s` | Reach for a cup, scratch your head, adjust your hair or glasses |
| `neg_fast_cursor` | Fast cursor sweeps with a pointing hand |
| `neg_wave_hello` | Wave at the camera |
| `neg_walk_closer` | Step ~50 cm toward the camera and back |
| `neg_person_behind` | Someone else moves and gestures behind you | -> dont have it
| `hold_drag_horizontal__drag2` | Thumb and index together, move left to right, release; 2× |
| `hold_still__long1` | Thumb and index together, hold still 1.5 s |
| `click_turned__click3` | Click with the palm turned ~20° toward you (should still work) |
| `push_tap_corners__tap4` | Air push at each corner |
| `push_drag__drag2` | Push in, move up, pull back; 2× |
| `push_hold__long1` | Push in and hold 1.5 s |
| `push_far__tap3` | Air push at ~1.8 m |
| `swipe_up__up3`, `swipe_down__down3` | Open-palm flicks up / down |
| `swipe_exit__right2` | Flick so the hand leaves the frame |
| `swipe_mixed__left1_right1_up1_down1` | One of each |
| `arm_disarm__arm1_disarm1` | Hold 🤟 1.5 s, wait 3 s, hold 🤟 again |
| `binds_poses__bind5` | Hold ✊ 👍 👎 ✌️ 🖐 for 1.5 s each |
| `binds_two_hands__bind2` | Both hands ✊, then both hands 🖐 |
| `gaze_calibration` | Look at the screen centre, still, 5 s |
| `gaze_look_away` | Look at the screen 3 s, then left, right, up, down, back to the screen |
| `neg_lookaway_click__click3` | Click 3× while looking away from the screen |

A clip may hold one gesture several times; it only needs the right total count. Air-push clips are
replayed with the floating plane (the in-app 5-point calibration needs the on-screen targets).

## 2. Extract
Set up once:
```sh
/usr/bin/python3 -m venv scripts/.venv
scripts/.venv/bin/pip install mediapipe opencv-python-headless
./scripts/fetch-models.sh
```
Then, after adding videos:
```sh
scripts/.venv/bin/python -I scripts/extract-clips.py            # all devices, only new/changed videos
scripts/.venv/bin/python -I scripts/extract-clips.py --fps 15   # plus a 15 fps copy (slower phones)
```
Clips land in `app/src/test/resources/clips/<device>/`.

## 3. Replay
```sh
./gradlew testDebugUnitTest --tests '*ClipReplayTest*' -Pclips=          # report for all clips
./gradlew testDebugUnitTest --tests '*ClipReplayTest*' -Pclips=click_    # only clips matching "click_"
```
The report (also in `app/build/reports/clips.txt`) has one line per clip with expected → actual
counts. For clips that fail, it lists every action with its time and every rejected click with the
reason. It ends with false actions per minute on the `neg_*` clips and the recall on the others.
