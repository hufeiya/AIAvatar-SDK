#!/usr/bin/env node
// 程序化生成团播道具 GLB(零依赖,CC0):microphone.glb(唱歌/说唱幕道具)
// + lightstick.glb(谢幕应援棒)。与 make-phone-glb.js 同一套活模板:
// 米制单位/Y 轴向上/静态网格/PBR 材质/无骨骼无动画。
// 用法: node make-prop-glb.js  →  写出 ./microphone.glb ./lightstick.glb
'use strict';
const fs = require('fs');

// ---- 几何:带面法线的圆柱(侧面 12 段 + 顶底扇形盖) ----
function cylinder(r, h, segments = 12) {
  const half = h / 2;
  const pos = [], nrm = [], idx = [];
  // 侧面
  for (let i = 0; i < segments; i++) {
    const a0 = (i / segments) * Math.PI * 2;
    const a1 = ((i + 1) / segments) * Math.PI * 2;
    const c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
    const base = pos.length / 3;
    // 两底两顶,法线取段中点方向(低模平面着色观感)
    const nx = Math.cos((a0 + a1) / 2), nz = Math.sin((a0 + a1) / 2);
    pos.push(c0 * r, -half, s0 * r, c1 * r, -half, s1 * r, c1 * r, half, s1 * r, c0 * r, half, s0 * r);
    for (let k = 0; k < 4; k++) nrm.push(nx, 0, nz);
    idx.push(base, base + 1, base + 2, base, base + 2, base + 3);
  }
  // 顶/底盖(扇形三角)
  for (const [y, ny] of [[half, 1], [-half, -1]]) {
    const base = pos.length / 3;
    pos.push(0, y, 0);
    nrm.push(0, ny, 0);
    for (let i = 0; i < segments; i++) {
      const a = (i / segments) * Math.PI * 2;
      pos.push(Math.cos(a) * r, y, Math.sin(a) * r);
      nrm.push(0, ny, 0);
    }
    for (let i = 1; i <= segments; i++) {
      const next = i === segments ? 1 : i + 1;
      if (ny > 0) idx.push(base, base + i, base + next);
      else idx.push(base, base + next, base + i);
    }
  }
  return { pos, nrm, idx };
}

function translate(geo, dx, dy, dz) {
  const pos = geo.pos.map((v, i) =>
    i % 3 === 0 ? v + dx : i % 3 === 1 ? v + dy : v + dz);
  return { ...geo, pos };
}

// ---- 麦克风:手柄(竖直)+ 颈环 + 网口头,总高 ~12cm,原点在手柄中心 ----
const MIC_HANDLE = cylinder(0.011, 0.085);
const MIC_RING = translate(cylinder(0.013, 0.008), 0, 0.0465, 0);
const MIC_HEAD = translate(cylinder(0.024, 0.032), 0, 0.0665, 0);

// ---- 应援棒:棒身 + 发光棒头,总高 ~16cm,原点在手柄中心 ----
const STICK_BODY = cylinder(0.009, 0.115);
const STICK_TIP = translate(cylinder(0.014, 0.045), 0, 0.08, 0);

