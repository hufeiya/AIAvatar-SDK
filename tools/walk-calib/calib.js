#!/usr/bin/env node
/**
 * walk-calib — VRMA 步态标定工具（docs/locomotion-feasibility.md §4.2/§9）。
 *
 * 用法：node tools/walk-calib/calib.js <一个或多个 .vrma>
 *
 * 原理：原地步态素材的位移要靠 LocomotionController 程序化推 root，速度
 * 必须与素材步频匹配否则滑步。本工具对 VRMA（GLB 容器）的 humanoid 动画
 * 做前向运动学：逐帧算左右脚踝（foot 节点）世界坐标，脚踝世界 Y 的局部
 * 极小值 = 触地事件，相邻异侧触地的水平距离/时间差 = 一步的速度——
 * 平均即 baseSpeed（playbackSpeed=1 时的地面速度）。
 *
 * 输出 JSON：baseSpeed 直接填进 ai_cmd walk_to 的步态常量
 * （AiDebugExecutor.DEFAULT_WALK_BASE_SPEED）。
 */
'use strict';

const fs = require('fs');
const path = require('path');

// ── GLB 解析 ──────────────────────────────────────────────────────────

function parseGlb(buf) {
  if (buf.readUInt32LE(0) !== 0x46546c67) throw new Error('not a GLB (magic)');
  const jsonLen = buf.readUInt32LE(12);
  const json = JSON.parse(buf.slice(20, 20 + jsonLen).toString('utf8'));
  let bin = null;
  const off = 20 + jsonLen;
  if (off + 8 <= buf.length) {
    const binLen = buf.readUInt32LE(off);
    bin = buf.subarray(off + 8, off + 8 + binLen);
  }
  if (!bin) throw new Error('GLB has no BIN chunk');
  return { json, bin };
}

// VRMA 解析器同款假设：componentType 全 5126（float）
function readAccessor(json, bin, index) {
  const acc = json.accessors[index];
  const comps = { SCALAR: 1, VEC2: 2, VEC3: 3, VEC4: 4 }[acc.type];
  const bv = json.bufferViews[acc.bufferView];
  const start = (bv.byteOffset || 0) + (acc.byteOffset || 0);
  const out = new Float32Array(acc.count * comps);
  for (let i = 0; i < acc.count * comps; i++) out[i] = bin.readFloatLE(start + i * 4);
  return { count: acc.count, comps, data: out };
}

// ── 四元数/矩阵（列主序 4x4，与 glTF 一致）───────────────────────────

function quatSlerp(a, b, t) {
  let dot = a[0]*b[0] + a[1]*b[1] + a[2]*b[2] + a[3]*b[3];
  let bb = b;
  if (dot < 0) { dot = -dot; bb = [-b[0], -b[1], -b[2], -b[3]]; }
  if (dot > 0.9995) {
    const r = a.map((v, i) => v + (bb[i] - v) * t);
    const n = Math.hypot(...r);
    return r.map(v => v / n);
  }
  const th0 = Math.acos(Math.min(1, Math.max(-1, dot)));
  const th = th0 * t;
  const s0 = Math.cos(th) - dot * Math.sin(th) / Math.sin(th0);
  const s1 = Math.sin(th) / Math.sin(th0);
  return [
    s0*a[0] + s1*bb[0], s0*a[1] + s1*bb[1], s0*a[2] + s1*bb[2], s0*a[3] + s1*bb[3],
  ];
}

// local = T · R（VRM 骨骼 scale 恒 1，省略 S）
function trsToMat(t, q) {
  const [x, y, z, w] = q;
  const x2 = x+x, y2 = y+y, z2 = z+z;
  const xx = x*x2, xy = x*y2, xz = x*z2, yy = y*y2, yz = y*z2, zz = z*z2;
  const wx = w*x2, wy = w*y2, wz = w*z2;
  // 列主序：col0..col2 = 旋转，col3 = 平移
  return new Float32Array([
    1-(yy+zz), xy+wz, xz-wy, 0,
    xy-wz, 1-(xx+zz), yz+wx, 0,
    xz+wy, yz-wx, 1-(xx+yy), 0,
    t[0], t[1], t[2], 1,
  ]);
}

