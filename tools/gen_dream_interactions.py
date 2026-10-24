#!/usr/bin/env python3
"""Generate the app-side interaction-anchor sidecar for the dream bedroom.

Reads the asset package's interactions.json (authoritative contact geometry,
GLB coordinates) and projects it into the compact schema the demo app parses
(`scene/<name>.interactions.json`):

- coordinates converted to SDK world meters (gltfPosition + [0,-1,-4]);
- sit/lie anchors get their approach anchor (walk target), sitting VRMA clips
  and the hips lift; stand anchors keep their yaw for turn-in-place arrival;
- look targets (glTF) converted to SDK world for the gaze hint.

The generated file is committed into app/src/main/assets/scene/ — re-run this
script only when the asset package's interactions.json changes.
"""

import json
import math
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "assets", "dream_bedroom", "interactions.json")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "scene", "dream_bedroom.interactions.json")

SDK_OFFSET = (0.0, -1.0, -4.0)

# sit loop clip per furniture vibe (transition / stand-up are shared)
SIT_IN = "animations/06_坐姿/Standing To Sitting Transition.vrma"
STAND_UP = "animations/06_坐姿/Standing Up From Seated Position.vrma"
SIT_LOOP = {
    "BED_SIT": "animations/06_坐姿/Sit With Fidgeting Feet.vrma",
    "DESK_SEAT": "animations/06_坐姿/Seated Idle With Hands On A Table.vrma",
    "READING_SEAT": "animations/06_坐姿/Elbows On Legs Leaning Forward.vrma",
}

# anchors that are pure walk targets (walk + turn, no contact pose)
STAND_KINDS = {"stand", "approach", "waypoint"}


def sdk(p):
    return [round(p[0] + SDK_OFFSET[0], 6), round(p[1] + SDK_OFFSET[1], 6), round(p[2] + SDK_OFFSET[2], 6)]


def main():
    with open(SRC, "r", encoding="utf-8") as f:
        src = json.load(f)

    by_id = {a["id"]: a for a in src["anchors"]}
    out_anchors = []
    for a in src["anchors"]:
        aid = a["id"]
        pos = sdk(a["gltfPosition"])
        rec = {
            "id": aid.lower(),
            "label": a["label"],
            "labelEn": aid.lower().replace("_", " "),
            "kind": a["kind"],
            "x": pos[0],
            "y": pos[1],
            "z": pos[2],
            "yawDeg": a["yawDeg"],
        }
        kind = a["kind"]
        if kind in ("sit", "lie"):
            # approach = the sibling *_APPROACH stand point (walk target first)
            base = aid
            for suffix in ("_SIT", "_SEAT", "_LIE"):
                if base.endswith(suffix):
                    base = base[: -len(suffix)]
                    break
            rec["approach"] = (base + "_APPROACH").lower()
            assert rec["approach"] in [x["id"].lower() for x in src["anchors"]], rec["approach"]
            rec["hipsLift"] = 0.09
            if kind == "sit":
                rec["sitInClip"] = SIT_IN
                rec["sitLoopClip"] = SIT_LOOP[aid]
                rec["standUpClip"] = STAND_UP
        if aid in ("BED_HEAD", "GAME_PICKUP"):
            # calibration / hand-IK references, not actionable positions
            continue
        look = a.get("gltfLookTarget")
        if look:
            rec["look"] = sdk(look)
        out_anchors.append(rec)

    doc = {
        "schemaVersion": 1,
        "scene": "dream_bedroom.glb",
        "title": src["title"],
        "anchorCount": len(out_anchors),
        "anchors": out_anchors,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"wrote {OUT}: {len(out_anchors)} anchors")
    for a in out_anchors:
        print(f"  {a['id']:20s} {a['kind']:9s} approach={a.get('approach', '-'):14s} yaw={a['yawDeg']}")


if __name__ == "__main__":
    main()