// ---- 组装 glTF JSON + BIN(与 make-phone-glb.js 同一套) ----
function buildGlb(name, parts, materials) {
  const binParts = [], bufferViews = [], accessors = [];
  function addView(typedArr, target) {
    const buf = Buffer.from(typedArr.buffer, typedArr.byteOffset, typedArr.byteLength);
    const pad = (4 - (buf.length % 4)) % 4;
    const offset = binParts.reduce((s, b) => s + b.length, 0);
    binParts.push(buf, Buffer.alloc(pad));
    bufferViews.push({ buffer: 0, byteOffset: offset, byteLength: buf.length, target });
    return bufferViews.length - 1;
  }
  function addAccessor(viewIdx, componentType, count, type, minMax) {
    accessors.push({ bufferView: viewIdx, componentType, count, type, ...(minMax ? { min: minMax.min, max: minMax.max } : {}) });
    return accessors.length - 1;
  }
  function accVec3(arr) {
    const f = new Float32Array(arr);
    const min = [Infinity, Infinity, Infinity], max = [-Infinity, -Infinity, -Infinity];
    for (let i = 0; i < f.length; i += 3) for (let k = 0; k < 3; k++) {
      min[k] = Math.min(min[k], f[i + k]); max[k] = Math.max(max[k], f[i + k]);
    }
    return addAccessor(addView(f, 34962), 5126, f.length / 3, 'VEC3', { min, max });
  }
  function accNrm(arr) {
    const f = new Float32Array(arr);
    return addAccessor(addView(f, 34962), 5126, f.length / 3, 'VEC3');
  }
  function accIdx(arr) {
    const u = new Uint16Array(arr);
    return addAccessor(addView(u, 34963), 5123, u.length, 'SCALAR');
  }

  const prims = parts.map(p => ({
    attributes: { POSITION: accVec3(p.geo.pos), NORMAL: accNrm(p.geo.nrm) },
    indices: accIdx(p.geo.idx),
    material: p.mat,
  }));

  const json = {
    asset: { version: '2.0', generator: `AIAvatar-SDK make-prop-glb.js (CC0) ${name}` },
    scene: 0,
    scenes: [{ nodes: [0], name: `${name}_scene` }],
    nodes: [{ mesh: 0, name }],
    meshes: [{ primitives: prims, name: `${name}_mesh` }],
    materials,
    accessors, bufferViews,
    buffers: [{ byteLength: binParts.reduce((s, b) => s + b.length, 0) }],
  };

  let jsonBuf = Buffer.from(JSON.stringify(json), 'utf8');
  if (jsonBuf.length % 4) jsonBuf = Buffer.concat([jsonBuf, Buffer.alloc(4 - (jsonBuf.length % 4), 0x20)]);
  const binBuf = Buffer.concat(binParts);
  if (binBuf.length % 4) binParts.push(Buffer.alloc(4 - (binBuf.length % 4)));
  const total = 12 + 8 + jsonBuf.length + 8 + binBuf.length;
  const out = Buffer.alloc(total);
  out.writeUInt32LE(0x46546C67, 0); out.writeUInt32LE(2, 4); out.writeUInt32LE(total, 8);
  out.writeUInt32LE(jsonBuf.length, 12); out.write('JSON', 16, 'ascii'); jsonBuf.copy(out, 20);
  const binAt = 20 + jsonBuf.length;
  out.writeUInt32LE(binBuf.length, binAt); out.write('BIN\x00', binAt + 4, 'ascii'); binBuf.copy(out, binAt + 8);
  return out;
}

const MIC_MATERIALS = [
  { name: 'mic_body', pbrMetallicRoughness: { baseColorFactor: [0.1, 0.1, 0.12, 1], metallicFactor: 0.7, roughnessFactor: 0.3 } },
  { name: 'mic_ring', pbrMetallicRoughness: { baseColorFactor: [0.75, 0.62, 0.2, 1], metallicFactor: 0.9, roughnessFactor: 0.25 } },
  { name: 'mic_grill', pbrMetallicRoughness: { baseColorFactor: [0.35, 0.35, 0.38, 1], metallicFactor: 0.8, roughnessFactor: 0.5 } },
];

const STICK_MATERIALS = [
  { name: 'stick_body', pbrMetallicRoughness: { baseColorFactor: [0.92, 0.92, 0.95, 1], metallicFactor: 0.05, roughnessFactor: 0.4 } },
  { name: 'stick_glow', pbrMetallicRoughness: { baseColorFactor: [0.4, 0.05, 0.2, 1], metallicFactor: 0, roughnessFactor: 0.2 }, emissiveFactor: [1.0, 0.22, 0.5] },
];

const mic = buildGlb('microphone', [
  { geo: MIC_HANDLE, mat: 0 },
  { geo: MIC_RING, mat: 1 },
  { geo: MIC_HEAD, mat: 2 },
], MIC_MATERIALS);
const stick = buildGlb('lightstick', [
  { geo: STICK_BODY, mat: 0 },
  { geo: STICK_TIP, mat: 1 },
], STICK_MATERIALS);

fs.writeFileSync('microphone.glb', mic);
fs.writeFileSync('lightstick.glb', stick);
const tris = g => g.pos.length / 9;
console.log(`microphone.glb: ${(mic.length / 1024).toFixed(1)}KB, ~${tris(MIC_HANDLE) + tris(MIC_RING) + tris(MIC_HEAD)} triangles`);
console.log(`lightstick.glb: ${(stick.length / 1024).toFixed(1)}KB, ~${tris(STICK_BODY) + tris(STICK_TIP)} triangles`);

