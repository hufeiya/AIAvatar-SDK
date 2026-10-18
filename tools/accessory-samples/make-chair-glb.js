#!/usr/bin/env node
// 程序化生成辩论座椅 GLB(零依赖):seat+backrest+4 legs,坐面高 0.55m,
// 原点=椅脚落地中心,朝向 +Z(与舞台 yaw 0=面向镜头同约定)。
// 用法: node make-chair-glb.js [输出路径]
'use strict';
const fs = require('fs');

function box(w, h, d) {
  const x0 = -w / 2, x1 = w / 2, y0 = -h / 2, y1 = h / 2, z0 = -d / 2, z1 = d / 2;
  // 8 顶点
  const v = [
    [x0, y0, z0], [x1, y0, z0], [x1, y1, z0], [x0, y1, z0],
    [x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1],
  ];
  const faces = [
    { q: [4, 5, 6, 7], n: [0, 0, 1] },   // +Z
    { q: [1, 0, 3, 2], n: [0, 0, -1] },  // -Z
    { q: [5, 1, 2, 6], n: [1, 0, 0] },   // +X
    { q: [0, 4, 7, 3], n: [-1, 0, 0] },  // -X
    { q: [3, 7, 6, 2], n: [0, 1, 0] },   // +Y
    { q: [0, 1, 5, 4], n: [0, -1, 0] },  // -Y
  ];
  const pos = [], nrm = [], idx = [];
  for (const f of faces) {
    const base = pos.length / 3;
    for (const i of f.q) { pos.push(...v[i]); nrm.push(...f.n); }
    idx.push(base, base + 1, base + 2, base, base + 2, base + 3);
  }
  return { pos, nrm, idx };
}

function cylinder(r, h, segments = 10) {
  const half = h / 2;
  const pos = [], nrm = [], idx = [];
  for (let i = 0; i < segments; i++) {
    const a0 = (i / segments) * Math.PI * 2;
    const a1 = ((i + 1) / segments) * Math.PI * 2;
    const c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
    const base = pos.length / 3;
    const nx = Math.cos((a0 + a1) / 2), nz = Math.sin((a0 + a1) / 2);
    pos.push(c0 * r, -half, s0 * r, c1 * r, -half, s1 * r, c1 * r, half, s1 * r, c0 * r, half, s0 * r);
    for (let k = 0; k < 4; k++) nrm.push(nx, 0, nz);
    idx.push(base, base + 1, base + 2, base, base + 2, base + 3);
  }
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
  const pos = geo.pos.map((v, i) => (i % 3 === 0 ? v + dx : i % 3 === 1 ? v + dy : v + dz));
  return { ...geo, pos };
}

// ---- 尺寸:坐面顶 0.55m,坐深 0.5m,椅背到 1.15m;总计 ~2.6k 三角 ----
const SEAT_TOP = 0.55;
const parts = [
  { geo: translate(box(0.52, 0.06, 0.50), 0, SEAT_TOP - 0.03, 0.02), mat: 0 },            // 坐面
  { geo: translate(box(0.52, 0.55, 0.06), 0, SEAT_TOP + 0.275, -0.24), mat: 0 },          // 椅背
  { geo: translate(cylinder(0.022, SEAT_TOP - 0.06), -0.21, (SEAT_TOP - 0.06) / 2, 0.19), mat: 1 }, // 4 腿
  { geo: translate(cylinder(0.022, SEAT_TOP - 0.06), 0.21, (SEAT_TOP - 0.06) / 2, 0.19), mat: 1 },
  { geo: translate(cylinder(0.022, SEAT_TOP - 0.06), -0.21, (SEAT_TOP - 0.06) / 2, -0.19), mat: 1 },
  { geo: translate(cylinder(0.022, SEAT_TOP - 0.06), 0.21, (SEAT_TOP - 0.06) / 2, -0.19), mat: 1 },
];

const MATERIALS = [
  { name: 'chair_fabric', pbrMetallicRoughness: { baseColorFactor: [0.22, 0.25, 0.33, 1], metallicFactor: 0.0, roughnessFactor: 0.85 } },
  { name: 'chair_legs', pbrMetallicRoughness: { baseColorFactor: [0.12, 0.12, 0.14, 1], metallicFactor: 0.8, roughnessFactor: 0.35 } },
];

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
    asset: { version: '2.0', generator: `AIAvatar-SDK make-chair-glb.js ${name}` },
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

const outPath = process.argv[2] || 'chair.glb';
const glb = buildGlb('debate_chair', parts, MATERIALS);
fs.writeFileSync(outPath, glb);
const tris = parts.reduce((s, p) => s + p.geo.idx.length / 3, 0);
console.log(`${outPath}: ${(glb.length / 1024).toFixed(1)}KB, ~${tris} triangles, seatTop=${SEAT_TOP}m`);
