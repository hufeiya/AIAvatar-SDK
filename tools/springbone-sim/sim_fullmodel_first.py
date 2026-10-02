import json, struct
import numpy as np

path = "/home/neethu/projects/AIAvatar-SDK/app/src/main/assets/vrms/10.vrm"
with open(path, "rb") as f: data = f.read()
chunk_len, _ = struct.unpack_from("<II", data, 12)
js = json.loads(data[20:20+chunk_len].decode("utf-8"))
nodes = js["nodes"]
N = len(nodes)

# ---- build local matrices ----
def quat_mat(q):
    x,y,z,w = q
    return np.array([
        [1-2*(y*y+z*z), 2*(x*y-w*z), 2*(x*z+w*y)],
        [2*(x*y+w*z), 1-2*(x*x+z*z), 2*(y*z-w*x)],
        [2*(x*z-w*y), 2*(y*z+w*x), 1-2*(x*x+y*y)]])

locals_ = []
for n in nodes:
    m = np.eye(4)
    t = n.get("translation", [0,0,0])
    r = n.get("rotation", [0,0,0,1])
    s = n.get("scale", [1,1,1])
    m[:3,:3] = quat_mat(r) @ np.diag(s)
    m[:3,3] = t
    locals_.append(m)

parent = [-1]*N
for i,n in enumerate(nodes):
    for c in n.get("children", []):
        parent[c] = i

def world(i):
    if i in world_cache: return world_cache[i]
    m = locals_[i]
    if parent[i] >= 0: m = world(parent[i]) @ m
    world_cache[i] = m
    return m

world_cache = {}
def invalidate(): world_cache.clear()

# ---- spring data ----
sb = js["extensions"]["VRMC_springBone"]
def parse_vec(e, d=None):
    if e is None: return d
    return np.array(e, dtype=float)

colliders = []
for c in sb.get("colliders", []):
    shape = c["shape"]
    if "sphere" in shape:
        colliders.append(dict(node=c["node"], offset=parse_vec(shape["sphere"]["offset"]), radius=shape["sphere"]["radius"], tail=None))
    else:
        cap = shape["capsule"]
        colliders.append(dict(node=c["node"], offset=parse_vec(cap["offset"]), radius=cap["radius"], tail=parse_vec(cap["tail"])))
groups = [g.get("colliders", []) for g in sb.get("colliderGroups", [])]

# name -> node index (first match like getFirstEntityByName)
name2node = {}
for i,n in enumerate(nodes):
    nm = n.get("name")
    if nm and nm not in name2node: name2node[nm] = i

# duplicate names check
from collections import Counter
name_counts = Counter(n.get("name") for n in nodes if n.get("name"))
dups = {k:v for k,v in name_counts.items() if v>1}
print("duplicate node names:", len(dups), list(dups.items())[:5])

def quat_rot(q, v):
    x,y,z,w = q; vx,vy,vz = v
    tx = 2*(y*vz - z*vy); ty = 2*(z*vx - x*vz); tz = 2*(x*vy - y*vx)
    return np.array([vx + w*tx + (y*tz - z*ty), vy + w*ty + (z*tx - x*tz), vz + w*tz + (x*ty - y*tx)])
def quat_rot_inv(q, v): return quat_rot(np.array([-q[0],-q[1],-q[2],q[3]]), v)
def quat_mul(a,b):
    ax,ay,az,aw=a; bx,by,bz,bw=b
    return np.array([aw*bx+ax*bw+ay*bz-az*by, aw*by-ax*bz+ay*bw+az*bx, aw*bz+ax*by-ay*bx+az*bw, aw*bw-ax*bx-ay*by-az*bz])
# ---- bind (port of bindToAsset) ----
TAIL_RATIO = 0.07
springs = []
for chain in sb.get("springs", []):
    states = []; prms = []
    for i, j in enumerate(chain["joints"]):
        ni = j["node"]
        head_w = world(ni)[:3,3].copy()
        rest_local_q = None
        # matrixToQuaternion of local
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
        rest_local_q = mat_to_quat(locals_[ni])
        wq = mat_to_quat(world(ni))
        if i+1 < len(chain["joints"]):
            nxt = chain["joints"][i+1]["node"]
            cw = world(nxt)[:3,3]
            wd = cw - head_w
            L = norm(wd)
            if L > 1e-6:
                ax = quat_rot_inv(wq, wd)  # need inverse rotate
                ax = ax/norm(ax)
            else:
                ax = np.array([0,-1.0,0]); L = 0.01
        else:
            if states:
                L = max(states[-1]["L"]*TAIL_RATIO, 0.01); ax = states[-1]["ax"].copy()
            else:
                ax = np.array([0,-1.0,0]); L = 0.01
        tail = head_w + quat_rot(wq, ax)*L
        states.append(dict(node=ni, L=L, ax=ax, restq=rest_local_q, prev=tail.copy(), cur=tail.copy()))
        prms.append(dict(
            stiffness=j.get("stiffness", 1.0), gravityPower=j.get("gravityPower", 0.0),
            gravityDir=parse_vec(j.get("gravityDir"), np.array([0,-1.0,0])), dragForce=j.get("dragForce", 0.5),
            hitRadius=j.get("hitRadius", 0.0)))
    cg = []
    for gi in chain.get("colliderGroups", []):
        cg += groups[gi] if gi < len(groups) else []
    springs.append((states, prms, cg))

