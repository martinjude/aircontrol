#!/usr/bin/env python3
"""
Turns gesture videos into landmark clips for the JVM replay tests.

    scripts/.venv/bin/python -I scripts/extract-clips.py [--device pixel8] [--fps 15] [--mirrored auto|yes|no] [--force]

Reads   testdata/videos/<device>/*.mp4|mov|m4v|webm
Writes  app/src/test/resources/clips/<device>/<name>.jsonl
        (and app/src/test/resources/clips/<device>@<fps>/<name>.jsonl with --fps: a decimated copy
        that simulates slower live tracking)

It runs the same MediaPipe models as the app (app/src/main/assets/*.task, fetched by
scripts/fetch-models.sh) in VIDEO mode, on frames scaled down like the app's 640x480 analysis
stream, with pose and face on every 3rd frame like the app. Coordinates are written in the app's
"user view" (mirrored like the front camera preview), so the Kotlin side can replay them as is.

Mirroring: phone camera apps may or may not mirror front-camera videos. The clip 00_mirror_check
(right hand raised beside the right shoulder) decides it per device; --mirrored overrides.

Line format (JSON Lines): a header {"clip", "device", "fps", "flipped"} then one object per frame:
  t (ms), a (width/height), h: [{l: 63 floats, w: 63 floats, g: gesture, s: score, side: L|R|?}],
  p: {l: 99 floats, v: 33 floats} (only on pose frames), f: {m: 16 floats, b: {blendshape: score}}
  (only on face frames).
"""
import argparse
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
VIDEOS = os.path.join(ROOT, "testdata", "videos")
OUT = os.path.join(ROOT, "app", "src", "test", "resources", "clips")
EXTS = (".mp4", ".mov", ".m4v", ".webm", ".mkv")
ANALYSIS_LONG_SIDE = 640
BLENDSHAPES = [
    "eyeLookInLeft", "eyeLookOutLeft", "eyeLookUpLeft", "eyeLookDownLeft",
    "eyeLookInRight", "eyeLookOutRight", "eyeLookUpRight", "eyeLookDownRight",
]

try:
    import cv2
    import mediapipe as mp
    from mediapipe.tasks import python as mpt
    from mediapipe.tasks.python import vision
except ImportError:
    sys.exit("Missing packages. Set up once with:\n"
             "  /usr/bin/python3 -m venv scripts/.venv && scripts/.venv/bin/pip install mediapipe opencv-python-headless")


def models():
    base = lambda name: mpt.BaseOptions(model_asset_path=os.path.join(ASSETS, name))
    for name in ("gesture_recognizer.task", "pose_landmarker_lite.task", "face_landmarker.task"):
        if not os.path.isfile(os.path.join(ASSETS, name)):
            sys.exit(f"Missing model {name}: run scripts/fetch-models.sh")
    hands = vision.GestureRecognizer.create_from_options(vision.GestureRecognizerOptions(
        base_options=base("gesture_recognizer.task"), running_mode=vision.RunningMode.VIDEO, num_hands=2,
        min_hand_detection_confidence=0.5, min_hand_presence_confidence=0.5, min_tracking_confidence=0.5))
    pose = vision.PoseLandmarker.create_from_options(vision.PoseLandmarkerOptions(
        base_options=base("pose_landmarker_lite.task"), running_mode=vision.RunningMode.VIDEO, num_poses=1,
        min_pose_detection_confidence=0.5, min_pose_presence_confidence=0.5, min_tracking_confidence=0.5))
    face = vision.FaceLandmarker.create_from_options(vision.FaceLandmarkerOptions(
        base_options=base("face_landmarker.task"), running_mode=vision.RunningMode.VIDEO, num_faces=1,
        output_face_blendshapes=True, output_facial_transformation_matrixes=True))
    return hands, pose, face


