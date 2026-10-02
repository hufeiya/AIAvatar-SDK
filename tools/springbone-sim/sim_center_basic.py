import json, struct
import numpy as np

path = "/home/neethu/projects/AIAvatar-SDK/app/src/main/assets/vrms/10.vrm"
with open(path, "rb") as f: data = f.read()
chunk_len, _ = struct.unpack_from("<II", data, 12)
js = json.loads(data[20:20+chunk_len].decode("utf-8"))
nodes = js["nodes"]
N = len(nodes)

def quat_mat(q):
    x,y,z,w = q
    return np.array([
        [1-2*(y*y+z*z), 2*(x*y-w*z), 2*(x*z+w*y)],
        [2*(x*y+w*z), 1-2*(x*x+z*z), 2*(y*z-w*x)],
        [2*(x*z-w*y), 2*(y*z+w*x), 1-2*(x*x+y*y)]])

locals_ = []
for n in nodes:
    m = np.eye(4)
    t = n.get("translation", [0,0,0]); r = n.get("rotation", [0,0,0,1]); s = n.get("scale", [1,1,1])
    m[:3,:3] = quat_mat(r) @ np.diag(s); m[:3,3] = t
    locals_.append(m)

parent = [-1]*N
for i,n in enumerate(nodes):
    for c in n.get("children", []):
        parent[c] = i

world_cache = {}
def invalidate(): world_cache.clear()
def world(i):
    if i in world_cache: return world_cache[i]
    m = locals_[i]
    if parent[i] >= 0: m = world(parent[i]) @ m
    world_cache[i] = m
    return m

def quat_rot(q, v):
    x,y,z,w = q; vx,vy,vz = v
    tx = 2*(y*vz - z*vy); ty = 2*(z*vx - x*vz); tz = 2*(x*vy - y*vx)
    return np.array([vx + w*tx + (y*tz - z*ty), vy + w*ty + (z*tx - x*tz), vz + w*tz + (x*ty - y*tx)])
def quat_rot_inv(q, v): return quat_rot(np.array([-q[0],-q[1],-q[2],q[3]]), v)
def quat_mul(a,b):
    ax,ay,az,aw=a; bx,by,bz,bw=b
    return np.array([aw*bx+ax*bw+ay*bz-az*by, aw*by-ax*bz+ay*bw+az*bx, aw*bz+ax*by-ay*bx+az*bw, aw*bw-ax*bx-ay*by-az*bz])
from numpy.linalg import norm
def mat_to_quat(m):
    m00,m01,m02 = m[0,0],m[0,1],m[0,2]
    m10,m11,m12 = m[1,0],m[1,1],m[1,2]
    m20,m21,m22 = m[2,0],m[2,1],m[2,2]
    tr = m00+m11+m22
    if tr>0:
        s=np.sqrt(tr+1)*2; return np.array([(m21-m12)/s,(m02-m20)/s,(m10-m01)/s,s*0.25])
    elif m00>m11 and m00>m22:
        s=np.sqrt(1+m00-m11-m22)*2; return np.array([s*0.25,(m01+m10)/s,(m02+m20)/s,(m21-m12)/s])
    elif m11>m22:
        s=np.sqrt(1+m11-m00-m22)*2; return np.array([(m01+m10)/s,s*0.25,(m12+m21)/s,(m02-m20)/s])
    else:
        s=np.sqrt(1+m22-m00-m11)*2; return np.array([(m02+m20)/s,(m12+m21)/s,s*0.25,(m10-m01)/s])
def from_to(f,t):
    d = float(np.dot(f,t))
    if d>0.999999: return np.array([0,0,0,1.0])
    if d<-0.999999:
        perp = np.array([1.0,0,0]) if abs(f[0])<=0.9 else np.array([0,1.0,0])
        a = np.cross(f,perp); l=norm(a); return np.array([a[0]/l,a[1]/l,a[2]/l,0.0])
    c = np.cross(f,t); w=1+d; l=np.sqrt(c@c+w*w)
    return np.array([c[0]/l,c[1]/l,c[2]/l,w/l])