def quat_rot_inv(q, v): return quat_rot(np.array([-q[0],-q[1],-q[2],q[3]]), v)
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

# ---- update (port of update()) ----
def update(dt):
    for states, prms, cg in springs:
        for st, p in zip(states, prms):
            ni = st["node"]
            invalidate()
            w = world(ni)
            head = w[:3,3].copy()
            pi = parent[ni]
            parentRot = mat_to_quat(world(pi)) if pi>=0 else np.array([0,0,0,1.0])
            inertia = (st["cur"]-st["prev"])*(1-p["dragForce"])
            combined = quat_mul(parentRot, st["restq"])
            sdir = quat_rot(combined, st["ax"])
            nxt = st["cur"] + inertia + sdir*p["stiffness"]*dt + p["gravityDir"]*p["gravityPower"]*dt
            nxt = constrain(head, nxt, st["L"])
            # collision
            for ci in cg:
                col = colliders[ci]
                cwn = world(col["node"])
                cpos = cwn[:3,:3] @ col["offset"] + cwn[:3,3]
                if col["tail"] is None:
                    r = p["hitRadius"] + col["radius"]
                    dv = nxt - cpos; ds = norm(dv)
                    if ds <= r:
                        nrm = dv/ds if ds>1e-6 else np.array([0,1.0,0])
                        nxt = constrain(head, cpos + nrm*r, st["L"])
                else:
                    ctail = cwn[:3,:3] @ col["tail"] + cwn[:3,3]
                    seg = ctail - cpos; sl = norm(seg)
                    if sl < 1e-10:
                        r = p["hitRadius"] + col["radius"]
                        dv = nxt - cpos; ds = norm(dv)
                        if ds <= r:
                            nrm = dv/ds if ds>1e-6 else np.array([0,1.0,0])
                            nxt = constrain(head, cpos + nrm*r, st["L"])
                    else:
                        sdir2 = seg/sl
                        dot = float(np.dot(nxt-cpos, sdir2))
                        cp = cpos + sdir2*min(max(dot,0.0), sl)
                        r = p["hitRadius"] + col["radius"]
                        dv = nxt - cp; ds = norm(dv)
                        if ds <= r:
                            nrm = dv/ds if ds>1e-6 else np.array([0,1.0,0])
                            nxt = constrain(head, cp + nrm*r, st["L"])
            st["prev"] = st["cur"].copy(); st["cur"] = nxt.copy()
            # rotation recovery -> write local
            cur = nxt-head; cur = cur/norm(cur)
            rest = quat_rot(combined, st["ax"]); rest = rest/norm(rest)
            ft = from_to(rest, cur)
            newWorld = quat_mul(ft, combined)
            newLocal = quat_mul(quat_inv4(parentRot), newWorld)
            # write local rotation, keep translation/scale
            R = quat_mat(newLocal)
            old = locals_[ni]
            S = np.diag([norm(old[:3,0]), norm(old[:3,1]), norm(old[:3,2])])
            locals_[ni][:3,:3] = R @ S
            # NOTE Kotlin OVERWRITES scale with quaternionToMatrix (loses scale!) — here we emulate Kotlin: 
            # Kotlin: quaternionToMatrix(newLocalRot, localMat) then restore translation only => scale lost & set to 1
            locals_[ni][:3,:3] = R   # faithful to Kotlin

def quat_inv4(q):
    return np.array([-q[0],-q[1],-q[2],q[3]])

# settle
for f in range(180): update(1/60)

def report(tag):
    invalidate()
    ni = name2node["J_Sec_Hair2_04"]
    st = None
    for states,prms,cg in springs:
        for s in states:
            if s["node"]==ni: st=s; break
    head = world(ni)[:3,3]
    d = st["cur"]-head; d = d/norm(d)
    # rest direction in world at rest pose:
    print(f"{tag}: hair2_04 tail dir = {np.round(d,3)}, head={np.round(head,3)}")

report("after settle")
# drag +X
root_i = name2node["Root"]
for f in range(60):
    locals_[root_i][0,3] += 0.02   # note: app moves asset.root; here 'Root' is the top glTF node — same effect on children
    update(1/60)
report("after 1.2m +X move")
# stop, settle
for f in range(120): update(1/60)
report("after stop")
