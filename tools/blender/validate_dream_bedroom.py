#!/usr/bin/env python3
"""Validate the exported bedroom without Blender or third-party packages.

Reads actual GLB POSITION accessors and accumulated node transforms, then checks
interaction coordinates, direction targets, SDK offsets, the waypoint graph and
optional cutaway wall groups.
This geometric check is not a physics simulation or a glTF conformance validator.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parents[2]
EPS = 2e-5
IDENTITY = [[float(i == j) for j in range(4)] for i in range(4)]


def multiply(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def transform(m, p, vector=False):
    return [sum(m[i][j] * p[j] for j in range(3)) + (0 if vector else m[i][3]) for i in range(3)]


def normalized(v):
    n = math.sqrt(sum(x*x for x in v))
    return [x/n for x in v] if n else [0, 0, 0]


def delta(a, b):
    return max(abs(x-y) for x, y in zip(a, b))


def node_matrix(node):
    if "matrix" in node:
        return [[node["matrix"][j*4+i] for j in range(4)] for i in range(4)]
    x, y, z, w = node.get("rotation", [0, 0, 0, 1])
    qnorm = math.sqrt(x*x+y*y+z*z+w*w)
    x, y, z, w = [v/qnorm for v in (x, y, z, w)]
    rotation = [
        [1-2*y*y-2*z*z, 2*x*y-2*z*w, 2*x*z+2*y*w, 0],
        [2*x*y+2*z*w, 1-2*x*x-2*z*z, 2*y*z-2*x*w, 0],
        [2*x*z-2*y*w, 2*y*z+2*x*w, 1-2*x*x-2*y*y, 0],
        [0, 0, 0, 1],
    ]
    for i, scale in enumerate(node.get("scale", [1, 1, 1])):
        for j in range(3):
            rotation[j][i] *= scale
    for i, value in enumerate(node.get("translation", [0, 0, 0])):
        rotation[i][3] = value
    return rotation


class Glb:
    def __init__(self, path):
        self.raw = path.read_bytes()
        magic, version, length = struct.unpack_from("<4sII", self.raw)
        assert magic == b"glTF" and version == 2 and length == len(self.raw), "Invalid GLB header"
        self.binary = None
        offset = 12
        while offset < len(self.raw):
            size, kind = struct.unpack_from("<II", self.raw, offset)
            chunk = self.raw[offset+8:offset+8+size]
            if kind == 0x4E4F534A:
                self.doc = json.loads(chunk)
            elif kind == 0x004E4942:
                self.binary = chunk
            offset += size+8
        assert offset == len(self.raw), "GLB chunk length mismatch"
        self.nodes = self.doc["nodes"]
        self.world = {}
        self.children = {}
        self.names = {}
        self.duplicates = []

        def visit(index, parent):
            assert index not in self.world, "Cycle or multiple node parents"
            n = self.nodes[index]
            self.world[index] = multiply(parent, node_matrix(n))
            self.children[index] = n.get("children", [])
            if "name" in n:
                if n["name"] in self.names:
                    self.duplicates.append(n["name"])
                self.names[n["name"]] = index
            for c in self.children[index]:
                visit(c, self.world[index])

        for index in self.doc["scenes"][self.doc.get("scene", 0)]["nodes"]:
            visit(index, IDENTITY)
        self.position_cache = {}
        self.bounds_cache = {}

    def positions(self, accessor_id):
        if accessor_id in self.position_cache:
            return self.position_cache[accessor_id]
        a = self.doc["accessors"][accessor_id]
        assert a["type"] == "VEC3" and a["componentType"] == 5126, "Expected float VEC3 positions"
        assert "sparse" not in a, "Sparse POSITION requires explicit decoder support"
        view = self.doc["bufferViews"][a["bufferView"]]
        assert view.get("buffer", 0) == 0 and "uri" not in self.doc["buffers"][0], "Expected embedded GLB buffer"
        offset = view.get("byteOffset", 0)+a.get("byteOffset", 0)
        stride = view.get("byteStride", 12)
        result = [struct.unpack_from("<3f", self.binary, offset+i*stride) for i in range(a["count"])]
        self.position_cache[accessor_id] = result
        return result

    def position(self, name):
        return transform(self.world[self.names[name]], [0, 0, 0])

    def forward(self, name):
        return normalized(transform(self.world[self.names[name]], [0, 0, 1], vector=True))

    def descendants(self, index):
        """Include the group itself; mesh ownership is by node, not mesh resource."""
        result, pending = set(), [index]
        while pending:
            current = pending.pop()
            if current in result:
                continue
            result.add(current)
            pending.extend(self.children[current])
        return result

    def bounds(self, name):
        if name in self.bounds_cache:
            return self.bounds_cache[name]
        points = []

        def visit(index):
            node = self.nodes[index]
            if "mesh" in node:
                for primitive in self.doc["meshes"][node["mesh"]]["primitives"]:
                    points.extend(transform(self.world[index], p) for p in self.positions(primitive["attributes"]["POSITION"]))
            for c in self.children[index]:
                visit(c)

        visit(self.names[name])
        result = {"min": [min(p[i] for p in points) for i in range(3)], "max": [max(p[i] for p in points) for i in range(3)]}
        self.bounds_cache[name] = result
        return result


def segment_hits_expanded_aabb(p, q, box, radius):
    """XZ slab intersection, including touching endpoints and boundary."""
    low, high = 0.0, 1.0
    for axis in (0, 2):
        minimum, maximum = box["min"][axis]-radius, box["max"][axis]+radius
        direction = q[axis]-p[axis]
        if abs(direction) < 1e-12:
            if p[axis] < minimum or p[axis] > maximum:
                return False
        else:
            t0, t1 = (minimum-p[axis])/direction, (maximum-p[axis])/direction
            low = max(low, min(t0, t1))
            high = min(high, max(t0, t1))
            if low > high:
                return False
    return True


def validate_cutaway_groups(glb, manifest, expect):
    """Ensure a host can hide each declared group without hiding the floor.

    Mesh data may be instanced by multiple unrelated nodes, so disjointness is
    checked on renderable node instances rather than shared glTF mesh indices.
    """
    rendering = manifest.get("rendering", {})
    if "cutawayGroups" not in rendering:
        return []
    groups = rendering["cutawayGroups"]
    expect(isinstance(groups, list), "rendering.cutawayGroups must be a list", "cutawaySchema")
    if not isinstance(groups, list):
        return []
    required = {"WALL_NORTH", "WALL_SOUTH", "WALL_EAST", "WALL_WEST", "CEILING"}
    names = [g.get("node") for g in groups if isinstance(g, dict)]
    valid_names = [name for name in names if isinstance(name, str)]
    expect(len(valid_names) == len(groups), "Every cutaway group must have a string node name", "cutawaySchema")
    expect(len(valid_names) == len(set(valid_names)), "Duplicate cutaway group nodes", "cutawaySchema")
    expect(set(valid_names) == required, f"Cutaway groups must be the four walls and ceiling: actual={valid_names}", "cutawayGroups")
    expect("FLOOR" in glb.names, "Missing protected FLOOR node", "cutawayFloor")
    expect("FLOOR" in rendering.get("alwaysVisibleNodes", []), "FLOOR must be listed in rendering.alwaysVisibleNodes", "cutawayFloor")
    floor = glb.descendants(glb.names["FLOOR"]) if "FLOOR" in glb.names else set()
    floor_renderables = {i for i in floor if "mesh" in glb.nodes[i]}
    expect(bool(floor_renderables), "FLOOR must contain renderable geometry", "cutawayFloor")
    report, owner = [], {}
    for group in groups:
        if not isinstance(group, dict) or not isinstance(group.get("node"), str):
            continue
        name = group["node"]
        expect(name in glb.names, f"Missing cutaway group node: {name}", "cutawayNodeExists")
        declared = group.get("renderableNodes")
        valid_list = isinstance(declared, list) and all(isinstance(n, str) for n in declared)
        expect(valid_list, f"{name}.renderableNodes must be a list of node names", "cutawaySchema")
        if name not in glb.names or not valid_list:
            continue
        expect(len(declared) == len(set(declared)), f"Duplicate renderable node names in {name}", "cutawaySchema")
        descendants = glb.descendants(glb.names[name])
        renderables = {i for i in descendants if "mesh" in glb.nodes[i]}
        actual = {glb.nodes[i].get("name", f"<unnamed:{i}>") for i in renderables}
        expect(bool(renderables), f"Cutaway group has no renderable descendants: {name}", "cutawayRenderables")
        expect(actual == set(declared), f"Cutaway renderable list mismatch for {name}: missing={sorted(actual-set(declared))}, unexpected={sorted(set(declared)-actual)}", "cutawayRenderables")
        overlap = floor & descendants
        expect(not overlap, f"Cutaway group {name} contains protected FLOOR nodes: {[glb.nodes[i].get('name', i) for i in sorted(overlap)]}", "cutawayFloor")
        for i in sorted(renderables):
            expect(i not in owner, f"Renderable {glb.nodes[i].get('name', i)} belongs to both {owner.get(i)} and {name}", "cutawayDisjointness")
            owner.setdefault(i, name)
        if renderables and "gltfBounds" in group:
            actual_bounds = glb.bounds(name)
            for key in ("min", "max"):
                expect(delta(actual_bounds[key], group["gltfBounds"][key]) <= EPS, f"Cutaway {key} bounds mismatch for {name}: actual={actual_bounds[key]}, declared={group['gltfBounds'][key]}", "cutawayBounds")
        report.append({"node": name, "renderableCount": len(renderables), "renderableNodes": sorted(actual)})
    for alias, parent in rendering.get("legacyAliases", {}).items():
        expect(alias in glb.names, f"Missing cutaway legacy alias: {alias}", "cutawayAliases")
        expect(parent in glb.names, f"Missing cutaway legacy alias target: {parent}", "cutawayAliases")
        if alias in glb.names and parent in glb.names:
            expect(glb.names[alias] in glb.descendants(glb.names[parent]), f"Legacy alias {alias} is not inside {parent}", "cutawayAliases")
    return report


def validate_cutaway_sidecar(folder, manifest, expect):
    rendering = manifest.get("rendering", {})
    if "cutawayGroups" not in rendering:
        return
    path = folder/"dream_bedroom.cutaway.json"
    try:
        sidecar = json.loads(path.read_text())
    except (OSError, ValueError) as error:
        expect(False, f"Cannot read cutaway sidecar: {error}", "cutawaySidecar")
        return
    expect(isinstance(sidecar, dict), "Cutaway sidecar must be an object", "cutawaySidecar")
    if not isinstance(sidecar, dict):
        return
    for key in ("coordinateSystem", "units", "sdkPlacement"):
        expect(sidecar.get(key) == manifest[key], f"Cutaway sidecar {key} differs from interaction manifest", "cutawaySidecar")
    for sidecar_key, manifest_key in (("groups", "cutawayGroups"), ("alwaysVisibleNodes", "alwaysVisibleNodes"), ("visibilityContract", "visibilityContract")):
        expect(sidecar.get(sidecar_key) == rendering.get(manifest_key), f"Cutaway sidecar {sidecar_key} differs from interaction manifest", "cutawaySidecar")


def validate(folder):
    glb_path = folder/"dream_bedroom.glb"
    glb = Glb(glb_path)
    manifest = json.loads((folder/"interactions.json").read_text())
    meta = json.loads((folder/"dream_bedroom.meta.json").read_text())
    walk = json.loads((folder/"dream_bedroom.anchors.json").read_text())["anchors"]
    errors, warnings = [], []
    checks = Counter()

    def expect(ok, message, kind):
        checks[kind] += 1
        if not ok:
            errors.append(message)

    def close(actual, expected, message, kind):
        expect(delta(actual, expected) <= EPS, f"{message}: actual={actual}, expected={expected}", kind)

    theta = math.radians(meta["yawDeg"])
    c, s = math.cos(theta), math.sin(theta)
    offsets = [meta["offsetX"], meta["offsetY"], meta["offsetZ"]]

    def sdk(p):
        return [c*p[0]+s*p[2]+offsets[0], p[1]+offsets[1], -s*p[0]+c*p[2]+offsets[2]]

    expect(not glb.duplicates, f"Duplicate GLB node names: {glb.duplicates}", "uniqueNodeNames")
    cutaway_groups = validate_cutaway_groups(glb, manifest, expect)
    validate_cutaway_sidecar(folder, manifest, expect)
    for key in ("offsetX", "offsetY", "offsetZ", "yawDeg"):
        expect(manifest["sdkPlacement"][key] == meta[key], f"sdkPlacement differs from meta: {key}", "sdkPlacement")
    all_records = manifest["objects"]+manifest["anchors"]
    for record in all_records:
        node = record["node"]
        expect(node in glb.names, f"Missing node: {node}", "nodeExists")
        if node not in glb.names:
            continue
        is_anchor = "id" in record
        key, world_key = ("gltfPosition", "sdkWorldPosition") if is_anchor else ("gltfOrigin", "sdkWorldOrigin")
        actual = glb.position(node)
        close(actual, record[key], f"Node translation mismatch: {node}", "worldPosition")
        close(sdk(actual), record[world_key], f"SDK world mismatch: {node}", "sdkWorldPosition")
        yaw = math.radians(record["yawDeg"])
        forward = [math.sin(yaw), 0, math.cos(yaw)]
        close(forward, record["forward"], f"Manifest forward mismatch: {node}", "declaredForward")
        close(glb.forward(node), record["forward"], f"GLB +Z direction mismatch: {node}", "nodeForward")
        if is_anchor:
            facing = record["facingNode"]
            expect(facing in glb.names, f"Missing facing node: {facing}", "nodeExists")
            if facing in glb.names:
                close(normalized([a-b for a, b in zip(glb.position(facing), actual)]), forward, f"Facing target mismatch: {facing}", "facingDirection")
            if "lookTargetNode" in record:
                look = record["lookTargetNode"]
                expect(look in glb.names, f"Missing look target: {look}", "nodeExists")
                if look in glb.names:
                    close(glb.position(look), record["gltfLookTarget"], f"Look target mismatch: {look}", "lookTargetPosition")
            extras = glb.nodes[glb.names[node]].get("extras", {})
            for k, v in [("anchorId", record["id"]), ("contact", record["contact"]), ("navigable", record["navigable"])]:
                expect(extras.get(k) == v, f"GLB extras mismatch: {node}.{k}", "anchorExtras")

    bound_comparisons = []
    for record in manifest["objects"]:
        actual = glb.bounds(record["node"])
        declared = record["gltfBounds"]
        error = max(delta(actual[k], declared[k]) for k in ("min", "max"))
        bound_comparisons.append({"node": record["node"], "maxErrorMeters": error, "actual": actual, "declared": declared})
        expect(error <= EPS, f"Furniture bounds differ from GLB vertices: {record['node']} error={error:.6f}m", "furnitureBounds")

    anchors = {a["id"]: a for a in manifest["anchors"]}
    walking = {a["name"]: a for a in walk}
    navigable = {a["id"].lower() for a in manifest["anchors"] if a["navigable"]}
    expect(set(walking) == navigable, "Walking sidecar names differ from navigable interaction IDs", "walkingSidecar")
    for name, w in walking.items():
        a = anchors.get(name.upper())
        if not a:
            continue
        close([w["x"], w["z"]], [a["sdkWorldPosition"][0], a["sdkWorldPosition"][2]], f"Walking coordinate mismatch: {name}", "walkingSidecar")
        b = meta["bounds"]
        expect(b["minX"] <= w["x"] <= b["maxX"] and b["minZ"] <= w["z"] <= b["maxZ"], f"Walk point outside meta bounds: {name}", "walkingBounds")

    manifest_edges = {tuple(sorted(e)) for e in manifest["navigation"]["edges"]}
    walking_edges = set()
    for name, w in walking.items():
        for link in w.get("links", []):
            expect(link in walking, f"Unknown walking link: {name}->{link}", "walkingLinks")
            expect(name in walking.get(link, {}).get("links", []), f"Walking link not reciprocal: {name}->{link}", "walkingLinks")
            walking_edges.add(tuple(sorted([name.upper(), link.upper()])))
    expect(manifest_edges == walking_edges, "Walking sidecar edges differ from interactions graph", "walkingLinks")
    reached, todo = set(), ["SPAWN"]
    while todo:
        current = todo.pop()
        if current in reached:
            continue
        reached.add(current)
        for edge in manifest_edges:
            if current in edge:
                todo.extend(n for n in edge if n not in reached)
    expect(reached == {x.upper() for x in navigable}, "Navigation graph is disconnected", "graphConnectivity")

    radius = manifest["navigation"]["agentRadius"]
    collisions = {"declaredBounds": [], "actualGlbBounds": []}
    for edge in sorted(manifest_edges):
        p, q = [glb.position(anchors[name]["node"]) for name in edge]
        for obstacle in manifest["navigation"]["obstacles"]:
            node = obstacle["node"]
            actual = glb.bounds(node)
            for key, box in [("declaredBounds", obstacle["gltfBounds"]), ("actualGlbBounds", actual)]:
                if segment_hits_expanded_aabb(p, q, box, radius):
                    collisions[key].append({"edge": list(edge), "obstacle": node})
            close(actual["min"], obstacle["gltfBounds"]["min"], f"Navigation min bound mismatch: {node}", "navigationBounds")
            close(actual["max"], obstacle["gltfBounds"]["max"], f"Navigation max bound mismatch: {node}", "navigationBounds")
    for key, found in collisions.items():
        expect(not found, f"Navigation edge intersections ({key}): {found}", "edgeClearance")

    for image in glb.doc.get("images", []):
        expect("bufferView" in image and "uri" not in image, f"Texture not embedded: {image.get('name')}", "embeddedTextures")
    primitives = [p for mesh in glb.doc["meshes"] for p in mesh["primitives"]]
    triangle_count = sum(glb.doc["accessors"][p["indices"]]["count"]//3 for p in primitives if p.get("mode", 4) == 4 and "indices" in p)
    warnings.extend([
        "Geometry validation only: no Android/Filament device rendering or avatar pose fitting was performed.",
        "Navigation checks cover declared graph edges against the eight listed furniture obstacles; they do not implement runtime collision avoidance.",
        "Walking sidecar contains XZ only; arrival yaw, vertical rug compensation, sitting, lying and hand IK need host logic.",
    ])
    return {
        "status": "PASS" if not errors else "FAIL",
        "asset": "dream_bedroom.glb",
        "sha256": hashlib.sha256(glb.raw).hexdigest(),
        "byteLength": len(glb.raw),
        "counts": {"sceneNodes": len(glb.world), "meshes": len(glb.doc["meshes"]), "primitives": len(primitives), "triangles": triangle_count, "materials": len(glb.doc["materials"]), "embeddedImages": len(glb.doc.get("images", [])), "furnitureRoots": len(manifest["objects"]), "interactionAnchors": len(anchors), "navigableAnchors": len(walking), "undirectedEdges": len(manifest_edges)},
        "toleranceMeters": EPS,
        "agentRadiusMeters": radius,
        "checks": dict(checks),
        "navigationCollisions": collisions,
        "cutawayGroups": cutaway_groups,
        "furnitureBounds": bound_comparisons,
        "errors": list(dict.fromkeys(errors)),
        "limitations": warnings,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--asset-dir", type=Path, default=ROOT/"assets/dream_bedroom")
    parser.add_argument("--update-bounds", action="store_true", help="Replace furniture and navigation AABBs with bounds from actual exported GLB vertices")
    args = parser.parse_args()
    if args.update_bounds:
        glb = Glb(args.asset_dir/"dream_bedroom.glb")
        manifest_path = args.asset_dir/"interactions.json"
        manifest = json.loads(manifest_path.read_text())
        for record in manifest["objects"]+manifest["navigation"]["obstacles"]:
            record["gltfBounds"] = {k: [round(v, 6) for v in values] for k, values in glb.bounds(record["node"]).items()}
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+"\n")
    result = validate(args.asset_dir)
    (args.asset_dir/"validation.json").write_text(json.dumps(result, ensure_ascii=False, indent=2)+"\n")
    lines = [f"Dream bedroom validation: {result['status']}", f"GLB SHA-256: {result['sha256']}", f"Counts: {json.dumps(result['counts'])}", f"Coordinate tolerance: {EPS} m; navigation radius: {result['agentRadiusMeters']} m", ""]
    lines += [f"ERROR: {e}" for e in result["errors"]]
    lines += [f"LIMITATION: {w}" for w in result["limitations"]]
    (args.asset_dir/"validation.txt").write_text("\n".join(lines)+"\n")
    print("\n".join(lines))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