def constrain(head,tail,L):
    d = tail-head; dist = norm(d)
    if dist < 1e-8: return head+np.array([0,-L,0])
    return head + d*(L/dist)
def mat4_inv(m):
    return np.linalg.inv(m)

def mul_point(m, p):
    return m[:3,:3] @ p + m[:3,3]

sb = js["extensions"]["VRMC_springBone"]
name2node = {}
for i,n in enumerate(nodes):
    nm = n.get("name")
    if nm and nm not in name2node: name2node[nm] = i

children_map = {i: n.get("children", []) for i,n in enumerate(nodes)}

colliders = []
for c in sb.get("colliders", []):
    shape = c["shape"]
    if "sphere" in shape:
        colliders.append(dict(node=c["node"], offset=np.array(shape["sphere"]["offset"],dtype=float), radius=shape["sphere"]["radius"], tail=None))
    else:
        cap = shape["capsule"]
        colliders.append(dict(node=c["node"], offset=np.array(cap["offset"],dtype=float), radius=cap["radius"], tail=np.array(cap["tail"],dtype=float)))
groups = [g.get("colliders", []) for g in sb.get("colliderGroups", [])]

springs = []
for chain in sb.get("springs", []):
    center_idx = chain.get("center")   # node index or None
    states = []; prms = []
    nj = len(chain["joints"])
    for i, j in enumerate(chain["joints"]):
        ni = j["node"]
        head_w = world(ni)[:3,3].copy()
        rest_local_q = mat_to_quat(locals_[ni])
        wq = mat_to_quat(world(ni))
        if i+1 < nj:
            nxt = chain["joints"][i+1]["node"]
        elif children_map.get(ni):
            nxt = children_map[ni][0]     # first hierarchy child
        else:
            nxt = None
        if nxt is not None:
            cw = world(nxt)[:3,3]
            wd = cw - head_w; L = norm(wd)
            ax = quat_rot_inv(wq, wd)/max(L,1e-9)
        else:
            pi = parent[ni]
            pw = world(pi)[:3,3]
            wd = head_w - pw; wd = wd/norm(wd)
            tw = head_w + wd*0.07
            v = tw - head_w; L = norm(v)
            ax = quat_rot_inv(wq, v)/L
        tail_w = head_w + quat_rot(wq, ax)*L
        states.append(dict(node=ni, L=L, ax=ax, restq=rest_local_q, prev=None, cur=None))
        prms.append(dict(stiffness=j.get("stiffness",1.0), gravityPower=j.get("gravityPower",0.0),
                         gravityDir=np.array(j.get("gravityDir",[0,-1,0]),dtype=float), dragForce=j.get("dragForce",0.4),
                         hitRadius=j.get("hitRadius",0.0)))
    cg = []
    for gi in chain.get("colliderGroups", []): cg += groups[gi] if gi < len(groups) else []
    springs.append(dict(states=states, prms=prms, cg=cg, center=center_idx))

# init tails in CENTER space (like three-vrm setInitState)
def init_tails():
    for sp in springs:
        cwi = mat4_inv(world(sp["center"])) if sp["center"] is not None else np.eye(4)
        for st in sp["states"]:
            ni = st["node"]
            w = world(ni); head_w = w[:3,3]
            tw = head_w + quat_rot(mat_to_quat(w), st["ax"])*st["L"]
            tc = mul_point(cwi, tw)
            st["prev"] = tc.copy(); st["cur"] = tc.copy()

