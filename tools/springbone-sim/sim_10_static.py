#!/usr/bin/env python3
"""
Static settled state of 10.vrm under the CURRENT Kotlin algorithm (faithful port
of VrmSpringBoneManager.stepSprings), for comparison against the Node ground
truth (node_run_10_static.ts -> node_native-settled-5s.txt).

No VRMA, no drag: model sits in bind pose, springs settle from bind tails.
Native scale (no transformToUnitCube) == three-vrm example conditions.

Usage: python3 sim_10_static.py   (writes /tmp/sbtest/py_native-settled-5s.txt)
"""
import json, struct, math, sys
import numpy as np
from numpy.linalg import norm

path = "/home/neethu/projects/AIAvatar-SDK/app/src/main/assets/vrms/10.vrm"
data = open(path, "rb").read()
chunk_len, _ = struct.unpack_from("<II", data, 12)
js = json.loads(data[20:20 + chunk_len].decode("utf-8"))
nodes = js["nodes"]
N = len(nodes)

def quat_mat(q):
    x, y, z, w = q
    return np.array([
        [1-2*(y*y+z*z), 2*(x*y-w*z), 2*(x*z+w*y)],
        [2*(x*y+w*z), 1-2*(x*x+z*z), 2*(y*z-w*x)],
        [2*(x*z-w*y), 2*(y*z+w*x), 1-2*(x*x+y*y)]])

def quat_mul(a, b):
    ax, ay, az, aw = a; bx, by, bz, bw = b
    return np.array([aw*bx+ax*bw+ay*bz-az*by, aw*by-ax*bz+ay*bw+az*bx,
                     aw*bz+ax*by-ay*bx+az*bw, aw*bw-ax*bx-ay*by-az*bz])

def quat_inv(q): return np.array([-q[0], -q[1], -q[2], q[3]])

def quat_rot(q, v):
    x, y, z, w = q; vx, vy, vz = v
    tx = 2*(y*vz - z*vy); ty = 2*(z*vx - x*vz); tz = 2*(x*vy - y*vx)
    return np.array([vx + w*tx + (y*tz - z*ty), vy + w*ty + (z*tx - x*tz),
                     vz + w*tz + (x*ty - y*tx)])

def mat_to_quat(m):
    m00, m01, m02 = m[0, 0], m[0, 1], m[0, 2]
    m10, m11, m12 = m[1, 0], m[1, 1], m[1, 2]
    m20, m21, m22 = m[2, 0], m[2, 1], m[2, 2]
    tr = m00+m11+m22
    if tr > 0:
        s = np.sqrt(tr+1)*2; return np.array([(m21-m12)/s, (m02-m20)/s, (m10-m01)/s, s*0.25])
    elif m00 > m11 and m00 > m22:
        s = np.sqrt(1+m00-m11-m22)*2; return np.array([s*0.25, (m01+m10)/s, (m02+m20)/s, (m21-m12)/s])
    elif m11 > m22:
        s = np.sqrt(1+m11-m00-m22)*2; return np.array([(m01+m10)/s, s*0.25, (m12+m21)/s, (m02-m20)/s])
    else:
        s = np.sqrt(1+m22-m00-m11)*2; return np.array([(m02+m20)/s, (m12+m21)/s, s*0.25, (m10-m01)/s])

def from_to(f, t):
    d = float(np.dot(f, t))
    if d > 0.999999: return np.array([0, 0, 0, 1.0])
    if d < -0.999999:
        perp = np.array([1.0, 0, 0]) if abs(f[0]) <= 0.9 else np.array([0, 1.0, 0])
        a = np.cross(f, perp); l = norm(a); return np.array([a[0]/l, a[1]/l, a[2]/l, 0.0])
    c = np.cross(f, t); w = 1+d; l = np.sqrt(c@c+w*w)
    return np.array([c[0]/l, c[1]/l, c[2]/l, w/l])

def constrain(head, tail, L):
    d = tail-head; dist = norm(d)
    if dist < 1e-8: return head+np.array([0, -L, 0])
    return head + d*(L/dist)

def mul_point(m, p): return m[:3, :3] @ p + m[:3, 3]

# ---- hierarchy ----
locals_ = []
for n in nodes:
    m = np.eye(4)
    t = n.get("translation", [0, 0, 0]); r = n.get("rotation", [0, 0, 0, 1]); s = n.get("scale", [1, 1, 1])
    m[:3, :3] = quat_mat(r) @ np.diag(s); m[:3, 3] = t
    locals_.append(m)