// ═══════════════════════════════════════════════════════════════════════
// P1 道具系统批量入库(docs/prop-system-feasibility.md §6.1):程序化生成 20 件
// 日常/饮食/娱乐/情感/古风/场景道具。同活模板:米制/Y-up/自然使用朝向 +Z/
// 静态网格/PBR/无骨骼无动画,原点=握点或几何中心(holdWand 类一律握点)。
// 全部 CC0 自制,过 validate-glb.js 零告警后拷入 app assets。
// ═══════════════════════════════════════════════════════════════════════

function box(w, h, d) {
  const x = w / 2, y = h / 2, z = d / 2;
  const faces = [
    // [四个角, 法线]
    [[-x, -y, z], [x, -y, z], [x, y, z], [-x, y, z], [0, 0, 1]],        // +Z
    [[x, -y, -z], [-x, -y, -z], [-x, y, -z], [x, y, -z], [0, 0, -1]],  // -Z
    [[x, -y, z], [x, -y, -z], [x, y, -z], [x, y, z], [1, 0, 0]],       // +X
    [[-x, -y, -z], [-x, -y, z], [-x, y, z], [-x, y, -z], [-1, 0, 0]],  // -X
    [[-x, y, z], [x, y, z], [x, y, -z], [-x, y, -z], [0, 1, 0]],       // +Y
    [[-x, -y, -z], [x, -y, -z], [x, -y, z], [-x, -y, z], [0, -1, 0]],  // -Y
  ];
  const pos = [], nrm = [], idx = [];
  for (const [a, b, c, d, n] of faces) {
    const base = pos.length / 3;
    pos.push(...a, ...b, ...c, ...d);
    for (let k = 0; k < 4; k++) nrm.push(...n);
    idx.push(base, base + 1, base + 2, base, base + 2, base + 3);
  }
  return { pos, nrm, idx };
}

function sphere(r, latSeg = 8, lonSeg = 12) {
  const pos = [], nrm = [], idx = [];
  for (let i = 0; i <= latSeg; i++) {
    const phi = (i / latSeg) * Math.PI; // 0=顶
    const y = Math.cos(phi), rr = Math.sin(phi);
    for (let j = 0; j < lonSeg; j++) {
      const a = (j / lonSeg) * Math.PI * 2;
      const nx = rr * Math.cos(a), nz = rr * Math.sin(a);
      pos.push(nx * r, y * r, nz * r);
      nrm.push(nx, y, nz);
    }
  }
  for (let i = 0; i < latSeg; i++) {
    for (let j = 0; j < lonSeg; j++) {
      const a = i * lonSeg + j, b = i * lonSeg + (j + 1) % lonSeg;
      const c = (i + 1) * lonSeg + j, d = (i + 1) * lonSeg + (j + 1) % lonSeg;
      idx.push(a, c, b, b, c, d);
    }
  }
  return { pos, nrm, idx };
}

// 锥台(rTop→0 即圆锥):与 cylinder 同构,两底半径不同
function frustum(rBottom, rTop, h, segments = 12) {
  const half = h / 2;
  const pos = [], nrm = [], idx = [];
  for (let i = 0; i < segments; i++) {
    const a0 = (i / segments) * Math.PI * 2;
    const a1 = ((i + 1) / segments) * Math.PI * 2;
    const c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
    const base = pos.length / 3;
    const nx = Math.cos((a0 + a1) / 2), nz = Math.sin((a0 + a1) / 2);
    pos.push(c0 * rBottom, -half, s0 * rBottom, c1 * rBottom, -half, s1 * rBottom,
      c1 * rTop, half, s1 * rTop, c0 * rTop, half, s0 * rTop);
    for (let k = 0; k < 4; k++) nrm.push(nx, 0, nz);
    idx.push(base, base + 1, base + 2, base, base + 2, base + 3);
  }
  for (const [y, ny, r] of [[half, 1, rTop], [-half, -1, rBottom]]) {
    if (r < 1e-4) continue; // 圆锥尖无盖
    const base = pos.length / 3;
    pos.push(0, y, 0);
    nrm.push(0, ny, 0);
    for (let i = 0; i < segments; i++) {
      const a = (i / segments) * Math.PI * 2;
      pos.push(Math.cos(a) * r, y, Math.sin(a) * r);
      nrm.push(0, ny, 0);
    }
    for (let i = 1; i <= segments; i++) {
      const next = i === segments ? 1 : i + 1;
      if (ny > 0) idx.push(base, base + i, base + next);
      else idx.push(base, base + next, base + i);
    }
  }
  return { pos, nrm, idx };
}

