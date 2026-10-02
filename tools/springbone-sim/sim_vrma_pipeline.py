import json, struct, math
import numpy as np
from numpy.linalg import norm

# ============ load 10.vrm ============
path = "/home/neethu/projects/AIAvatar-SDK/app/src/main/assets/vrms/10.vrm"
data = open(path, "rb").read()
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
def quat_mul(a,b):
    ax,ay,az,aw=a; bx,by,bz,bw=b
    return np.array([aw*bx+ax*bw+ay*bz-az*by, aw*by-ax*bz+ay*bw+az*bx, aw*bz+ax*by-ay*bx+az*bw, aw*bw-ax*bx-ay*by-az*bz])
def quat_inv(q): return np.array([-q[0],-q[1],-q[2],q[3]])
def quat_rot(q, v):
    x,y,z,w = q; vx,vy,vz = v
    tx = 2*(y*vz - z*vy); ty = 2*(z*vx - x*vz); tz = 2*(x*vy - y*vx)
    return np.array([vx + w*tx + (y*tz - z*ty), vy + w*ty + (z*tx - x*tz), vz + w*tz + (x*ty - y*tx)])
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
def slerp(q0, q1, t):
    d = float(np.dot(q0,q1))
    b = q1
    if d < 0: d=-d; b=-q1
    if d > 0.9995:
        r = q0 + (b-q0)*t
        return r/norm(r)
    th0 = math.acos(np.clip(d,-1,1)); th = th0*t
    s0 = math.cos(th) - d*math.sin(th)/math.sin(th0); s1 = math.sin(th)/math.sin(th0)
    r = s0*q0 + s1*b
    return r/norm(r)
def interp_quat(times, values, t):
    if len(times)==0: return np.array([0,0,0,1.0])
    if t <= times[0]: return np.array(values[0:4])
    if t >= times[-1]:
        i=(len(times)-1)*4; return np.array(values[i:i+4])
    idx=0
    for i in range(len(times)-1):
        if times[i] <= t < times[i+1]: idx=i; break
    a = (t-times[idx])/(times[idx+1]-times[idx]) if times[idx+1]>times[idx] else 0
    return slerp(np.array(values[idx*4:idx*4+4]), np.array(values[(idx+1)*4:(idx+1)*4+4]), a)
def interp_v3(times, values, t):
    if len(times)==0: return np.zeros(3)
    if t <= times[0]: return np.array(values[0:3])
    if t >= times[-1]:
        i=(len(times)-1)*3; return np.array(values[i:i+3])
    idx=0
    for i in range(len(times)-1):
        if times[i] <= t < times[i+1]: idx=i; break
    a = (t-times[idx])/(times[idx+1]-times[idx]) if times[idx+1]>times[idx] else 0
    return np.array(values[idx*3:idx*3+3]) + (np.array(values[(idx+1)*3:(idx+1)*3+3])-np.array(values[idx*3:idx*3+3]))*a

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

# humanoid bone map of TARGET model (VRMC_vrm)
hum = js["extensions"]["VRMC_vrm"]["humanoid"]["humanBones"]
target_bone_node = {}
for bone, el in hum.items():
    target_bone_node[bone] = el["node"]
def rest_world_quat(ni):
    q = mat_to_quat(locals_[ni]); c = parent[ni]
    while c >= 0:
        q = quat_mul(mat_to_quat(locals_[c]), q); c = parent[c]
    return q
target_rest_local = {b: mat_to_quat(locals_[ni]) for b,ni in target_bone_node.items()}
target_rest_world = {b: rest_world_quat(ni) for b,ni in target_bone_node.items()}