parent = [-1]*N
for i, n in enumerate(nodes):
    for c in n.get("children", []): parent[c] = i
children_map = {i: n.get("children", []) for i, n in enumerate(nodes)}
world_cache = {}
def invalidate(): world_cache.clear()
def world(i):
    if i in world_cache: return world_cache[i]
    m = locals_[i]
    if parent[i] >= 0: m = world(parent[i]) @ m
    world_cache[i] = m
    return m

# ---- springs exactly like Kotlin parseVrmc10SpringBone + bindToAsset ----
sb = js["extensions"]["VRMC_springBone"]
colliders = []
for c in sb.get("colliders", []):
    shape = c["shape"]
    if "sphere" in shape:
        colliders.append(dict(node=c["node"], offset=np.array(shape["sphere"]["offset"], dtype=float),
                              radius=shape["sphere"]["radius"], tail=None))
    else:
        cap = shape["capsule"]
        colliders.append(dict(node=c["node"], offset=np.array(cap["offset"], dtype=float),
                              radius=cap["radius"], tail=np.array(cap["tail"], dtype=float)))
groups = [g.get("colliders", []) for g in sb.get("colliderGroups", [])]

springs = []
for chain in sb.get("springs", []):
    center_idx = chain.get("center")
    njoint = len(chain["joints"])
    # Kotlin: drop the LAST schema joint (tail-only, three-vrm v1 import semantics)
    sim_joints = chain["joints"][:-1]
    states = []; prms = []
    for i, j in enumerate(sim_joints):
        ni = j["node"]; head_w = world(ni)[:3, 3].copy()
        rest_local_q = mat_to_quat(locals_[ni]); wq = mat_to_quat(world(ni))
        # Kotlin tailNode: next schema joint node (in the REDUCED list); for the last
        # remaining joint that is the dropped node, which == chain["joints"][i+1]
        if i+1 < njoint: nxt = chain["joints"][i+1]["node"]
        elif children_map.get(ni): nxt = children_map[ni][0]
        else: nxt = None
        if nxt is not None:
            cw = world(nxt)[:3, 3]; wd = cw-head_w; L = norm(wd)
            ax = quat_rot(quat_inv(wq), wd)/max(L, 1e-9)
            virtual = False
        else:
            pw = world(parent[ni])[:3, 3]
            wd = head_w-pw; wd = wd/norm(wd)
            wscale = norm(w[:3, 0])
            L = max(0.07*wscale, 0.01)
            ax = quat_rot(quat_inv(wq), wd)
            virtual = True
        tw = head_w + quat_rot(wq, ax)*L
        states.append(dict(node=ni, L=L, restL=L, virtual=virtual, ax=ax, restq=rest_local_q,
                           prev=tw.copy(), cur=tw.copy(), lastTailNode=tw.copy()))
        prms.append(dict(stiffness=j.get("stiffness", 1.0), gravityPower=j.get("gravityPower", 0.0),
                         gravityDir=np.array(j.get("gravityDir", [0, -1, 0]), dtype=float),
                         dragForce=j.get("dragForce", 0.4), hitRadius=j.get("hitRadius", 0.0)))
    cg = []
    for gi in chain.get("colliderGroups", []): cg += groups[gi] if gi < len(groups) else []
    springs.append(dict(name=chain.get("name"), states=states, prms=prms, cg=cg, center=center_idx))

print(f"chains={len(springs)} joints={sum(len(s['states']) for s in springs)} "
      f"colliders={len(colliders)} centered={sum(1 for s in springs if s['center'] is not None)}")