function rotateX(geo, deg) {
  const t = deg * Math.PI / 180, c = Math.cos(t), s = Math.sin(t);
  const f = p => [p[0], p[1] * c - p[2] * s, p[1] * s + p[2] * c];
  return remap(geo, f);
}
function rotateZ(geo, deg) {
  const t = deg * Math.PI / 180, c = Math.cos(t), s = Math.sin(t);
  const f = p => [p[0] * c - p[1] * s, p[0] * s + p[1] * c, p[2]];
  return remap(geo, f);
}
function remap(geo, f) {
  const pos = [], nrm = [];
  for (let i = 0; i < geo.pos.length; i += 3) {
    const [x, y, z] = f([geo.pos[i], geo.pos[i + 1], geo.pos[i + 2]]);
    pos.push(x, y, z);
  }
  for (let i = 0; i < geo.nrm.length; i += 3) {
    const [x, y, z] = f([geo.nrm[i], geo.nrm[i + 1], geo.nrm[i + 2]]);
    nrm.push(x, y, z);
  }
  return { pos, nrm, idx: geo.idx };
}

// ── 20 件 P1 道具 ────────────────────────────────────────────────────────
function mat(name, color, opts = {}) {
  return {
    name,
    pbrMetallicRoughness: {
      baseColorFactor: color,
      metallicFactor: opts.metallic ?? 0.1,
      roughnessFactor: opts.roughness ?? 0.5,
    },
    ...(opts.emissive ? { emissiveFactor: opts.emissive } : {}),
  };
}

const P1_PROPS = [];

// 1. book 单本书:封面 + 内页,捧读开本 ~13×20cm
P1_PROPS.push({
  name: 'book',
  materials: [
    mat('book_cover', [0.16, 0.35, 0.42, 1], { roughness: 0.6 }),
    mat('book_pages', [0.93, 0.91, 0.86, 1], { roughness: 0.8 }),
  ],
  parts: [
    { geo: box(0.134, 0.024, 0.196), mat: 0 },
    { geo: translate(box(0.128, 0.019, 0.19), 0, 0, -0.004), mat: 1 },
  ],
});

// 2. pen 钢笔:笔杆 + 金环 + 笔尖段,总长 14cm,原点=杆中心
P1_PROPS.push({
  name: 'pen',
  materials: [
    mat('pen_body', [0.09, 0.09, 0.11, 1], { metallic: 0.4, roughness: 0.25 }),
    mat('pen_trim', [0.78, 0.64, 0.22, 1], { metallic: 0.9, roughness: 0.25 }),
  ],
  parts: [
    { geo: cylinder(0.005, 0.115), mat: 0 },
    { geo: translate(cylinder(0.0055, 0.008), 0, 0.05, 0), mat: 1 },
    { geo: translate(frustum(0.005, 0.0035, 0.022), 0, -0.0685, 0), mat: 1 },
  ],
});

// 3. mug 马克杯:杯身 + 咖啡面 + 三段式把手
P1_PROPS.push({
  name: 'mug',
  materials: [
    mat('mug_body', [0.88, 0.34, 0.30, 1], { roughness: 0.35 }),
    mat('mug_coffee', [0.22, 0.13, 0.08, 1], { roughness: 0.3 }),
  ],
  parts: [
    { geo: cylinder(0.038, 0.095), mat: 0 },
    { geo: translate(cylinder(0.034, 0.002), 0, 0.0485, 0), mat: 1 },
    { geo: translate(box(0.007, 0.055, 0.007), 0.0455, 0, 0), mat: 0 },
    { geo: translate(box(0.016, 0.007, 0.007), 0.038, 0.028, 0), mat: 0 },
    { geo: translate(box(0.016, 0.007, 0.007), 0.038, -0.028, 0), mat: 0 },
  ],
});