def update(dt):
    for sp in springs:
        cwid = world(sp["center"]) if sp["center"] is not None else np.eye(4)
        cwi  = mat4_inv(cwid) if sp["center"] is not None else np.eye(4)
        for st, p in zip(sp["states"], sp["prms"]):
            ni = st["node"]
            invalidate()
            w = world(ni); head = w[:3,3].copy()
            pi = parent[ni]
            parentRot = mat_to_quat(world(pi)) if pi>=0 else np.array([0,0,0,1.0])
            # verlet: inertia in CENTER space
            inertia_c = (st["cur"]-st["prev"])*(1-p["dragForce"])
            next_c = st["cur"] + inertia_c
            # to world
            nxt = mul_point(cwid, next_c)
            combined = quat_mul(parentRot, st["restq"])
            sdir = quat_rot(combined, st["ax"])
            nxt = nxt + sdir*p["stiffness"]*dt + p["gravityDir"]*p["gravityPower"]*dt
            nxt = constrain(head, nxt, st["L"])
            # collision (world, radius scaled by world scale)
            for ci in sp["cg"]:
                col = colliders[ci]
                cwn = world(col["node"])
                cpos = mul_point(cwn, col["offset"])
                r = (p["hitRadius"] + col["radius"]) * norm(cwn[:3,0])
                if col["tail"] is None:
                    dv = nxt - cpos; ds = norm(dv)
                    if ds <= r:
                        nrm = dv/ds if ds>1e-6 else np.array([0,1.0,0])
                        nxt = constrain(head, cpos + nrm*r, st["L"])
                else:
                    ctail = mul_point(cwn, col["tail"])
                    seg = ctail - cpos; sl = norm(seg)
                    if sl < 1e-10:
                        dv = nxt - cpos; ds = norm(dv)
                        if ds <= r:
                            nrm = dv/ds if ds>1e-6 else np.array([0,1.0,0])
                            nxt = constrain(head, cpos + nrm*r, st["L"])
                    else:
                        sd = seg/sl
                        dot = float(np.dot(nxt-cpos, sd))
                        cp = cpos + sd*min(max(dot,0.0), sl)
                        dv = nxt - cp; ds = norm(dv)
                        if ds <= r:
                            nrm = dv/ds if ds>1e-6 else np.array([0,1.0,0])
                            nxt = constrain(head, cp + nrm*r, st["L"])
            st["prev"] = st["cur"].copy()
            st["cur"] = mul_point(cwi, nxt)   # back to center space
            # rotation recovery (world)
            cur = nxt-head; cur = cur/norm(cur)
            rest = quat_rot(combined, st["ax"]); rest = rest/norm(rest)
            ft = from_to(rest, cur)
            newWorld = quat_mul(ft, combined)
            newLocal = quat_mul(np.array([-parentRot[0],-parentRot[1],-parentRot[2],parentRot[3]]), newWorld)
            R = quat_mat(newLocal)
            locals_[ni][:3,:3] = R

def report(tag, ni_name="J_Sec_Hair2_04"):
    invalidate()
    ni = name2node[ni_name]
    st = None
    for sp in springs:
        for s in sp["states"]:
            if s["node"]==ni: st=s; break
    head = world(ni)[:3,3]
    tw = mul_point(world([s["center"] for s in springs if any(x["node"]==ni for x in s["states"])][0]) if False else np.eye(4), st["cur"])
    # convert cur (center space) to world for reporting
    sp = next(sp for sp in springs if any(x["node"]==ni for x in sp["states"]))
    cwid = world(sp["center"]) if sp["center"] is not None else np.eye(4)
    tw = mul_point(cwid, st["cur"])
    d = tw-head; d = d/norm(d)
    print(f"{tag}: {ni_name} tail dir = {np.round(d,3)} head={np.round(head,2)}")
    return d

init_tails()
for f in range(180): update(1/60)
report("A rest")
# continuous translate +X
for f in range(60):
    locals_[name2node["Root"]][0,3] += 0.02
    update(1/60)
d2 = report("B moving +X (expect ~same as rest)")
for f in range(120): update(1/60)
report("C after stop")
# instant teleport 1m
locals_[name2node["Root"]][0,3] += 1.0
update(1/60)
report("D teleport +1m (expect ~same as rest)")
for f in range(60): update(1/60)
report("E settle after teleport")
# body rotation test: rotate Head 30deg over 30 frames
import math
for f in range(30):
    q = np.array([0, math.sin(math.radians(1.0)), 0, math.cos(math.radians(1.0))])
    R = quat_mat(q)
    hi = name2node["J_Bip_C_Head"]
    locals_[hi][:3,:3] = R @ locals_[hi][:3,:3]
    update(1/60)
d6 = report("F head rotated 30deg yaw (expect hair swung +X-ish)")