function mulMat(a, b) { // a · b
  const o = new Float32Array(16);
  for (let c = 0; c < 4; c++) {
    for (let r = 0; r < 4; r++) {
      o[c*4+r] = a[r]*b[c*4] + a[4+r]*b[c*4+1] + a[8+r]*b[c*4+2] + a[12+r]*b[c*4+3];
    }
  }
  return o;
}

const posOf = (m) => [m[12], m[13], m[14]];

// ── 动画重采样 ────────────────────────────────────────────────────────

function buildChannelMap(json, bin) {
  const anim = json.animations[0];
  const channels = {}; // nodeIndex → {rotation: {times, values}, translation: ...}
  for (const ch of anim.channels) {
    const s = anim.samplers[ch.sampler];
    const times = readAccessor(json, bin, s.input);
    const values = readAccessor(json, bin, s.output);
    channels[ch.target.node] = channels[ch.target.node] || {};
    channels[ch.target.node][ch.target.path] = { times: times.data, values: values.data, comps: values.comps };
  }
  return channels;
}

function sampleChannel(ch, t) {
  const { times, values, comps } = ch;
  if (t <= times[0]) return values.slice(0, comps);
  if (t >= times[times.length - 1]) return values.slice((times.length - 1) * comps);
  let i = 0;
  while (i < times.length - 2 && t >= times[i + 1]) i++;
  const a = (t - times[i]) / (times[i + 1] - times[i] || 1);
  const i0 = i * comps, i1 = (i + 1) * comps;
  const out = new Float32Array(comps);
  for (let k = 0; k < comps; k++) out[k] = values[i0 + k] + (values[i1 + k] - values[i0 + k]) * a;
  return out;
}

function sampleQuat(ch, t) {
  const { times, values } = ch;
  if (t <= times[0]) return Array.from(values.slice(0, 4));
  if (t >= times[times.length - 1]) return Array.from(values.slice((times.length - 1) * 4));
  let i = 0;
  while (i < times.length - 2 && t >= times[i + 1]) i++;
  const a = (t - times[i]) / (times[i + 1] - times[i] || 1);
  const i0 = i * 4, i1 = (i + 1) * 4;
  return quatSlerp(
    Array.from(values.slice(i0, i0 + 4)),
    Array.from(values.slice(i1, i1 + 4)),
    a,
  );
}

// ── 标定主体 ──────────────────────────────────────────────────────────