// 4. waterBottle 水瓶:锥台瓶身 + 瓶盖,高 22cm
P1_PROPS.push({
  name: 'waterBottle',
  materials: [
    mat('bottle_body', [0.25, 0.42, 0.55, 1], { metallic: 0.55, roughness: 0.3 }),
    mat('bottle_cap', [0.12, 0.12, 0.13, 1], { roughness: 0.5 }),
  ],
  parts: [
    { geo: frustum(0.031, 0.028, 0.19), mat: 0 },
    { geo: translate(cylinder(0.0165, 0.026), 0, 0.108, 0), mat: 1 },
  ],
});

// 5. cannedDrink 易拉罐:罐身 + 顶底盖
P1_PROPS.push({
  name: 'cannedDrink',
  materials: [
    mat('can_body', [0.78, 0.12, 0.14, 1], { metallic: 0.75, roughness: 0.3 }),
    mat('can_lid', [0.82, 0.83, 0.85, 1], { metallic: 0.9, roughness: 0.25 }),
  ],
  parts: [
    { geo: cylinder(0.0325, 0.113), mat: 0 },
    { geo: translate(cylinder(0.0327, 0.003), 0, 0.058, 0), mat: 1 },
    { geo: translate(cylinder(0.0327, 0.003), 0, -0.058, 0), mat: 1 },
  ],
});

// 6. milkTea 奶茶杯:锥台杯 + 蒙顶盖 + 斜插吸管
P1_PROPS.push({
  name: 'milkTea',
  materials: [
    mat('milktea_cup', [0.95, 0.92, 0.85, 1], { roughness: 0.4 }),
    mat('milktea_lid', [0.42, 0.30, 0.22, 1], { roughness: 0.5 }),
    mat('milktea_straw', [0.9, 0.55, 0.65, 1], { roughness: 0.3 }),
  ],
  parts: [
    { geo: frustum(0.036, 0.047, 0.135), mat: 0 },
    { geo: translate(cylinder(0.0405, 0.007), 0, 0.071, 0), mat: 1 },
    { geo: translate(rotateZ(cylinder(0.004, 0.17), 8), 0.02, 0.105, 0), mat: 2 },
  ],
});

// 7. kettle 水壶(茶壶形):壶身 + 壶嘴 + 提梁 + 壶钮
P1_PROPS.push({
  name: 'kettle',
  materials: [
    mat('kettle_body', [0.90, 0.87, 0.78, 1], { roughness: 0.45 }),
    mat('kettle_trim', [0.30, 0.24, 0.18, 1], { roughness: 0.55 }),
  ],
  parts: [
    { geo: frustum(0.082, 0.058, 0.095), mat: 0 },
    { geo: translate(cylinder(0.083, 0.008), 0, -0.0515, 0), mat: 1 },
    { geo: translate(rotateZ(frustum(0.011, 0.007, 0.088), -40), 0.079, 0.028, 0), mat: 0 },
    { geo: translate(cylinder(0.012, 0.016), 0, 0.0555, 0), mat: 1 },
    { geo: translate(box(0.009, 0.007, 0.078), 0, 0.072, 0), mat: 1 },
    { geo: translate(box(0.009, 0.026, 0.009), 0, 0.058, 0.036), mat: 1 },
    { geo: translate(box(0.009, 0.026, 0.009), 0, 0.058, -0.036), mat: 1 },
  ],
});

// 8. cup 杯(茶杯):直筒杯身 + 茶水面
P1_PROPS.push({
  name: 'cup',
  materials: [
    mat('cup_body', [0.94, 0.93, 0.89, 1], { roughness: 0.3 }),
    mat('cup_tea', [0.72, 0.48, 0.24, 1], { roughness: 0.25 }),
  ],
  parts: [
    { geo: cylinder(0.027, 0.068), mat: 0 },
    { geo: translate(cylinder(0.0235, 0.0015), 0, 0.0345, 0), mat: 1 },
  ],
});

