import numpy as np

# ===== faithful port of VrmSpringBoneManager math =====
def quat_mul(a, b):
    ax,ay,az,aw = a; bx,by,bz,bw = b
    return np.array([
        aw*bx + ax*bw + ay*bz - az*by,
        aw*by - ax*bz + ay*bw + az*bx,
        aw*bz + ax*by - ay*bx + az*bw,
        aw*bw - ax*bx - ay*by - az*bz])

def quat_inv(q): return np.array([-q[0],-q[1],-q[2],q[3]])

def quat_rot(q, v):
    qx,qy,qz,qw = q; vx,vy,vz = v
    tx = 2*(qy*vz - qz*vy); ty = 2*(qz*vx - qx*vz); tz = 2*(qx*vy - qy*vx)
    return np.array([
        vx + qw*tx + (qy*tz - qz*ty),
        vy + qw*ty + (qz*tx - qx*tz),
        vz + qw*tz + (qx*ty - qy*tx)])

def quat_to_mat(q):
    x,y,z,w = q
    x2,y2,z2 = x+x,y+y,z+z
    xx,xy,xz = x*x2,x*y2,x*z2
    yy,yz,zz = y*y2,y*z2,z*z2
    wx,wy,wz = w*x2,w*y2,w*z2
    m = np.eye(4)
    m[0,0]=1-(yy+zz); m[0,1]=xy-wz;   m[0,2]=xz+wy
    m[1,0]=xy+wz;    m[1,1]=1-(xx+zz); m[1,2]=yz-wx
    m[2,0]=xz-wy;    m[2,1]=yz+wx;    m[2,2]=1-(xx+yy)
    return m
    # NOTE: this is the Kotlin quaternionToMatrix in column-major layout:
    # mat[0]=m00, mat[4]=m01 ... we need to be careful. Kotlin writes:
    # mat[0]=1-(yy+zz); mat[1]=xy+wz; mat[2]=xz-wy   (col 0)
    # mat[4]=xy-wz; mat[5]=1-(xx+zz); mat[6]=yz+wx    (col 1)
    # mat[8]=xz+wy; mat[9]=yz-wx; mat[10]=1-(xx+yy)   (col 2)
    # So in math (row, col) terms: m[0,0]=1-(yy+zz), m[1,0]=xy+wz, m[2,0]=xz-wy,
    # m[0,1]=xy-wz, m[1,1]=1-(xx+zz), m[2,1]=yz+wx, m[0,2]=xz+wy, m[1,2]=yz-wx, m[2,2]=1-(xx+yy)
    # That is the STANDARD rotation matrix R(q) in row-major sense:
    # R = [[1-(yy+zz), xy-wz, xz+wy],[xy+wz, 1-(xx+zz), yz-wx],[xz-wy, yz+wx, 1-(xx+yy)]]
    # numpy m[row,col]: m[0,0]=1-(yy+zz) ✓, m[0,1]=xy-wz ✓, m[0,2]=xz+wy ✓
    # m[1,0]=xy+wz ✓, m[1,1]=1-(xx+zz) ✓, m[1,2]=yz-wx ✓
    # m[2,0]=xz-wy ✓, m[2,1]=yz+wx ✓, m[2,2]=1-(xx+yy) ✓  — matches. Good.

def mat_to_quat(m):
    # m is 4x4 numpy, column-major semantics like Filament: m[r,c]
    m00,m01,m02 = m[0,0],m[0,1],m[0,2]
    m10,m11,m12 = m[1,0],m[1,1],m[1,2]
    m20,m21,m22 = m[2,0],m[2,1],m[2,2]
    tr = m00+m11+m22
    if tr > 0:
        s = np.sqrt(tr+1.0)*2
        w = s*0.25; x = (m21-m12)/s; y = (m02-m20)/s; z = (m10-m01)/s
    elif m00 > m11 and m00 > m22:
        s = np.sqrt(1+m00-m11-m22)*2
        w = (m21-m12)/s; x = s*0.25; y = (m01+m10)/s; z = (m02+m20)/s
    elif m11 > m22:
        s = np.sqrt(1+m11-m00-m22)*2
        w = (m02-m20)/s; x = (m01+m10)/s; y = s*0.25; z = (m12+m21)/s
    else:
        s = np.sqrt(1+m22-m00-m11)*2
        w = (m10-m01)/s; x = (m02+m20)/s; y = (m12+m21)/s; z = s*0.25
    q = np.array([x,y,z,w]); return q/np.linalg.norm(q)