function calibrate(file) {
  const { json, bin } = parseGlb(fs.readFileSync(file));
  const ext = json.extensions && json.extensions.VRMC_vrm_animation;
  if (!ext) throw new Error('no VRMC_vrm_animation extension');
  const humanBones = ext.humanoid.humanBones;
  const need = ['hips', 'leftUpperLeg', 'leftLowerLeg', 'leftFoot', 'rightUpperLeg', 'rightLowerLeg', 'rightFoot'];
  for (const b of need) {
    if (!humanBones[b]) throw new Error(`missing humanoid bone: ${b}`);
  }

  // 节点表：name/translation/rotation/children（rest = T-pose）
  const nodes = json.nodes.map((n) => ({
    translation: n.translation || [0, 0, 0],
    rotation: n.rotation || [0, 0, 0, 1],
    children: n.children || [],
  }));
  const parentOf = new Map();
  nodes.forEach((n, i) => n.children.forEach((c) => parentOf.set(c, i)));

  const channels = buildChannelMap(json, bin);
  const duration = json.animations[0].samplers[0]
    ? json.accessors[json.animations[0].samplers[0].input].max[0]
    : 0;

  // 链：hips → upperLeg → lowerLeg → foot（动画只覆盖 rotation；hips 另有 translation）
  const chainBones = ['hips', 'upper', 'lower', 'foot'];
  const chains = [
    { side: 'L', idx: ['hips', humanBones.leftUpperLeg.node, humanBones.leftLowerLeg.node, humanBones.leftFoot.node] },
    { side: 'R', idx: ['hips', humanBones.rightUpperLeg.node, humanBones.rightLowerLeg.node, humanBones.rightFoot.node] },
  ].map((c) => ({ side: c.side, nodes: c.idx.map((n) => ({ index: n, parent: parentOf.get(n) })) }));

  // 逐帧 FK（120Hz 重采样）
  const dt = 1 / 120;
  const frames = [];
  for (let t = 0; t < duration; t += dt) {
    // 缓存本帧各相关节点的世界矩阵（从根往下）
    const worldCache = new Map();
    const worldOf = (nodeIndex) => {
      if (worldCache.has(nodeIndex)) return worldCache.get(nodeIndex);
      const n = nodes[nodeIndex];
      const ch = channels[nodeIndex];
      const translation = ch && ch.translation
        ? sampleChannel(ch.translation, t)
        : n.translation;
      const rotation = ch && ch.rotation ? sampleQuat(ch.rotation, t) : n.rotation;
      const local = trsToMat(translation, rotation);
      const parent = parentOf.get(nodeIndex);
      const world = parent === undefined ? local : mulMat(worldOf(parent), local);
      worldCache.set(nodeIndex, world);
      return world;
    };
    const feet = chains.map((c) => {
      const m = worldOf(c.nodes[3].index);
      return { side: c.side, pos: posOf(m) };
    });
    frames.push({ t, feet });
  }

  // 触地检测：单脚脚踝世界 Y 的局部极小值（最小间隔 0.12s 抑制抖动）
  const contacts = [];
  for (const side of ['L', 'R']) {
    const ys = frames.map((f) => f.feet.find((x) => x.side === side).pos[1]);
    const minGap = Math.floor(0.12 / dt);
    for (let i = minGap; i < ys.length - minGap; i++) {
      let isMin = true;
      for (let k = -minGap; k <= minGap; k++) if (ys[i + k] < ys[i]) { isMin = false; break; }
      if (isMin) {
        const f = frames[i];
        const p = f.feet.find((x) => x.side === side).pos;
        contacts.push({ t: f.t, side, x: p[0], z: p[2], y: p[1] });
      }
    }
  }
  contacts.sort((a, b) => a.t - b.t);

  // 相邻异侧触地 = 一步；中段（去头尾各 20%）取平均防边缘效应
  const steps = [];
  for (let i = 1; i < contacts.length; i++) {
    const a = contacts[i - 1];
    const b = contacts[i];
    if (a.side === b.side) continue;
    const dist = Math.hypot(b.x - a.x, b.z - a.z);
    const dtStep = b.t - a.t;
    if (dtStep <= 0.05 || dtStep > 2) continue;
    steps.push({ dist, dt: dtStep, speed: dist / dtStep, t: b.t });
  }
  const core = steps.slice(Math.floor(steps.length * 0.2), Math.ceil(steps.length * 0.8));
  const mean = (arr) => arr.reduce((s, v) => s + v, 0) / (arr.length || 1);
  const meanStep = core.length ? mean(core.map((s) => s.speed)) : 0;
  const cycle = core.length ? 2 * mean(core.map((s) => s.dt)) : 0;
  const stride = cycle && cycle > 0 ? meanStep * cycle : 0;

  return {
    file: path.basename(file),
    duration: +duration.toFixed(3),
    contactEvents: contacts.length,
    steps: core.length,
    meanStepTime_s: +mean(core.map((s) => s.dt)).toFixed(3),
    meanStepLength_m: +mean(core.map((s) => s.dist)).toFixed(3),
    cyclePeriod_s: +cycle.toFixed(3),
    strideLength_m: +stride.toFixed(3),
    baseSpeed_mps: +meanStep.toFixed(3),
    suitable: meanStep > 0.15 && core.length >= 4,
  };
}

// ── CLI ───────────────────────────────────────────────────────────────

const files = process.argv.slice(2);
if (!files.length) {
  console.error('usage: node tools/walk-calib/calib.js <one or more .vrma>');
  process.exit(1);
}
const results = [];
for (const f of files) {
  try {
    results.push(calibrate(f));
  } catch (e) {
    results.push({ file: path.basename(f), error: e.message });
  }
}
for (const r of results) console.log(JSON.stringify(r, null, 2));