// 9. guitar 吉他(民谣):双圆桶琴身 + 音孔圈 + 琴颈 + 琴头,总长 ~0.9m
P1_PROPS.push({
  name: 'guitar',
  materials: [
    mat('guitar_body', [0.72, 0.45, 0.22, 1], { roughness: 0.4 }),
    mat('guitar_dark', [0.10, 0.07, 0.05, 1], { roughness: 0.5 }),
    mat('guitar_neck', [0.48, 0.32, 0.18, 1], { roughness: 0.45 }),
  ],
  parts: [
    { geo: cylinder(0.148, 0.05), mat: 0 },
    { geo: translate(cylinder(0.105, 0.05), 0, 0.12, 0), mat: 0 },
    { geo: translate(cylinder(0.021, 0.052), 0, 0.03, 0), mat: 1 },
    { geo: translate(box(0.05, 0.008, 0.02), 0, -0.06, 0.028), mat: 1 },
    { geo: translate(box(0.044, 0.44, 0.02), 0, 0.42, 0), mat: 2 },
    { geo: translate(box(0.052, 0.11, 0.022), 0, 0.70, 0), mat: 1 },
  ],
});

// 10. headphones 耳机:拱形头梁(弧线分段) + 两侧耳罩
P1_PROPS.push({
  name: 'headphones',
  materials: [
    mat('hp_band', [0.28, 0.28, 0.32, 1], { roughness: 0.4 }),
    mat('hp_cup', [0.16, 0.16, 0.18, 1], { metallic: 0.35, roughness: 0.35 }),
    mat('hp_pad', [0.12, 0.12, 0.13, 1], { roughness: 0.7 }),
  ],
  parts: (() => {
    const parts = [];
    const R = 0.088;
    for (let i = 0; i < 9; i++) {
      const deg = 12 + i * (156 / 8); // 12°..168° 上半圆(XY 平面)
      const t = deg * Math.PI / 180;
      const seg = rotateZ(box(0.015, 0.03, 0.02), 90 - deg);
      parts.push({ geo: translate(seg, Math.cos(t) * R, Math.sin(t) * R, 0), mat: 0 });
    }
    parts.push({ geo: translate(rotateZ(cylinder(0.036, 0.026), 90), 0.082, 0, 0), mat: 1 });
    parts.push({ geo: translate(rotateZ(cylinder(0.036, 0.026), 90), -0.082, 0, 0), mat: 1 });
    parts.push({ geo: translate(rotateZ(cylinder(0.042, 0.012), 90), 0.076, 0, 0), mat: 2 });
    parts.push({ geo: translate(rotateZ(cylinder(0.042, 0.012), 90), -0.076, 0, 0), mat: 2 });
    return parts;
  })(),
});

// 11. flowers 花束:牛皮纸锥套 + 5 支扇形花茎 + 花苞,原点=握点
P1_PROPS.push({
  name: 'flowers',
  materials: [
    mat('flower_wrap', [0.78, 0.66, 0.46, 1], { roughness: 0.7 }),
    mat('flower_stem', [0.30, 0.48, 0.26, 1], { roughness: 0.6 }),
    mat('flower_bud_a', [0.92, 0.45, 0.60, 1], { roughness: 0.5 }),
    mat('flower_bud_b', [0.96, 0.88, 0.70, 1], { roughness: 0.5 }),
  ],
  parts: (() => {
    const parts = [{ geo: frustum(0.016, 0.045, 0.13), mat: 0 }];
    const stems = [
      { dx: 0.000, dz: 0.000, rz: 0, bud: 2 },
      { dx: 0.045, dz: 0.000, rz: 18, bud: 3 },
      { dx: -0.045, dz: 0.000, rz: -18, bud: 2 },
      { dx: 0.022, dz: 0.040, rz: 9, bud: 3 },
      { dx: -0.022, dz: -0.040, rz: -9, bud: 2 },
    ];
    for (const s of stems) {
      parts.push({ geo: translate(rotateZ(cylinder(0.0022, 0.14), s.rz), s.dx * 0.5, 0.185, s.dz * 0.5), mat: 1 });
      parts.push({ geo: translate(sphere(0.018, 6, 8), s.dx, 0.255, s.dz), mat: s.bud });
    }
    return parts;
  })(),
});

// 12. giftBox 礼物盒:盒 + 盖 + 十字缎带 + 蝴蝶结结点
P1_PROPS.push({
  name: 'giftBox',
  materials: [
    mat('gift_box', [0.80, 0.20, 0.24, 1], { roughness: 0.45 }),
    mat('gift_ribbon', [0.95, 0.82, 0.30, 1], { roughness: 0.35 }),
  ],
  parts: [
    { geo: box(0.16, 0.105, 0.16), mat: 0 },
    { geo: translate(box(0.168, 0.02, 0.168), 0, 0.0625, 0), mat: 0 },
    { geo: box(0.02, 0.11, 0.162), mat: 1 },
    { geo: box(0.162, 0.11, 0.02), mat: 1 },
    { geo: translate(box(0.052, 0.02, 0.052), 0, 0.083, 0), mat: 1 },
  ],
});