# ============ load VRMA ============
def load_vrma(p):
    data = open(p,"rb").read()
    json_off=None; bin_off=None; json_len=0; bin_len=0
    pos = 12
    while pos < len(data):
        cl, ct = struct.unpack_from("<II", data, pos); pos += 8
        if ct == 0x4E4F534A: json_off=pos; json_len=cl
        elif ct == 0x004E4942: bin_off=pos; bin_len=cl
        pos += cl
    j = json.loads(data[json_off:json_off+json_len].decode("utf-8"))
    binb = data[bin_off:bin_off+bin_len]
    ext = j["extensions"]["VRMC_vrm_animation"]
    node2bone = {}
    for bone, el in ext["humanoid"]["humanBones"].items():
        node2bone[el["node"]] = bone
    # world rot map (VRMA model)
    pmap = {}
    for i,n in enumerate(j["nodes"]):
        for c in n.get("children", []): pmap[c]=i
    local_rots = [np.array(n.get("rotation",[0,0,0,1.0]),dtype=float) for n in j["nodes"]]
    def world_rot(ni):
        q = local_rots[ni].copy(); c = pmap.get(ni)
        while c is not None:
            q = quat_mul(local_rots[c], q); c = pmap.get(c)
        return q
    wrot = {bone: world_rot(ni) for ni,bone in node2bone.items()}
    BONE_PARENT = {"hips":None,"spine":"hips","chest":"spine","upperChest":"chest","neck":"upperChest","head":"neck",
      "leftShoulder":"upperChest","leftUpperArm":"leftShoulder","leftLowerArm":"leftUpperArm","leftHand":"leftLowerArm",
      "rightShoulder":"upperChest","rightUpperArm":"rightShoulder","rightLowerArm":"rightUpperArm","rightHand":"rightLowerArm",
      "leftUpperLeg":"hips","leftLowerLeg":"leftUpperLeg","leftFoot":"leftLowerLeg",
      "rightUpperLeg":"hips","rightLowerLeg":"rightUpperLeg","rightFoot":"rightLowerLeg"}
    def parent_of(bone):
        b = BONE_PARENT.get(bone)
        while b is not None and b not in wrot: b = BONE_PARENT.get(b)
        return b
    # rest hips pos
    hips_node = ext["humanoid"]["humanBones"]["hips"]["node"]
    rp = np.zeros(3); cur = hips_node
    while True:
        rp += np.array(j["nodes"][cur].get("translation",[0,0,0]))
        c = pmap.get(cur)
        if c is None: break
        cur = c
    # accessors
    accs = j["accessors"]; bvs = j["bufferViews"]
    def read_acc(idx):
        a = accs[idx]; bv = bvs[a["bufferView"]]
        es = {"SCALAR":1,"VEC2":2,"VEC3":3,"VEC4":4,"MAT4":16}[a["type"]]
        off = bv.get("byteOffset",0) + a.get("byteOffset",0)
        stride = bv.get("byteStride") or es*4
        cnt = a["count"]
        out = []
        for i in range(cnt):
            b0 = off + i*stride
            out.extend(struct.unpack_from("<"+"f"*es, binb, b0))
        return out
    anim = j["animations"][0]
    tracks = []; dur=0.0
    for ch in anim.get("channels", []):
        tgt = ch.get("target")
        if tgt is None: continue
        ni = tgt.get("node")
        p = tgt.get("path")
        si = ch.get("sampler")
        bone = node2bone.get(ni)
        if bone is None: continue
        smp = anim["samplers"][si]
        times = read_acc(smp["input"]); values = read_acc(smp["output"])
        dur = max(dur, times[-1] if times else 0)
        if p=="rotation":
            bw = wrot.get(bone, np.array([0,0,0,1.0])); bwi = quat_inv(bw)
            pb = parent_of(bone)
            pw = wrot.get(pb, wrot.get("hipsParent", np.array([0,0,0,1.0])))
            vals=[]
            for k in range(len(values)//4):
                raw = np.array(values[k*4:k*4+4])
                nq = quat_mul(quat_mul(pw, raw), bwi)
                vals.extend(nq)
            tracks.append((bone,"rotation",times,vals))
        elif p=="translation" and bone=="hips":
            hp = wrot.get("hipsParent", np.array([0,0,0,1.0]))
            vals=[]
            for k in range(len(values)//3):
                v = np.array(values[k*3:k*3+3])
                vals.extend(quat_rot(hp, v))
            tracks.append(("hips","translation",times,vals))
    return dur, tracks, rp

# ============ spring (current implementation) ============
sb = js["extensions"]["VRMC_springBone"]
colliders = []
for c in sb.get("colliders", []):
    shape = c["shape"]
    if "sphere" in shape:
        colliders.append(dict(node=c["node"], offset=np.array(shape["sphere"]["offset"],dtype=float), radius=shape["sphere"]["radius"], tail=None))
    else:
        cap = shape["capsule"]
        colliders.append(dict(node=c["node"], offset=np.array(cap["offset"],dtype=float), radius=cap["radius"], tail=np.array(cap["tail"],dtype=float)))
groups = [g.get("colliders", []) for g in sb.get("colliderGroups", [])]

def mul_point(m, p): return m[:3,:3] @ p + m[:3,3]
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
def mat4_inv(m): return np.linalg.inv(m)

springs = []
for chain in sb.get("springs", []):
    center_idx = chain.get("center")
    states=[]; prms=[]
    nj = len(chain["joints"])
    for i, j in enumerate(chain["joints"]):
        ni = j["node"]; head_w = world(ni)[:3,3].copy()
        rest_local_q = mat_to_quat(locals_[ni]); wq = mat_to_quat(world(ni))
        if i+1 < nj: nxt = chain["joints"][i+1]["node"]
        elif children_map.get(ni): nxt = children_map[ni][0]
        else: nxt = None
        if nxt is not None:
            cw = world(nxt)[:3,3]; wd = cw-head_w; L=norm(wd)
            ax = quat_rot(quat_inv(wq), wd)/max(L,1e-9)
        else:
            pw = world(parent[ni])[:3,3]
            wd = head_w-pw; wd = wd/norm(wd)
            v = wd*0.07; L=0.07
            ax = quat_rot(quat_inv(wq), wd)
        tw = head_w + quat_rot(wq, ax)*L
        states.append(dict(node=ni, L=L, restL=L, virtual=(nxt is None), ax=ax, restq=rest_local_q,
                           prev=None, cur=None, lastTailNode=tw.copy()))
        prms.append(dict(stiffness=j.get("stiffness",1.0), gravityPower=j.get("gravityPower",0.0),
                         gravityDir=np.array(j.get("gravityDir",[0,-1,0]),dtype=float),
                         dragForce=j.get("dragForce",0.4), hitRadius=j.get("hitRadius",0.0)))
    cg=[]
    for gi in chain.get("colliderGroups", []): cg += groups[gi] if gi<len(groups) else []
    springs.append(dict(states=states, prms=prms, cg=cg, center=center_idx))

def init_tails():
    for sp in springs:
        cwi = mat4_inv(world(sp["center"])) if sp["center"] is not None else np.eye(4)
        for st in sp["states"]:
            ni = st["node"]; w = world(ni)
            tw = w[:3,3] + quat_rot(mat_to_quat(w), st["ax"])*st["L"]
            tc = mul_point(cwi, tw)
            st["prev"]=tc.copy(); st["cur"]=tc.copy()
            st["lastTailNode"] = tw.copy()

def chain_tail_is_virtual(sp):
    # in bind we set L=0.07 fixed for last joints without any child node
    last = sp["states"][-1]
    ni = last["node"]
    nxt = None
    nj = len(sp["states"])
    return abs(last["L"] - 0.07) < 1e-5

def spring_update(dt, use_collision=True, scale_radii=True, tvrm_length=False):
    # three-vrm style per-frame bone length: |tail NODE world (end of last frame) - head world (now)|
    # The tail NODE position is bone-snapped (head + dir * restL), NOT the verlet tail.
    if tvrm_length:
        for sp in springs:
            for st in sp["states"]:
                if st.get("virtual"): continue
                invalidate()
                head_w = world(st["node"])[:3,3]
                L = norm(st["lastTailNode"] - head_w)
                if L > 1e-6: st["L"] = L
    for sp in springs:
        cwid = world(sp["center"]) if sp["center"] is not None else np.eye(4)
        cwi  = mat4_inv(cwid) if sp["center"] is not None else np.eye(4)
        for st, p in zip(sp["states"], sp["prms"]):
            ni = st["node"]; invalidate()
            w = world(ni); head = w[:3,3].copy()
            pi = parent[ni]
            parentRot = mat_to_quat(world(pi)) if pi>=0 else np.array([0,0,0,1.0])
            dragF = 1-p["dragForce"]
            next_c = st["cur"] + (st["cur"]-st["prev"])*dragF
            nxt = mul_point(cwid, next_c)
            combined = quat_mul(parentRot, st["restq"])
            sdir = quat_rot(combined, st["ax"])
            nxt = nxt + sdir*p["stiffness"]*dt + p["gravityDir"]*p["gravityPower"]*dt
            nxt = constrain(head, nxt, st["L"])
            if use_collision:
                ws = norm(w[:3,0])
                for ci in sp["cg"]:
                    col = colliders[ci]; cwn = world(col["node"])
                    cpos = mul_point(cwn, col["offset"])
                    r = (p["hitRadius"] + col["radius"]*(norm(cwn[:3,0]) if scale_radii else 1.0))*ws if scale_radii else (p["hitRadius"]+col["radius"])
                    if col["tail"] is None:
                        dv = nxt-cpos; ds = norm(dv)
                        if ds <= r:
                            nrm = dv/ds if ds>1e-6 else np.array([0,1.0,0])
                            nxt = constrain(head, cpos+nrm*r, st["L"])
                    else:
                        ctail = mul_point(cwn, col["tail"])
                        seg = ctail-cpos; sl=norm(seg)
                        if sl<1e-10:
                            dv = nxt-cpos; ds=norm(dv)
                            if ds<=r:
                                nrm = dv/ds if ds>1e-6 else np.array([0,1.0,0])
                                nxt = constrain(head, cpos+nrm*r, st["L"])
                        else:
                            sd=seg/sl; dot=float(np.dot(nxt-cpos, sd))
                            cp = cpos+sd*min(max(dot,0.0),sl)
                            dv = nxt-cp; ds=norm(dv)
                            if ds<=r:
                                nrm = dv/ds if ds>1e-6 else np.array([0,1.0,0])
                                nxt = constrain(head, cp+nrm*r, st["L"])
            st["prev"]=st["cur"].copy()
            st["cur"]=mul_point(cwi, nxt)
            cur = nxt-head; cur=cur/norm(cur)
            rest = quat_rot(combined, st["ax"]); rest=rest/norm(rest)
            ft = from_to(rest, cur)
            newWorld = quat_mul(ft, combined)
            newLocal = quat_mul(quat_inv(parentRot), newWorld)
            locals_[ni][:3,:3] = quat_mat(newLocal)
            st["lastTailNode"] = head + cur*st["restL"]

def tail_world(sp, st):
    cwid = world(sp["center"]) if sp["center"] is not None else np.eye(4)
    return mul_point(cwid, st["cur"])

def run_vrma(vrma_path, seconds=6.0, use_collision=True, scale_radii=True, verbose=True, tvrm=False, reset_start=True, skip_last=False, substep=True):
    dur, tracks, rest_hips = load_vrma(vrma_path)
    if verbose: print(f"\n=== {vrma_path.split('/')[-1]} dur={dur:.2f}s tracks={len(tracks)} ===")
    # reset model to rest pose
    for i in range(N):
        n = nodes[i]
        m = np.eye(4)
        t = n.get("translation",[0,0,0]); r = n.get("rotation",[0,0,0,1]); s = n.get("scale",[1,1,1])
        m[:3,:3] = quat_mat(r) @ np.diag(s); m[:3,3] = t
        locals_[i] = m
    invalidate(); init_tails()
    hips_node = target_bone_node["hips"]
    rest_hips_local = locals_[hips_node][:3,3].copy()
    anim_hips_y = rest_hips[1] if rest_hips[1] > 0.01 else 1.0
    target_hips_y = world(hips_node)[1,3]
    scale = target_hips_y/anim_hips_y
    def apply_pose(tt):
        for (bone, p, times, values) in tracks:
            ni = target_bone_node.get(bone)
            if ni is None: continue
            if p == "rotation":
                nq = interp_quat(times, values, tt)
                restL = target_rest_local[bone]; restW = target_rest_world[bone]
                final = quat_mul(restL, quat_mul(quat_mul(quat_inv(restW), nq), restW))
                locals_[ni][:3,:3] = quat_mat(final)
            else:
                pos = interp_v3(times, values, tt)
                pos = rest_hips_local + (pos - rest_hips)*scale
                locals_[ni][:3,3] = pos
    import random
    rng = random.Random(42)
    dt = 1/60
    sim_time = 0.0
    # rest directions per joint for deviation measurement
    rest_dirs = {}
    for sp in springs:
        cwid0 = world(sp["center"]) if sp["center"] is not None else np.eye(4)
        for st in sp["states"]:
            invalidate()
            ni = st["node"]; head = world(ni)[:3,3]
            tw = mul_point(cwid0, st["cur"])
            d = tw-head
            L = norm(d)
            if L > 1e-9: rest_dirs[ni] = d/L
    prev_dirs = {}
    dev_exceed = {}   # node -> frames with deviation > 90 deg
    max_dev = {}
    flail_frames = 0; total = 0
    max_ang = 0.0
    flail_report = []
    flail_times = []
    hair_flip = {}
    if skip_last:
        for sp in springs:
            if len(sp["states"]) >= 1:
                sp["states"] = sp["states"][:-1]
                sp["prms"] = sp["prms"][:-1]
        springs[:] = [sp for sp in springs if sp["states"]]
    # apply the first animation pose BEFORE initializing tails (reset-at-start)
    if reset_start:
        invalidate()
        apply_pose(0.0)
        invalidate()
    init_tails()
    steps = int(seconds*60)
    f = 0
    while sim_time < seconds:
        real_dt = rng.uniform(1/120, 0.05)
        dt = min(max(real_dt, 0.001), 0.05)
        sim_time += dt
        t = sim_time % dur
        invalidate()
        apply_pose(t)
        if substep:
            n = max(1, int(math.ceil(dt / (1/120))))
            sdt = dt / n
            for _ in range(n):
                spring_update(sdt, use_collision, scale_radii, tvrm_length=tvrm)
        else:
            spring_update(dt, use_collision, scale_radii, tvrm_length=tvrm)
        f += 1
        # measure ALL spring joints: deviation from own rest direction + angular speed
        if sim_time > 0.5:
            total += 1
            for sp in springs:
                cwid = world(sp["center"]) if sp["center"] is not None else np.eye(4)
                is_hair = "Hair" in str(nodes[sp["states"][0]["node"]].get("name"))
                for st in sp["states"]:
                    invalidate()
                    ni = st["node"]
                    rd = rest_dirs.get(ni)
                    if rd is None: continue
                    head = world(ni)[:3,3]
                    tw = mul_point(cwid, st["cur"])
                    d = tw-head; L = norm(d)
                    if L < 1e-9: continue
                    d = d/L
                    dev = math.degrees(math.acos(np.clip(np.dot(d, rd),-1,1)))
                    if dev > max_dev.get(ni, 0): max_dev[ni] = dev
                    if dev > 90:
                        dev_exceed[ni] = dev_exceed.get(ni,0)+1
                        if is_hair: hair_flip[ni] = hair_flip.get(ni,0)+1
                    pd = prev_dirs.get(ni)
                    if pd is not None:
                        ang = math.degrees(math.acos(np.clip(np.dot(d,pd),-1,1)))
                        max_ang = max(max_ang, ang)
                        if ang > 60:
                            flail_frames += 1
                            flail_times.append(round(sim_time,1))
                            if len(flail_report) < 5: flail_report.append((round(sim_time,1), nodes[ni].get("name"), round(ang)))
                    prev_dirs[ni] = d
    if verbose:
        worst = sorted(max_dev.items(), key=lambda kv:-kv[1])[:8]
        print(f"  frames={total} flail(>60deg/frame)={flail_frames} max_step={max_ang:.0f}deg")
        print("   worst deviation-from-rest:", [(nodes[k].get('name'), round(v)) for k,v in worst])
        if hair_flip: print("   HAIR joints inverted(>90deg) frames:", dict(sorted(((nodes[k].get('name'), v) for k,v in hair_flip.items()), key=lambda kv:-kv[1])[:8]))
        import collections
        dist = collections.Counter(flail_times)
        print("   flail time dist:", dict(sorted(dist.items())[:12]))
        for r in flail_report[:3]: print("   flail e.g.:", r)
    return flail_frames

run_vrma("/home/neethu/projects/AIAvatar-SDK/app/src/main/assets/animations/Doing The Shuffling Dance.vrma")
run_vrma("/home/neethu/projects/AIAvatar-SDK/app/src/main/assets/animations/Afoxe Samba Reggae Dance.vrma")
run_vrma("/home/neethu/projects/AIAvatar-SDK/app/src/main/assets/animations/Being Thankful While Standing.vrma")


print("\n===== device-like variable dt, fixed bone length (current impl) =====")
run_vrma("/home/neethu/projects/AIAvatar-SDK/app/src/main/assets/animations/Doing The Shuffling Dance.vrma")
run_vrma("/home/neethu/projects/AIAvatar-SDK/app/src/main/assets/animations/Afoxe Samba Reggae Dance.vrma")
print("\n===== device-like variable dt, three-vrm per-frame bone length =====")
run_vrma("/home/neethu/projects/AIAvatar-SDK/app/src/main/assets/animations/Doing The Shuffling Dance.vrma", tvrm=True)
run_vrma("/home/neethu/projects/AIAvatar-SDK/app/src/main/assets/animations/Afoxe Samba Reggae Dance.vrma", tvrm=True)


print("\n===== var dt + tvrm length + NO reset at start =====")
run_vrma("/home/neethu/projects/AIAvatar-SDK/app/src/main/assets/animations/Doing The Shuffling Dance.vrma", tvrm=True, reset_start=False)
print("\n===== var dt + tvrm length + reset at start =====")
run_vrma("/home/neethu/projects/AIAvatar-SDK/app/src/main/assets/animations/Doing The Shuffling Dance.vrma", tvrm=True, reset_start=True)
run_vrma("/home/neethu/projects/AIAvatar-SDK/app/src/main/assets/animations/Afoxe Samba Reggae Dance.vrma", tvrm=True, reset_start=True)
run_vrma("/home/neethu/projects/AIAvatar-SDK/app/src/main/assets/animations/Being Thankful While Standing.vrma", tvrm=True, reset_start=True)


print("\n===== FINAL: var dt + tvrm + reset + skip-last-joint =====")
run_vrma("/home/neethu/projects/AIAvatar-SDK/app/src/main/assets/animations/Doing The Shuffling Dance.vrma", tvrm=True, reset_start=True, skip_last=True)
run_vrma("/home/neethu/projects/AIAvatar-SDK/app/src/main/assets/animations/Afoxe Samba Reggae Dance.vrma", tvrm=True, reset_start=True, skip_last=True)
run_vrma("/home/neethu/projects/AIAvatar-SDK/app/src/main/assets/animations/Being Thankful While Standing.vrma", tvrm=True, reset_start=True, skip_last=True)


print("\n===== bone-snapped basis + substep(<=1/120) =====")
run_vrma("/home/neethu/projects/AIAvatar-SDK/app/src/main/assets/animations/Doing The Shuffling Dance.vrma", tvrm=True, reset_start=True, skip_last=True, substep=True)
run_vrma("/home/neethu/projects/AIAvatar-SDK/app/src/main/assets/animations/Afoxe Samba Reggae Dance.vrma", tvrm=True, reset_start=True, skip_last=True, substep=True)
run_vrma("/home/neethu/projects/AIAvatar-SDK/app/src/main/assets/animations/Being Thankful While Standing.vrma", tvrm=True, reset_start=True, skip_last=True, substep=True)
print("\n===== bone-snapped basis + substep(<=1/240) =====")