def from_to(f, t):
    d = float(np.dot(f,t))
    if d > 0.999999: return np.array([0,0,0,1.0])
    if d < -0.999999:
        perp = np.array([1.0,0,0])
        if abs(f[0]) > 0.9: perp = np.array([0,1.0,0])
        ax = np.cross(f, perp); l = np.linalg.norm(ax)
        return np.array([ax[0]/l, ax[1]/l, ax[2]/l, 0.0])
    c = np.cross(f,t); w = 1.0+d
    l = np.sqrt(c@c + w*w)
    return np.array([c[0]/l, c[1]/l, c[2]/l, w/l])

def constrain(head, tail, L):
    d = tail-head; dist = np.linalg.norm(d)
    if dist < 1e-8: return head + np.array([0,-L,0])
    return head + d*(L/dist)

# ===== scene: 3-joint chain hanging down, rest local = identity, axis (0,-1,0) =====
# hierarchy: root -> J0 -> J1 -> J2 ; local translations place each 0.1m below parent
L = 0.1
axis = np.array([0.0,-1.0,0.0])
rest_local_quat = np.array([0,0,0,1.0])   # rest local rotation (identity)
params = dict(stiffness=0.46, dragForce=0.4, gravityPower=0.0375, gravityDir=np.array([0,-1,0.0]))

class Joint:
    def __init__(self):
        self.prevTail = None; self.curTail = None

# Filament-like transform store: locals dict, world computed by composition
locals_ = {}   # name -> 4x4
worlds = {}    # cache

def set_local(n, m): locals_[n] = m
def get_world(n, parents):
    m = locals_[n].copy()
    p = parents.get(n)
    if p: m = get_world(p, parents) @ m
    return m

parents = {'J0':'root', 'J1':'J0', 'J2':'J1'}
root_local = np.eye(4)

def init(root_local_m):
    global joints, root_local
    root_local = root_local_m
    locals_['root'] = root_local
    for i,n in enumerate(['J0','J1','J2']):
        lm = np.eye(4); lm[1,3] = -L
        set_local(n, lm)
    locals_['root'] = root_local
    joints = {n: Joint() for n in ['J0','J1','J2']}
    for n in ['J0','J1','J2']:
        w = get_world(n, parents)
        head = w[:3,3]
        wq = mat_to_quat(w)
        dirw = quat_rot(wq, axis)
        tail = head + dirw*L
        joints[n].prevTail = tail.copy(); joints[n].curTail = tail.copy()

def update(dt):
    for n in ['J0','J1','J2']:
        st = joints[n]
        w = get_world(n, parents)
        head = w[:3,3].copy()
        pw = get_world(parents[n], parents) if parents[n] else np.eye(4)
        parentRot = mat_to_quat(pw)
        # verlet
        inertia = (st.curTail - st.prevTail)*(1-params['dragForce'])
        combined = quat_mul(parentRot, rest_local_quat)
        sdir = quat_rot(combined, axis)
        stiffness = sdir*params['stiffness']*dt
        grav = params['gravityDir']*params['gravityPower']*dt
        nxt = st.curTail + inertia + stiffness + grav
        nxt = constrain(head, nxt, L)
        st.prevTail = st.curTail.copy(); st.curTail = nxt.copy()
        # rotation recovery
        cur = nxt-head; cur = cur/np.linalg.norm(cur)
        rest = quat_rot(combined, axis); rest = rest/np.linalg.norm(rest)
        ft = from_to(rest, cur)
        newWorld = quat_mul(ft, combined)
        newLocal = quat_mul(quat_inv(parentRot), newWorld)
        lm = np.eye(4)
        lm[:3,:3] = quat_to_mat(newLocal)[:3,:3]
        lm[:3,3] = locals_[n][:3,3]
        set_local(n, lm)

# ===== TEST: rest stability =====
init(np.eye(4))
for f in range(120): update(1/60)
w = get_world('J2', parents); tail = joints['J2'].curTail
head0 = get_world('J0', parents)[:3,3]
print("TEST A rest: tail-head =", np.round(tail-head0,4), "(expect ~(0,-0.3,0))")

# ===== TEST B: translate root +X continuously =====
init(np.eye(4))
for f in range(120): update(1/60)  # settle
for f in range(60):
    # drag: root local translation += X
    root_local[0,3] += 0.02
    locals_['root'] = root_local
    update(1/60)
w0 = get_world('J0', parents); h = w0[:3,3]
t = joints['J2'].curTail
d = t - h
d = d/np.linalg.norm(d)
print("TEST B moving +X: (tail-head) normalized =", np.round(d,3))
print("  expected: tilt toward -X (trailing) => x component NEGATIVE")
print("  actual x component:", round(float(d[0]),3))