// 13. sparkler 仙女棒:钢棒 + 发光星焰(锥),原点=握点(棒底)
P1_PROPS.push({
  name: 'sparkler',
  materials: [
    mat('sparkler_rod', [0.72, 0.73, 0.75, 1], { metallic: 0.8, roughness: 0.3 }),
    mat('sparkler_flame', [1.0, 0.85, 0.45, 1], { roughness: 0.2, emissive: [1.0, 0.78, 0.30] }),
  ],
  parts: [
    { geo: translate(cylinder(0.0026, 0.21), 0, 0.105, 0), mat: 0 },
    { geo: translate(frustum(0.011, 0.001, 0.05), 0, 0.235, 0), mat: 1 },
  ],
});

// 14. balloon 气球:珠光气球 + 结扣 + 拉线,原点=握点(线底)
P1_PROPS.push({
  name: 'balloon',
  materials: [
    mat('balloon_skin', [0.91, 0.28, 0.42, 1], { roughness: 0.15, metallic: 0.15 }),
    mat('balloon_string', [0.85, 0.85, 0.85, 1], { roughness: 0.6 }),
  ],
  parts: [
    { geo: translate(sphere(0.105, 10, 14), 0, 0.315, 0), mat: 0 },
    { geo: translate(frustum(0.013, 0.006, 0.016), 0, 0.203, 0), mat: 0 },
    { geo: translate(cylinder(0.0011, 0.19), 0, 0.1, 0), mat: 1 },
  ],
});

// 15. umbrella 雨伞:伞面(锥台) + 伞杆 + 直角柄,原点=握点
P1_PROPS.push({
  name: 'umbrella',
  materials: [
    mat('umbrella_canopy', [0.22, 0.36, 0.58, 1], { roughness: 0.4 }),
    mat('umbrella_shaft', [0.35, 0.36, 0.38, 1], { metallic: 0.7, roughness: 0.3 }),
    mat('umbrella_handle', [0.42, 0.28, 0.16, 1], { roughness: 0.5 }),
  ],
  parts: [
    { geo: translate(frustum(0.44, 0.024, 0.16, 10), 0, 0.47, 0), mat: 0 },
    { geo: translate(cylinder(0.006, 0.03), 0, 0.565, 0), mat: 1 },
    { geo: translate(cylinder(0.0075, 0.62), 0, 0.13, 0), mat: 1 },
    { geo: translate(rotateZ(cylinder(0.0105, 0.085), 90), 0.032, -0.188, 0), mat: 2 },
  ],
});

// 16. staff 法杖:杖身 + 握环 + 顶环 + 蓝辉法球,原点=握点(杖长 0.88)
P1_PROPS.push({
  name: 'staff',
  materials: [
    mat('staff_shaft', [0.32, 0.22, 0.14, 1], { roughness: 0.5 }),
    mat('staff_trim', [0.80, 0.67, 0.25, 1], { metallic: 0.9, roughness: 0.25 }),
    mat('staff_orb', [0.35, 0.75, 0.95, 1], { roughness: 0.1, emissive: [0.25, 0.65, 1.0] }),
  ],
  parts: [
    { geo: translate(cylinder(0.0105, 0.8), 0, 0.15, 0), mat: 0 },
    { geo: translate(cylinder(0.0135, 0.028), 0, 0, 0), mat: 1 },
    { geo: translate(cylinder(0.0155, 0.02), 0, 0.56, 0), mat: 1 },
    { geo: translate(sphere(0.027, 8, 10), 0, 0.605, 0), mat: 2 },
  ],
});

// 17. yogaMat 瑜伽垫(卷):横放卷筒 + 束带,世界坐标摆件(P1 §6.2)
P1_PROPS.push({
  name: 'yogaMat',
  materials: [
    mat('mat_roll', [0.20, 0.55, 0.50, 1], { roughness: 0.7 }),
    mat('mat_strap', [0.75, 0.62, 0.28, 1], { roughness: 0.5 }),
  ],
  parts: [
    { geo: rotateX(cylinder(0.068, 0.60), 90), mat: 0 },
    { geo: translate(rotateX(cylinder(0.072, 0.035), 90), 0, 0, 0.20), mat: 1 },
  ],
});

