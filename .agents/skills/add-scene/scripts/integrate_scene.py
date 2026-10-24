#!/usr/bin/env python3
"""场景资产包一键接入 app（assets/<场景>/ → app/src/main/assets/scene/）。

自动化（照 dream_bedroom / dream_bedroom_2 两次接入固化）：
- 五件套格式校验：meta / interactions / anchors / cutaway / GLB；
- 剖切组与常显节点名对拍 GLB 实际节点名（防手写错字到真机才暴露）；
- 拷 glb / cutaway / anchors 原样三件 + meta 补 spawn 字段
  （SPAWN 锚点 gltf 坐标 + meta 偏移，yawDeg=0 面向相机——911c28f 约定）；
- 调 tools/gen_dream_interactions.py 重投影出 <场景>.interactions.json；
- 回归守卫：重投影后旧场景的 interactions.json 必须字节不变
  （git diff 检查，防止误改 gen 脚本冲掉已验收场景）。

不替你做两件事（需要判断，报告精确修改指令后停，改完重跑本脚本）：
- gen_dream_interactions.py 的 SCENES / SIT_LOOP / SKIPPED_ANCHORS 增补；
- 新坐点（sit）循环剪辑的选择——素材库在
  app/src/main/assets/animations/06_坐姿/，按家具气质挑（床=摆腿闲坐、
  书桌=手放桌面、窗边长凳=Bench Swinging Her Legs 已有先例）。

用法（仓库根目录）：
    python3 .agents/skills/add-scene/scripts/integrate_scene.py <场景名> [--check-only]

--check-only 只校验不写入（已接入场景的幂等体检也用它）。
退出码：0=全部就绪；1=校验失败；2=已拷贝但投影配置有缺口（按提示补 gen 脚本后重跑）。
"""

import argparse
import importlib.util
import json
import os
import shutil
import struct
import subprocess
import sys

# 本文件在 <repo>/.agents/skills/add-scene/scripts/ 下,五层 dirname 回仓库根
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))))
APP_SCENE = os.path.join(ROOT, "app", "src", "main", "assets", "scene")
GEN_SCRIPT = os.path.join(ROOT, "tools", "gen_dream_interactions.py")
ANIM_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "animations", "06_坐姿")

# app 解析器认识的 kind；sit/lie 走接触定姿，其余三类纯走位。
KNOWN_KINDS = {"stand", "approach", "waypoint", "sit", "lie"}

errors = []
notes = []


def fail(msg):
    errors.append(msg)


def note(msg):
    notes.append(msg)