# ---- Kotlin stepSprings (single substep call) ----
def spring_update(dt):
    for sp in springs:
        if sp["center"] is not None:
            cwid = world(sp["center"]).copy(); cwi = np.linalg.inv(cwid)
        else:
            cwid = None; cwi = None
        for st, p in zip(sp["states"], sp["prms"]):
            ni = st["node"]; invalidate()
            w = world(ni); head = w[:3, 3].copy()
            ws = norm(w[:3, 0])
            # per-frame bone length from tail NODE end of last frame
            if not st["virtual"]:
                L = norm(st["lastTailNode"] - head)
                if L > 1e-6: st["L"] = L
            pi = parent[ni]
            parentRot = mat_to_quat(world(pi)) if pi >= 0 else np.array([0, 0, 0, 1.0])
            dragF = 1-p["dragForce"]
            next_c = st["cur"] + (st["cur"]-st["prev"])*dragF
            nxt = mul_point(cwid, next_c) if cwid is not None else next_c
            combined = quat_mul(parentRot, st["restq"])
            sdir = quat_rot(combined, st["ax"])
            nxt = nxt + sdir*p["stiffness"]*dt + p["gravityDir"]*p["gravityPower"]*dt
            nxt = constrain(head, nxt, st["L"])
            for ci in sp["cg"]:
                col = colliders[ci]; cwn = world(col["node"])
                cpos = mul_point(cwn, col["offset"])
                jr = p["hitRadius"]*ws
                cr = col["radius"]*norm(cwn[:3, 0])
                r = jr + cr
                if col["tail"] is None:
                    dv = nxt-cpos; ds = norm(dv)
                    if ds <= r:
                        nrm = dv/ds if ds > 1e-6 else np.array([0, 1.0, 0])
                        nxt = constrain(head, cpos+nrm*r, st["L"])
                else:
                    ctail = mul_point(cwn, col["tail"])
                    seg = ctail-cpos; sl = norm(seg)
                    if sl < 1e-10:
                        dv = nxt-cpos; ds = norm(dv)
                        if ds <= r:
                            nrm = dv/ds if ds > 1e-6 else np.array([0, 1.0, 0])
                            nxt = constrain(head, cpos+nrm*r, st["L"])
                    else:
                        sd = seg/sl; dot = float(np.dot(nxt-cpos, sd))
                        cp = cpos+sd*min(max(dot, 0.0), sl)
                        dv = nxt-cp; ds = norm(dv)
                        if ds <= r:
                            nrm = dv/ds if ds > 1e-6 else np.array([0, 1.0, 0])
                            nxt = constrain(head, cp+nrm*r, st["L"])
            st["prev"] = st["cur"].copy()
            st["cur"] = mul_point(cwi, nxt) if cwi is not None else nxt
            cur = nxt-head; cur = cur/norm(cur)
            rest = quat_rot(combined, st["ax"]); rest = rest/norm(rest)
            ft = from_to(rest, cur)
            newWorld = quat_mul(ft, combined)
            newLocal = quat_mul(quat_inv(parentRot), newWorld)
            locals_[ni][:3, :3] = quat_mat(newLocal)
            st["lastTailNode"] = head + cur*st["restL"]

def dump(tag):
    lines = []
    for sp in springs:
        for st in sp["states"]:
            ni = st["node"]; invalidate()
            head = world(ni)[:3, 3].copy()
            # tail node world pos: the next schema joint node (unmoved if dropped)
            nj = None
            idx = [k for k, j2 in enumerate(sp["states"]) if j2 is st][0]
            all_joints = js["extensions"]["VRMC_springBone"]["springs"][[s2 is sp for s2 in springs].index(True)]["joints"]
            if idx+1 < len(all_joints): nj = all_joints[idx+1]["node"]
            elif children_map.get(ni): nj = children_map[ni][0]
            if nj is not None:
                tp = world(nj)[:3, 3].copy()
            else:
                tp = head + np.array([0, -0.07, 0])
            d = tp-head; L = norm(d); d = d/max(L, 1e-12)
            p = sp["prms"][idx]
            lines.append(f"{nodes[ni].get('name')}|{nodes[nj].get('name') if nj is not None else 'VTAIL'} "
                         f"pos {head[0]:.5f} {head[1]:.5f} {head[2]:.5f} tail {tp[0]:.5f} {tp[1]:.5f} {tp[2]:.5f} "
                         f"len {L:.5f} stiff {p['stiffness']} gp {p['gravityPower']} drag {p['dragForce']} hit {p['hitRadius']}")
    open(f"/tmp/sbtest/py_{tag}.txt", "w").write("\n".join(lines))
    print(f"wrote /tmp/sbtest/py_{tag}.txt ({len(lines)} joints)")

# ---- settle: Kotlin substepping (dt=1/60 -> 2 substeps of 1/120) ----
dt = 1/60
steps = max(1, math.ceil(dt*120)); steps = min(steps, 8)
for f in range(300):
    for _ in range(steps):
        spring_update(dt/steps)
dump("native-settled-5s")
