import json, struct, math
import numpy as np
from numpy.linalg import norm

path = "/home/neethu/projects/three-vrm/packages/three-vrm/examples/models/VRM1_Constraint_Twist_Sample.vrm"
data = open(path, "rb").read()
chunk_len, _ = struct.unpack_from("<II", data, 12)
js = json.loads(data[20:20+chunk_len].decode("utf-8"))
nodes = js["nodes"]; N = len(nodes)

def quat_mat(q):
    x,y,z,w = q
    return np.array([
        [1-2*(y*y+z*z), 2*(x*y-w*z), 2*(x*z+w*y)],
        [2*(x*y+w*z), 1-2*(x*x+z*z), 2*(y*z-w*x)],
        [2*(x*z-w*y), 2*(y*z+w*x), 1-2*(x*x+y*y)]])
def quat_mul(a,b):
    ax,ay,az,aw=a; bx,by,bz,bw=b
    return np.array([aw*bx+ax*bw+ay*bz-az*by, aw*by-ax*bz+ay*bw+az*bx, aw*bz+ax*by-ay*bx+az*bw, aw*bw-ax*bx-ay*by-az*bz])
def quat_inv(q): return np.array([-q[0],-q[1],-q[2],q[3]])
def quat_rot(q, v):
    x,y,z,w = q; vx,vy,vz = v
    tx = 2*(y*vz - z*vy); ty = 2*(z*vx - x*vz); tz = 2*(x*vy - y*vx)
    return np.array([vx + w*tx + (y*tz - z*ty), vy + w*ty + (z*tx - x*tz), vz + w*tz + (x*ty - y*tx)])
from numpy.linalg import norm as _n
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
        a = np.cross(f,perp); l=_n(a); return np.array([a[0]/l,a[1]/l,a[2]/l,0.0])
    c = np.cross(f,t); w=1+d; l=np.sqrt(c@c+w*w)
    return np.array([c[0]/l,c[1]/l,c[2]/l,w/l])
def constrain(head,tail,L):
    d = tail-head; dist = _n(d)
    if dist < 1e-8: return head+np.array([0,-L,0])
    return head + d*(L/dist)
def mul_point(m, p): return m[:3,:3] @ p + m[:3,3]

locals_ = []
for n in nodes:
    m = np.eye(4)
    t = n.get("translation",[0,0,0]); r = n.get("rotation",[0,0,0,1]); s = n.get("scale",[1,1,1])
    m[:3,:3] = quat_mat(r) @ np.diag(s); m[:3,3] = t
    locals_.append(m)
parent = [-1]*N
for i,n in enumerate(nodes):
    for c in n.get("children", []): parent[c] = i
name2node = {}
for i,n in enumerate(nodes):
    nm = n.get("name")
    if nm and nm not in name2node: name2node[nm] = i
children_map = {i: n.get("children", []) for i,n in enumerate(nodes)}
world_cache = {}
def invalidate(): world_cache.clear()
def world(i):
    if i in world_cache: return world_cache[i]
    m = locals_[i]
    if parent[i] >= 0: m = world(parent[i]) @ m
    world_cache[i] = m
    return m

sb = js["extensions"]["VRMC_springBone"]
# NOTE: no center anywhere -> world space simulation
springs = []
for chain in sb.get("springs", []):
    states=[]; prms=[]
    nj = len(chain["joints"])
    for i, j in enumerate(chain["joints"]):
        ni = j["node"]; head_w = world(ni)[:3,3].copy()
        rest_local_q = mat_to_quat(locals_[ni]); wq = mat_to_quat(world(ni))
        if i+1 < nj: nxt = chain["joints"][i+1]["node"]
        elif children_map.get(ni): nxt = children_map[ni][0]
        else: nxt = None
        is_virtual = False
        if nxt is not None:
            cw = world(nxt)[:3,3]; wd = cw-head_w; L=_n(wd)
            ax = quat_rot(quat_inv(wq), wd)/max(L,1e-9)
        else:
            pw = world(parent[ni])[:3,3]
            wd = head_w-pw; wd = wd/_n(wd)
            L=0.07; ax = quat_rot(quat_inv(wq), wd); is_virtual=True
        tw = head_w + quat_rot(wq, ax)*L
        states.append(dict(node=ni, L=L, restL=L, ax=ax, restq=rest_local_q, prev=tw.copy(), cur=tw.copy(),
                           virtual=is_virtual, lastTail=tw.copy()))
        prms.append(dict(stiffness=j.get("stiffness",1.0), gravityPower=j.get("gravityPower",0.0),
                         gravityDir=np.array(j.get("gravityDir",[0,-1,0]),dtype=float),
                         dragForce=j.get("dragForce",0.4), hitRadius=j.get("hitRadius",0.0)))
    springs.append(dict(states=states, prms=prms, cg=[], center=None))