def frames(path):
    cap = cv2.VideoCapture(path)
    if not cap.isOpened():
        raise RuntimeError(f"cannot open {path}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    i = 0
    last_ts = -1
    while True:
        ok, bgr = cap.read()
        if not ok:
            break
        h, w = bgr.shape[:2]
        k = ANALYSIS_LONG_SIDE / max(h, w)
        if k < 1:
            bgr = cv2.resize(bgr, (round(w * k), round(h * k)), interpolation=cv2.INTER_AREA)
        pos = cap.get(cv2.CAP_PROP_POS_MSEC)
        ts = int(round(pos)) if pos and pos > 0 else int(round(i * 1000.0 / fps))
        ts = max(ts, last_ts + 1)
        last_ts = ts
        yield i, ts, fps, cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
        i += 1
    cap.release()


def r4(v):
    return round(float(v), 4)


def extract(path, flip):
    """Returns (fps, [frame dicts]) with coordinates in the app's user view."""
    hands_m, pose_m, face_m = models()
    out = []
    fps = 30.0
    try:
        for i, ts, fps, rgb in frames(path):
            h, w = rgb.shape[:2]
            img = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
            fx = (lambda x: 1.0 - x) if flip else (lambda x: x)
            fr = {"t": ts, "a": r4(w / h), "h": []}
            res = hands_m.recognize_for_video(img, ts)
            for j, lms in enumerate(res.hand_landmarks):
                g = res.gestures[j][0] if j < len(res.gestures) and res.gestures[j] else None
                label = res.handedness[j][0].category_name if j < len(res.handedness) and res.handedness[j] else ""
                # MediaPipe assumes a mirrored image for its label: an unmirrored frame (flip) has it inverted,
                # exactly like the app's VisionPipeline.
                if flip:
                    side = {"Left": "R", "Right": "L"}.get(label, "?")
                else:
                    side = {"Left": "L", "Right": "R"}.get(label, "?")
                world = res.hand_world_landmarks[j] if j < len(res.hand_world_landmarks) else None
                fr["h"].append({
                    "l": [c for p in lms for c in (r4(fx(p.x)), r4(p.y), r4(p.z))],
                    "w": [c for p in world for c in (r4(-p.x if flip else p.x), r4(p.y), r4(p.z))] if world else None,
                    "g": (g.category_name or "None") if g else "None",
                    "s": r4(g.score) if g else 0.0,
                    "side": side,
                })
            if i % 3 == 0:
                pr = pose_m.detect_for_video(img, ts)
                if pr.pose_landmarks:
                    pl = pr.pose_landmarks[0]
                    fr["p"] = {
                        "l": [c for p in pl for c in (r4(fx(p.x)), r4(p.y), r4(p.z))],
                        "v": [r4(p.visibility or 0.0) for p in pl],
                    }
            if i % 3 == 1:
                fres = face_m.detect_for_video(img, ts)
                if fres.facial_transformation_matrixes:
                    m = fres.facial_transformation_matrixes[0]
                    blend = {c.category_name: r4(c.score) for c in fres.face_blendshapes[0]} if fres.face_blendshapes else {}
                    fr["f"] = {
                        "m": [r4(v) for v in m.flatten().tolist()],
                        "b": {k: blend.get(k, 0.0) for k in BLENDSHAPES},
                    }
            out.append(fr)
    finally:
        hands_m.close(); pose_m.close(); face_m.close()
    return fps, out


def detect_flip(path):
    """00_mirror_check: right hand raised beside the right shoulder. In a raw (unmirrored) camera
    image the user's right is on the image's left, i.e. left of the nose."""
    fps, fr = extract(path, flip=False)
    votes = []
    nose_x = None
    for f in fr:
        if "p" in f:
            nose_x = f["p"]["l"][0]
        ref = nose_x if nose_x is not None else 0.5
        for hnd in f["h"]:
            wrist_x, wrist_y = hnd["l"][0], hnd["l"][1]
            if wrist_y < 0.75:  # raised
                votes.append(wrist_x < ref)
    if not votes:
        return None
    raw = sum(votes) > len(votes) / 2
    return raw  # raw camera image -> flip it like the app does


def write(path, header, frames_):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(json.dumps(header, separators=(",", ":")) + "\n")
        for fr in frames_:
            f.write(json.dumps(fr, separators=(",", ":")) + "\n")


def decimate(frames_, fps):
    step = 1000.0 / fps
    out, next_t = [], None
    for fr in frames_:
        if next_t is None or fr["t"] >= next_t - 1:
            out.append(fr)
            next_t = fr["t"] + step
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--device", help="only this device folder")
    ap.add_argument("--fps", type=float, help="also write a decimated copy at this frame rate")
    ap.add_argument("--mirrored", choices=["auto", "yes", "no"], default="auto",
                    help="whether the videos are already mirrored (selfie view); auto = use 00_mirror_check")
    ap.add_argument("--force", action="store_true", help="re-extract clips that are already up to date")
    ap.add_argument("--videos", default=VIDEOS, help=argparse.SUPPRESS)
    ap.add_argument("--out", default=OUT, help=argparse.SUPPRESS)
    args = ap.parse_args()
    videos, out = args.videos, args.out

    if not os.path.isdir(videos):
        sys.exit(f"No videos: put them in {os.path.relpath(videos, ROOT)}/<device>/ (see testdata/README.md)")
    devices = [d for d in sorted(os.listdir(videos)) if os.path.isdir(os.path.join(videos, d))]
    if args.device:
        devices = [d for d in devices if d == args.device]
    for dev in devices:
        vids = sorted(v for v in os.listdir(os.path.join(videos, dev)) if v.lower().endswith(EXTS))
        if not vids:
            continue
        if args.mirrored == "auto":
            check = next((v for v in vids if v.startswith("00_mirror_check")), None)
            flip = detect_flip(os.path.join(videos, dev, check)) if check else None
            if flip is None:
                print(f"[{dev}] no usable 00_mirror_check: assuming raw (unmirrored) videos; pass --mirrored to override")
                flip = True
            else:
                print(f"[{dev}] videos are {'raw (unmirrored)' if flip else 'already mirrored'}")
        else:
            flip = args.mirrored == "no"
        for v in vids:
            name = os.path.splitext(v)[0]
            if name.startswith("00_mirror_check"):
                continue
            src = os.path.join(videos, dev, v)
            dst = os.path.join(out, dev, name + ".jsonl")
            if not args.force and os.path.exists(dst) and os.path.getmtime(dst) >= os.path.getmtime(src):
                print(f"[{dev}] ok   {name}")
                continue
            print(f"[{dev}] get  {name}", flush=True)
            fps, fr = extract(src, flip)
            header = {"clip": name, "device": dev, "fps": round(fps, 2), "flipped": flip}
            write(dst, header, fr)
            if args.fps:
                dec = decimate(fr, args.fps)
                write(os.path.join(out, f"{dev}@{int(args.fps)}", name + ".jsonl"), dict(header, fps=args.fps), dec)
            hands = sum(1 for f in fr if f["h"])
            print(f"[{dev}]      {len(fr)} frames, hand in {hands}, {fps:.0f} fps")


if __name__ == "__main__":
    main()