// 18. teaSet 茶具组:木托盘 + 小茶壶 + 双杯,世界坐标摆件(P1 §6.2)
P1_PROPS.push({
  name: 'teaSet',
  materials: [
    mat('tray_wood', [0.45, 0.30, 0.18, 1], { roughness: 0.6 }),
    mat('teapot_celadon', [0.72, 0.84, 0.78, 1], { roughness: 0.3 }),
    mat('teapot_lid', [0.55, 0.70, 0.63, 1], { roughness: 0.3 }),
  ],
  parts: [
    { geo: box(0.30, 0.016, 0.20), mat: 0 },
    { geo: translate(frustum(0.048, 0.034, 0.062), -0.07, 0.047, 0), mat: 1 },
    { geo: translate(frustum(0.008, 0.005, 0.055), -0.118, 0.052, 0), mat: 1 },
    { geo: translate(sphere(0.009, 5, 8), -0.07, 0.092, 0), mat: 2 },
    { geo: translate(cylinder(0.017, 0.042), 0.055, 0.037, 0.05), mat: 2 },
    { geo: translate(cylinder(0.017, 0.042), 0.055, 0.037, -0.05), mat: 2 },
  ],
});

// 19. tablet 平板:石墨机身 + 亮屏(朝 +Z,手机同款朝向基准)
P1_PROPS.push({
  name: 'tablet',
  materials: [
    mat('tablet_body', [0.16, 0.17, 0.19, 1], { metallic: 0.55, roughness: 0.3 }),
    mat('tablet_screen', [0.25, 0.45, 0.80, 1], { roughness: 0.15, emissive: [0.14, 0.30, 0.62] }),
  ],
  parts: [
    { geo: box(0.168, 0.0072, 0.248), mat: 0 },
    { geo: translate(box(0.156, 0.002, 0.23), 0, 0, 0.003), mat: 1 },
  ],
});

// 20. gamepad 手柄:横梁机身 + 双握把 + 十字键 + 四键 + 双摇杆
P1_PROPS.push({
  name: 'gamepad',
  materials: [
    mat('pad_body', [0.15, 0.15, 0.17, 1], { roughness: 0.45 }),
    mat('pad_button', [0.85, 0.30, 0.30, 1], { roughness: 0.3 }),
    mat('pad_stick', [0.25, 0.25, 0.28, 1], { roughness: 0.35 }),
  ],
  parts: [
    { geo: box(0.128, 0.026, 0.052), mat: 0 },
    { geo: translate(rotateX(cylinder(0.019, 0.085), 22), 0.052, -0.03, -0.006), mat: 0 },
    { geo: translate(rotateX(cylinder(0.019, 0.085), 22), -0.052, -0.03, -0.006), mat: 0 },
    { geo: translate(box(0.024, 0.006, 0.008), -0.032, 0.016, 0.006), mat: 2 },
    { geo: translate(box(0.008, 0.006, 0.024), -0.032, 0.016, 0.006), mat: 2 },
    { geo: translate(cylinder(0.005, 0.005), 0.028, 0.016, 0.012), mat: 1 },
    { geo: translate(cylinder(0.005, 0.005), 0.037, 0.016, 0.006), mat: 1 },
    { geo: translate(cylinder(0.005, 0.005), 0.028, 0.016, -0.004), mat: 1 },
    { geo: translate(cylinder(0.005, 0.005), 0.033, 0.016, -0.011), mat: 1 },
    { geo: translate(cylinder(0.0075, 0.014), -0.010, 0.018, 0.004), mat: 2 },
    { geo: translate(cylinder(0.0075, 0.014), 0.010, 0.018, 0.004), mat: 2 },
  ],
});

for (const p of P1_PROPS) {
  const glb = buildGlb(p.name, p.parts, p.materials);
  fs.writeFileSync(`${p.name}.glb`, glb);
  console.log(`${p.name}.glb: ${(glb.length / 1024).toFixed(1)}KB, ~${p.parts.reduce((s, q) => s + q.geo.idx.length / 3, 0)} triangles`);
}
