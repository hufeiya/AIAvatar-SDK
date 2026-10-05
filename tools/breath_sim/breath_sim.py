#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
呼吸算法 Python 仿真 —— 精确复刻 corelib 的 VrmBreathEngine/BreathWave/GazeMath
（float32 语义），逐帧输出骨骼局部四元数的偏移角序列，检查：
  1. 连续性：偏移是否连续振荡（真机症状=99% 静止+偶发 1-2 帧抽动）
  2. 闪烁：隔帧「写/不写」交替（strip 误判导致偏移被剥掉）
  3. 漂移/复利：偏移逐帧累积（strip 拒绝时 base=上次写入）
  4. 占空比：|offset| 超过感知阈值的帧占比
环境开关：
  - idle_rewrite: 待机动画是否每帧重写该骨骼（Arms Down 实测=有脊柱轨道=True）
  - hz: 渲染刷新率（60/120，HyperOS 动态切换）
用法：python3 breath_sim.py
"""
import math
import numpy as np

f32 = np.float32

# ── GazeMath（精确复刻，float32） ──────────────────────────────────────

def quat_multiply(a, b):
    ax, ay, az, aw = a; bx, by, bz, bw = b
    return (f32(aw*bx + ax*bw + ay*bz - az*by),
            f32(aw*by - ax*bz + ay*bw + az*bx),
            f32(aw*bz + ax*by - ay*bx + az*bw),
            f32(aw*bw - ax*bx - ay*by - az*bz))

def quat_inverse(q):
    return (f32(-q[0]), f32(-q[1]), f32(-q[2]), f32(q[3]))

def quat_angle(a, b):
    # atan2 版（同步 GazeMath 修复）：无小角度死区
    x, y, z, w = quat_multiply(quat_inverse(a), b)
    v = math.sqrt(float(x)**2 + float(y)**2 + float(z)**2)
    return 2.0 * math.atan2(v, abs(float(w)))

IDENTITY_Q = (f32(0), f32(0), f32(0), f32(1))

STRIP_EPS_RAD = 0.01    # VrmLookAtEngine.STRIP_EPS_RAD（0.57°）
WRITE_EPS_RAD = 0.002   # VrmBreathEngine.WRITE_EPS_RAD（0.11°）

def strip_previous_write(current, last_written, last_offset):
    """精确复刻 VrmLookAtEngine.stripPreviousWrite。"""
    if last_written is None or last_offset is None:
        return current, False
    if quat_angle(current, last_written) > STRIP_EPS_RAD:
        return current, False
    base = quat_multiply(quat_inverse(last_offset), current)
    return base, quat_angle(last_offset, IDENTITY_Q) > STRIP_EPS_RAD

# ── BreathWave（精确复刻） ────────────────────────────────────────────

INHALE_FRACTION = 0.4
AMP_SMOOTH_RATE = 2.0

def amplitude(u):
    x = u - math.floor(u)
    if x < INHALE_FRACTION:
        p = x / INHALE_FRACTION
        return (1.0 - math.cos(math.pi * p)) / 2.0  # easeInOutSine（C¹ 连续）
    p = (x - INHALE_FRACTION) / (1.0 - INHALE_FRACTION)
    return (1.0 + math.cos(math.pi * p)) / 2.0

def advance(phase, dt, hz):
    p = phase + dt * hz
    if p >= 1.0:
        p -= 1.0
    return p

def amp_factor(current, target, dt):
    if dt <= 0:
        return current
    k = 1.0 - math.exp(-dt * AMP_SMOOTH_RATE)
    return current + (target - current) * k

# ── 环境模型 ──────────────────────────────────────────────────────────

class Bone:
    """TransformManager 单骨骼：local 4x4（这里只关心旋转+保留平移的语义）。"""
    def __init__(self, rest_quat):
        self.local = rest_quat  # 简化：局部旋转（平移不参与角度计算）
    def get(self):
        return self.local
    def set(self, q):
        self.local = q

class IdleWriter:
    """待机动画：每帧（或按配置）把骨骼重写为固定姿势 B。"""
    def __init__(self, bone, b_quat, rewrite_every_frame=True):
        self.bone = bone
        self.b = b_quat
        self.rewrite = rewrite_every_frame
    def update(self):
        if self.rewrite:
            self.bone.set(self.b)

# ── VrmBreathEngine.applyOffset（修复版：动画重写快照双信号） ──────────

REWRITE_EPS_RAD = 1e-4  # 快照 vs 当前的重写判定阈值（远小于任何实际偏移）

class BreathEngineSim:
    def __init__(self, bone, chest_deg=12.0, rest_hz=0.25,
                 speaking_hz_factor=1.15, speaking_amp=0.6, use_rewrite_signal=True):
        self.bone = bone
        self.chest_deg = chest_deg
        self.rest_hz = rest_hz
        self.speaking_hz_factor = speaking_hz_factor
        self.speaking_amp = speaking_amp
        self.use_rewrite_signal = use_rewrite_signal
        self.phase = 0.0
        self.amp_scale = 1.0
        self.speaking = False
        self.applied_any = False
        self.last_written = None
        self.last_offset = None
        self.pre_anim = None          # 动画分支前的骨骼局部快照
        self.deg_to_rad = math.pi / 180.0

    def capture_pre_animation(self):
        """渲染循环在动画分支之前调用。"""
        self.pre_anim = self.bone.get()

    def apply_offset(self, angle_rad):
        """返回 (action, 本帧写后的实际偏移角 deg)。"""
        cur = self.bone.get()
        # 双信号判定：动画重写过 → base=当前（绝不 strip）；未重写 → 才走 strip
        if self.use_rewrite_signal and self.pre_anim is not None:
            rewritten = quat_angle(cur, self.pre_anim) > REWRITE_EPS_RAD
        else:
            rewritten = False
        if rewritten:
            base, stripped = cur, False
        else:
            base, stripped = strip_previous_write(cur, self.last_written, self.last_offset)
        self.last_written = None
        self.last_offset = None
        if abs(angle_rad) < WRITE_EPS_RAD:
            if stripped:
                self.bone.set(base)
                self.applied_any = False
                return ("strip_write", 0.0)
            return ("skip", _residual(cur, base))
        local_offset = _axis_quat(angle_rad)
        lnew = quat_multiply(local_offset, base)
        self.bone.set(lnew)
        self.last_written = lnew
        self.last_offset = local_offset
        self.applied_any = True
        return ("write", math.degrees(quat_angle(base, lnew)))

    def update(self, dt):
        if not self.enabled:
            return ("disabled", 0.0)
        target = self.speaking_amp if self.speaking else 1.0
        self.amp_scale = amp_factor(self.amp_scale, target, dt)
        hz = self.rest_hz * (self.speaking_hz_factor if self.speaking else 1.0)
        self.phase = advance(self.phase, dt, hz)
        a = amplitude(self.phase) * self.amp_scale
        return self.apply_offset(a * self.chest_deg * self.deg_to_rad)

    enabled = True

# ── 工具 ─────────────────────────────────────────────────────────────

def _axis_quat(angle_rad):
    s = math.sin(angle_rad / 2.0)
    return (f32(s), f32(0.0), f32(0.0), f32(math.cos(angle_rad / 2.0)))

def _residual(cur, base):
    """cur 相对干净 base 的残余角（deg）。"""
    return math.degrees(quat_angle(cur, base))

def sparkline(values, width=120, lo=0.0, hi=None):
    hi = hi or max(values) or 1.0
    chars = " ▁▂▃▄▅▆▇█"
    step = max(1, len(values) // width)
    out = []
    for i in range(0, len(values), step):
        chunk = values[i:i+step]
        v = max(chunk)
        out.append(chars[min(8, int((v - lo) / (hi - lo + 1e-9) * 8))])
    return "".join(out)

# ── 场景运行 ──────────────────────────────────────────────────────────

def run(name, idle_rewrite, hz, frames=3600, chest_deg=12.0, use_rewrite_signal=True, verbose=True):
    bone = Bone(IDENTITY_Q)
    idle = IdleWriter(bone, B_POSE, rewrite_every_frame=idle_rewrite)
    eng = BreathEngineSim(bone, chest_deg=chest_deg, use_rewrite_signal=use_rewrite_signal)
    angles = []       # 每帧渲染时骨骼上实际存在的偏移角（相对 B，deg）
    actions = []
    dt = 1.0 / hz
    for i in range(frames):
        eng.capture_pre_animation()       # 动画分支前拍快照（渲染循环新挂点）
        idle.update()                     # 动画写
        act, deg = eng.update(dt)         # 呼吸后写
        rendered = quat_angle(bone.get(), B_POSE)  # 渲染=本帧结束时的局部 vs 动画姿势
        angles.append(math.degrees(rendered))
        actions.append(act)
    # 分析
    perce = 1.0  # 感知阈值（deg，约 1° 起肉眼可辨为「动」）
    vis = sum(1 for a in angles if a > perce)
    # 闪烁：隔帧「有偏移/无偏移」交替的次数
    flick = sum(1 for i in range(1, len(angles))
                if (angles[i] > perce) != (angles[i-1] > perce))
    max_a = max(angles)
    drift = angles[-1]
    from collections import Counter
    actc = Counter(actions)
    if verbose:
        print(f"\n== {name} ==")
        print(f"  帧数={frames} dt={dt*1000:.1f}ms idle_rewrite={idle_rewrite} hz={hz}")
        print(f"  动作分布: {dict(actc)}")
        print(f"  渲染偏移角: max={max_a:.2f}° 末帧={drift:.2f}° 超感知帧占比={vis/frames*100:.1f}%")
        print(f"  闪烁次数(可见↔不可见切换)={flick}  (连续振荡应≈2×周期数={frames/4:.0f})")
        print(f"  偏移角序列: {sparkline(angles)}")
        # 打印前 40 帧的逐帧角度+动作（找「静止+偶发」模式）
        head = " ".join(f"{a:.1f}" for a in angles[:40])
        print(f"  前40帧渲染角: {head}")
    return angles

# 待机动画写出的 chest 局部姿势（Arms Down 的某个固定四元数——任意非平凡值）
B_POSE = tuple(f32(v) for v in (0.0923, 0.0061, -0.0283, 0.9954))  # 近似直立的非单位扰动→归一
n = math.sqrt(sum(float(x)**2 for x in B_POSE))
B_POSE = tuple(f32(float(x)/n) for x in B_POSE)

if __name__ == "__main__":
    # 修复后的算法（动画重写快照双信号）在全部 regime 下应连续振荡：
    run("[修复] Case A: idle 每帧重写 + 60Hz + chest 12°", idle_rewrite=True, hz=60)
    run("[修复] Case B: idle 不重写 + 60Hz + chest 12°", idle_rewrite=False, hz=60)
    run("[修复] Case A': 120Hz", idle_rewrite=True, hz=120)
    run("[修复] 动态动作（重写值每帧变化）", idle_rewrite=True, hz=60, chest_deg=12.0)
    # 旧算法对照（应复现锁死）：
    run("[旧算法对照] Case A 无快照信号 → 锁死", idle_rewrite=True, hz=60,
        use_rewrite_signal=False)
