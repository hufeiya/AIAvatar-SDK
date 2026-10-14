#!/usr/bin/env node
// 程序化生成低模智能手机 GLB(零依赖,产出本仓库 CC0 示例资产 phone.glb)。
// 同时是「配饰资产规范」的活模板:米制单位/Y 轴向上/正面朝 +Z/静态网格/PBR 材质/无骨骼无动画。
// 用法: node make-phone-glb.js  →  写出 ./phone.glb
'use strict';
const fs = require('fs');

// ---- 几何:带面法线的长方体 ----
function box(w, h, d) {
  const x = w / 2, y = h / 2, z = d / 2;
  const faces = [
    { n: [0, 0, 1],  v: [[-x, -y, z], [x, -y, z], [x, y, z], [-x, y, z]] },   // 前 +Z(屏幕面)
    { n: [0, 0, -1], v: [[x, -y, -z], [-x, -y, -z], [-x, y, -z], [x, y, -z]] }, // 后
    { n: [1, 0, 0],  v: [[x, -y, z], [x, -y, -z], [x, y, -z], [x, y, z]] },     // 右
    { n: [-1, 0, 0], v: [[-x, -y, -z], [-x, -y, z], [-x, y, z], [-x, y, -z]] }, // 左
    { n: [0, 1, 0],  v: [[-x, y, z], [x, y, z], [x, y, -z], [-x, y, -z]] },     // 顶
    { n: [0, -1, 0], v: [[-x, -y, -z], [x, -y, -z], [x, -y, z], [-x, -y, z]] }, // 底
  ];
  const pos = [], nrm = [], idx = [];
  faces.forEach(f => {
    const base = pos.length / 3;
    f.v.forEach(p => pos.push(...p));
    for (let i = 0; i < 4; i++) nrm.push(...f.n);
    idx.push(base, base + 1, base + 2, base, base + 2, base + 3);
  });
  return { pos, nrm, idx };
}

const BODY = box(0.068, 0.142, 0.0078);          // 机身:68×142mm,7.8mm 厚,中心在原点
const SCREEN = box(0.058, 0.126, 0.0016);        // 屏幕:略凸出于前面
SCREEN.pos = SCREEN.pos.map((v, i) => i % 3 === 2 ? v + 0.0038 : v); // 屏幕沿 +Z 前移

const parts = [
  { geo: BODY, mat: 0 },   // 材质0:机身深灰金属
  { geo: SCREEN, mat: 1 }, // 材质1:发光屏
];

// ---- 组装 glTF JSON + BIN ----
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
  const vi = addView(f, 34962);
  return addAccessor(vi, 5126, f.length / 3, 'VEC3', { min, max });
}
function accNrm(arr) {
  const f = new Float32Array(arr);
  const vi = addView(f, 34962);
  return addAccessor(vi, 5126, f.length / 3, 'VEC3');
}
function accIdx(arr) {
  const u = new Uint16Array(arr);
  const vi = addView(u, 34963);
  return addAccessor(vi, 5123, u.length, 'SCALAR');
}

const prims = parts.map(p => ({
  attributes: { POSITION: accVec3(p.geo.pos), NORMAL: accNrm(p.geo.nrm) },
  indices: accIdx(p.geo.idx),
  material: p.mat,
}));

const json = {
  asset: { version: '2.0', generator: 'AIAvatar-SDK make-phone-glb.js (CC0)' },
  scene: 0,
  scenes: [{ nodes: [0], name: 'phone_scene' }],
  nodes: [{ mesh: 0, name: 'phone' }],
  meshes: [{ primitives: prims, name: 'phone_mesh' }],
  materials: [
    { name: 'body', pbrMetallicRoughness: { baseColorFactor: [0.08, 0.08, 0.09, 1], metallicFactor: 0.6, roughnessFactor: 0.35 } },
    { name: 'screen', pbrMetallicRoughness: { baseColorFactor: [0.02, 0.02, 0.03, 1], metallicFactor: 0, roughnessFactor: 0.2 }, emissiveFactor: [0.35, 0.75, 0.9] },
  ],
  accessors, bufferViews,
  buffers: [{ byteLength: binParts.reduce((s, b) => s + b.length, 0) }],
};

// ---- GLB 封装:12B 头 + JSON chunk(空格补齐) + BIN chunk(零补齐) ----
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

fs.writeFileSync('phone.glb', out);
console.log(`phone.glb: ${(total / 1024).toFixed(1)}KB, ${prims.reduce((s, p) => s + json.accessors[p.indices].count / 3, 0)} 三角形`);
