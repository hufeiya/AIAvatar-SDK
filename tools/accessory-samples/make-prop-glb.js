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