def init_tails():
    for sp in springs:
        for st in sp["states"]:
            ni = st["node"]; w = world(ni)
            tw = w[:3,3] + quat_rot(mat_to_quat(w), st["ax"])*st["L"]
            st["prev"]=tw.copy(); st["cur"]=tw.copy(); st["lastTail"]=tw.copy(); st["L"]=st["restL"]

def spring_update(dt):
    for sp in springs:
        for st, p in zip(sp["states"], sp["prms"]):
            ni = st["node"]; invalidate()
            w = world(ni); head = w[:3,3].copy()
            ws = _n(w[:3,0])
            # per-frame bone length (three-vrm slack), virtual tails keep fixed length
            if not st["virtual"]:
                newL = _n(st["lastTail"] - head)
                if newL > 1e-6: st["L"] = newL
            pi = parent[ni]
            parentRot = mat_to_quat(world(pi)) if pi>=0 else np.array([0,0,0,1.0])
            dragF = 1-p["dragForce"]
            nxt = st["cur"] + (st["cur"]-st["prev"])*dragF   # world space (no center)
            combined = quat_mul(parentRot, st["restq"])
            sdir = quat_rot(combined, st["ax"])
            nxt = nxt + sdir*p["stiffness"]*dt + p["gravityDir"]*p["gravityPower"]*dt
            nxt = constrain(head, nxt, st["L"])
            st["prev"]=st["cur"].copy(); st["cur"]=nxt.copy()
            cur = nxt-head; cur=cur/_n(cur)
            rest = quat_rot(combined, st["ax"]); rest=rest/_n(rest)
            ft = from_to(rest, cur)
            newWorld = quat_mul(ft, combined)
            newLocal = quat_mul(quat_inv(parentRot), newWorld)
            locals_[ni][:3,:3] = quat_mat(newLocal)
            # bone-snapped tail node position (three-vrm child.matrixWorld equivalent)
            st["lastTail"] = head + cur*st["restL"]

def substepped(dt):
    n = max(1, int(math.ceil(dt / (1/120))))
    for _ in range(n):
        spring_update(dt / n)

# drop last schema joints once (three-vrm semantics)
for sp in springs:
    if len(sp["states"])>=1:
        sp["states"]=sp["states"][:-1]; sp["prms"]=sp["prms"][:-1]
springs[:] = [sp for sp in springs if sp["states"]]

def run(mode, step, frames, label):
    # reset pose
    for i in range(N):
        n = nodes[i]
        m = np.eye(4)
        t = n.get("translation",[0,0,0]); r = n.get("rotation",[0,0,0,1]); s = n.get("scale",[1,1,1])
        m[:3,:3] = quat_mat(r) @ np.diag(s); m[:3,3] = t
        locals_[i] = m
    invalidate(); init_tails()
    dt = 1/60
    long_chain = name2node["J_Sec_Hair1_05"]   # 7-joint long chain
    root_i = name2node["Root"]
    hips_i = name2node["J_Bip_C_Hips"]
    for f in range(120):
        invalidate(); substepped(dt)   # settle
    def report(tag):
        invalidate()
        w = world(long_chain)[:3,3]
        sp0 = next(sp for sp in springs if sp["states"][0]["node"]==long_chain)
        tw = sp0["states"][0]["cur"]   # verlet tail (world space, no center)
        d = tw-w; d=d/_n(d)
        print(f"  {label} {tag}: hair1_05 dir={np.round(d,3)} L={sp0['states'][0]['L']:.3f}")
    report("before")
    for f in range(frames):
        if mode == "root":
            locals_[root_i][0,3] += step
        else:
            locals_[hips_i][0,3] += step
        invalidate(); substepped(dt)
        if f in (0, 4, 14, frames-1): report(f"frame{f}")
    for f in range(90):
        invalidate(); substepped(dt)
    report("settled")

print("=== A: root move 0.05m/frame (user slow drag, 1m total) ===")
run("root", 0.05, 20, "A")
print("=== B: root move 0.5m/frame (example-scale fast, 10m total) ===")
run("root", 0.5, 20, "B")
print("=== C: hips move 0.5m/frame (mouse.html semantics) ===")
run("hips", 0.5, 20, "C")

print("=== D: root move 0.05m/frame for 20 frames, then release — watch recovery ===")