def load_json(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def glb_node_names(path):
    """GLB 二进制头 12 字节 + 首 chunk（JSON）→ 全部节点名集合。"""
    with open(path, "rb") as f:
        magic, _version, _length = struct.unpack("<III", f.read(12))
        if magic != 0x46546C67:  # 'glTF'
            raise ValueError(f"{path} 不是 GLB（magic {magic:#x}）")
        chunk_len, chunk_type = struct.unpack("<II", f.read(8))
        if chunk_type != 0x4E4F534A:  # 'JSON'
            raise ValueError(f"{path} 首 chunk 不是 JSON")
        doc = json.loads(f.read(chunk_len))
    return {n["name"] for n in doc.get("nodes", []) if n.get("name")}


def approach_of(anchor_id):
    """sit/lie 锚点的接近点推导（与 gen_dream_interactions.py 同规则）。"""
    base = anchor_id
    for suffix in ("_SIT", "_SEAT", "_LIE"):
        if base.endswith(suffix):
            base = base[: -len(suffix)]
            break
    return (base + "_APPROACH").lower()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("scene", help="场景名（assets/ 下的目录名，如 dream_bedroom_3）")
    ap.add_argument("--check-only", action="store_true", help="只校验不写入")
    args = ap.parse_args()
    scene = args.scene

    pkg = os.path.join(ROOT, "assets", scene)
    print(f"=== 接入场景 {scene} ===\n包目录: {pkg}")

    # ── 1. 五件套存在性 ─────────────────────────────────────────────
    files = {
        "glb": os.path.join(pkg, f"{scene}.glb"),
        "meta": os.path.join(pkg, f"{scene}.meta.json"),
        "cutaway": os.path.join(pkg, f"{scene}.cutaway.json"),
        "anchors": os.path.join(pkg, f"{scene}.anchors.json"),
        "interactions": os.path.join(pkg, "interactions.json"),
    }
    for label, path in files.items():
        if not os.path.isfile(path):
            fail(f"缺 {label}: {path}")
    if errors:
        print("\n".join(f"✗ {e}" for e in errors))
        sys.exit(1)

    # ── 2. meta：偏移 + 可走范围 ────────────────────────────────────
    meta = load_json(files["meta"])
    off = (meta.get("offsetX"), meta.get("offsetY"), meta.get("offsetZ"))
    if not all(isinstance(v, (int, float)) for v in off):
        fail("meta.json 缺 offsetX/offsetY/offsetZ（数字）")
    if "bounds" not in meta:
        note("meta.json 无 bounds——walk_to 将用解析器默认 ±10 兜底")

    # ── 3. interactions：authoring 文档（GLB 坐标）─────────────────
    inter = load_json(files["interactions"])
    anchors = inter.get("anchors", [])
    ids = [a["id"] for a in anchors]
    ids_lower = {i.lower() for i in ids}
    spawn_anchor = next((a for a in anchors if a["id"].upper() == "SPAWN"), None)
    if spawn_anchor is None:
        note("无 SPAWN 锚点——meta 不写 spawn，角色落世界原点（闭室场景会悬空/穿墙，通常需要）")

    # kind 覆盖：不认识的 kind 在 app 里当纯走位点（不就位），多半是校准参考——
    # 属于 gen 脚本 SKIPPED_ANCHORS 的候选。
    unknown = sorted({a["kind"] for a in anchors} - KNOWN_KINDS)
    sit_ids = [a["id"] for a in anchors if a["kind"] == "sit"]
    for a in anchors:
        if a["kind"] in ("sit", "lie") and approach_of(a["id"]) not in ids_lower:
            fail(f"锚点 {a['id']} 的接近点 {approach_of(a['id'])} 不在锚点表内——就位链会死路")

    # sdkPlacement 与 meta 偏移一致性（文档坐标 vs 落地偏移）
    sdkp = inter.get("sdkPlacement", {})
    if "offsetX" in sdkp:
        doc_off = (sdkp.get("offsetX"), sdkp.get("offsetY"), sdkp.get("offsetZ"))
        if doc_off != off:
            fail(f"interactions.sdkPlacement 偏移 {doc_off} ≠ meta 偏移 {off}——两边文档打架")

    # ── 4. anchors（行走导航图）─────────────────────────────────────
    nav = load_json(files["anchors"]).get("anchors", [])
    nav_names = {a["name"].lower() for a in nav if a.get("name")}
    for a in nav:
        for k in ("name", "x", "z"):
            if k not in a:
                fail(f"导航锚点缺字段 {k}: {a}")
    dangling = sorted(
        {l.lower() for a in nav for l in a.get("links", [])} - nav_names
    )
    if dangling:
        note(f"导航图 links 指向不存在的锚点（路由层会丢弃）: {dangling}")

    # ── 5. cutaway 节点名对拍 GLB ───────────────────────────────────
    cut = load_json(files["cutaway"])
    groups = cut.get("groups", [])
    if not groups:
        fail("cutaway.json 无 groups——防遮挡剖切不会生效")
    try:
        glb_names = glb_node_names(files["glb"])
    except Exception as e:
        fail(str(e))
        glb_names = set()
    referenced = {n for g in groups for n in [g.get("node", "")] + g.get("renderableNodes", [])}
    referenced |= set(cut.get("alwaysVisibleNodes", []))
    missing_nodes = sorted(n for n in referenced if n and n not in glb_names)
    if missing_nodes:
        fail(f"cutaway 引用的节点在 GLB 里不存在（getFirstEntityByName 会落空）: {missing_nodes}")
    # 遮挡大背景板禁入剖切候选的教训（坑③）：报告罩住房间量级的组，人工判断
    for g in groups:
        b = g.get("gltfBounds") or {}
        try:
            dx = float(b["max"][0]) - float(b["min"][0])
            dz = float(b["max"][2]) - float(b["min"][2])
            if dx > 12 or dz > 12:
                note(f"剖切组 {g.get('node')} 的 AABB 量级 {dx:.1f}×{dz:.1f}m——若为外景/背景板须移出 groups（坑③）")
        except (KeyError, TypeError, ValueError):
            pass

    # ── 6. spawn 计算与 bounds 检查 ─────────────────────────────────
    spawn_out = None
    if spawn_anchor is not None:
        p = spawn_anchor["gltfPosition"]
        spawn_out = {
            "x": round(p[0] + off[0], 4),
            "y": round(p[1] + off[1], 4),
            "z": round(p[2] + off[2], 4),
            "yawDeg": 0,  # SDK 相机在 +Z 侧，yaw 0=面向相机（911c28f 约定）
        }
        b = meta.get("bounds")
        if b and not (b["minX"] <= spawn_out["x"] <= b["maxX"] and b["minZ"] <= spawn_out["z"] <= b["maxZ"]):
            fail(f"spawn {spawn_out} 落在可走 bounds 外 {b}")

    # ── 7. gen_dream_interactions.py 投影配置覆盖检查 ────────────────
    spec = importlib.util.spec_from_file_location("gen_dream_interactions", GEN_SCRIPT)
    gen = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(gen)
    gaps = []
    if scene not in gen.SCENES:
        gaps.append(f'SCENES 加一行:    "{scene}": ("{scene}", "{scene}.glb"),')
    for sid in sit_ids:
        if sid not in gen.SIT_LOOP:
            gaps.append(f"SIT_LOOP 加:      \"{sid}\": \"animations/06_坐姿/<挑一个>.vrma\",")
    for uid in unknown:
        # 不认识的 kind 若非跳过点，app 会把它当纯走位点（多半不符合资产语义）
        anchor_ids_of_kind = [a["id"] for a in anchors if a["kind"] == uid]
        if any(i not in gen.SKIPPED_ANCHORS for i in anchor_ids_of_kind):
            gaps.append(f"SKIPPED_ANCHORS 考虑加: {sorted(anchor_ids_of_kind)}（kind={uid}，校准/抓握参考，非可就位点）")

    # ── 报告 ────────────────────────────────────────────────────────
    print(f"\n锚点: {len(anchors)} 个（sit={len(sit_ids)} lie={sum(1 for a in anchors if a['kind']=='lie')} "
          f"导航图={len(nav)} 剖切组={len(groups)} GLB节点={len(glb_names)}）")
    if spawn_out:
        print(f"spawn（将写入 meta）: {spawn_out}")
    for n in notes:
        print(f"⚠ {n}")
    if errors:
        print("\n".join(f"✗ {e}" for e in errors))
        sys.exit(1)
    print("✓ 包校验全过")

    if args.check_only:
        print("✓ --check-only：不写入。")
        return

    # ── 8. 拷贝三件 + meta 补 spawn ─────────────────────────────────
    os.makedirs(APP_SCENE, exist_ok=True)
    for label in ("glb", "cutaway", "anchors"):
        shutil.copy2(files[label], os.path.join(APP_SCENE, os.path.basename(files[label])))
    print(f"已拷贝 glb/cutaway/anchors → {APP_SCENE}")

    app_meta = os.path.join(APP_SCENE, f"{scene}.meta.json")
    if os.path.isfile(app_meta):
        cur = load_json(app_meta)
        if "spawn" in cur:
            print(f"meta 已有 spawn（保留现值）: {cur['spawn']}")
        elif spawn_out:
            cur["spawn"] = spawn_out
            _dump(cur, app_meta)
            print(f"meta 补写 spawn: {spawn_out}")
    elif spawn_out:
        out = dict(meta)
        out["spawn"] = spawn_out
        _dump(out, app_meta)
        print(f"meta 生成（含 spawn）: {app_meta}")

    # ── 9. 投影 interactions ────────────────────────────────────────
    if gaps:
        print("\n✗ gen_dream_interactions.py 配置有缺口，interactions.json 尚未生成：")
        for g in gaps:
            print(f"  - {g}")
        print(f"  候选坐姿剪辑: {sorted(os.listdir(ANIM_DIR))}")
        print("改完 gen 脚本后重跑本命令即可收尾（拷贝/ meta 不会重复破坏，幂等）。")
        sys.exit(2)

    r = subprocess.run([sys.executable, GEN_SCRIPT], capture_output=True, text=True)
    print(r.stdout.strip())
    if r.returncode != 0:
        print(r.stderr)
        fail("gen_dream_interactions.py 运行失败")
        sys.exit(1)

    # ── 10. 回归守卫：旧场景投影必须字节不变 ────────────────────────
    diff = subprocess.run(
        ["git", "diff", "--name-only", "--", "app/src/main/assets/scene/"],
        capture_output=True, text=True, cwd=ROOT,
    ).stdout.split()
    drifted = [d for d in diff if d.endswith(".interactions.json") and scene not in d]
    if drifted:
        fail(f"重投影改变了其他场景的 interactions.json（gen 脚本被误改或投影 JSON 曾被手改）: {drifted}")
        sys.exit(1)

    # ── 11. 落地后校验：approach ⊆ 导航图 ──────────────────────────
    doc = load_json(os.path.join(APP_SCENE, f"{scene}.interactions.json"))
    approaches = {a["approach"] for a in doc["anchors"] if a.get("approach")}
    miss = sorted(approaches - nav_names)
    if miss:
        fail(f"交互表的 approach 点不在导航图内（走位会找不到路由）: {miss}")
        sys.exit(1)
    print(f"✓ {scene}.interactions.json {doc['anchorCount']} 锚点，approach 全在导航图内")

    print(f"""
✓ 接入完成。Kotlin 零改动（场景面板自动发现，剖切/导航/交互/出生点按场景名解析）。
真机验证清单:
  1. ./gradlew :app:assembleDebug 安装后,场景面板应出现 {scene}.glb
  2. 切过去: 开局角色应面向相机(spawn yawDeg=0),落在 SPAWN 点不悬空
  3. ai_cmd walk_to <导航点名> / LLM move_to 走位
  4. ai_cmd interact <坐点id> / LLM interact_with: 坐→循环剪辑→stand_up 起身
  5. 转动相机: 相机拉到墙外时对应剖切组隐藏(occlusion state 查看组状态)
  6. 测帧率: ai_cmd state 看 fps 字段
默认场景仍按 MainActivity DEFAULT_SCENE;要换默认改 app/src/main/java/com/neethu/aiavatar_sdk/MainActivity.kt 的常量。""")


def _dump(obj, path):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
        f.write("\n")


if __name__ == "__main__":
    main()
