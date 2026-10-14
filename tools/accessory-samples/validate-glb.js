#!/usr/bin/env node
// 零依赖 GLB/glTF 配饰资产校验器：解析 JSON chunk,输出面数/贴图/材质/包围盒,
// 并按本 SDK 配饰规范给出告警(压缩扩展/骨骼动画/尺寸)。
// 用法: node validate-glb.js [目录|文件...]  缺省扫描当前目录 *.glb
'use strict';
const fs = require('fs');
const path = require('path');

function parseGlb(buf) {
  if (buf.length < 20 || buf.toString('ascii', 0, 4) !== 'glTF') return null;
  const jsonLen = buf.readUInt32LE(12);
  const jsonType = buf.toString('ascii', 16, 20);
  if (jsonType !== 'JSON') return null;
  const json = JSON.parse(buf.toString('utf8', 20, 20 + jsonLen));
  return json;
}

function bboxOf(json) {
  let min = [Infinity, Infinity, Infinity], max = [-Infinity, -Infinity, -Infinity];
  const posAcc = new Set();
  (json.meshes || []).forEach(m => m.primitives.forEach(p => {
    if (p.attributes && p.attributes.POSITION != null) posAcc.add(p.attributes.POSITION);
  }));
  posAcc.forEach(i => {
    const a = json.accessors[i];
    if (!a || !a.min || !a.max) return;
    for (let k = 0; k < 3; k++) {
      min[k] = Math.min(min[k], a.min[k]);
      max[k] = Math.max(max[k], a.max[k]);
    }
  });
  if (min[0] === Infinity) return null;
  return { min, max, size: [max[0] - min[0], max[1] - min[1], max[2] - min[2]] };
}

function fmt(n) { return Number.isFinite(n) ? n.toFixed(3) : '?'; }

function report(file) {
  const buf = fs.readFileSync(file);
  const sizeMB = (buf.length / 1048576).toFixed(2);
  const json = buf.toString('ascii', 0, 4) === 'glTF' ? parseGlb(buf)
    : (path.extname(file) === '.gltf' ? JSON.parse(buf.toString('utf8')) : null);
  if (!json) return { file: path.basename(file), err: '不可解析' };
  const bb = bboxOf(json);
  const verts = (json.accessors || []).reduce((s, a) => s + (a.type === 'VEC3' && a.componentType === 5126 ? (a.count || 0) : 0), 0);
  const mats = (json.materials || []).length;
  const tex = (json.images || []).length;
  const extUsed = (json.extensionsUsed || []).join(',');
  const anims = (json.animations || []).length;
  const skins = (json.skins || []).length;
  const warn = [];
  if (extUsed.includes('DRACO')) warn.push('DRACO压缩(gltfio需decoder,真机待验)');
  if (extUsed.includes('KTX2')) warn.push('KTX2纹理(需KTX2扩展)');
  if (extUsed.includes('KHR_materials_unlit')) warn.push('unlit材质(GlbMorphPatcher会剥除)');
  if (anims > 0) warn.push(`含${anims}段动画(配饰应为静态)`);
  if (skins > 0) warn.push(`含${skins}个skinned mesh(配饰应无骨骼)`);
  if (bb && Math.max(...bb.size) > 20) warn.push(`尺寸过大(max=${fmt(Math.max(...bb.size))}单位,需按renderScale校准)`);
  if (buf.length > 10 * 1048576) warn.push('文件>10MB(移动端配饰建议<2MB)');
  if (path.extname(file) === '.gltf') warn.push('外置glTF(gltf+bin+贴图),SDK走ByteArray需GLB打包');
  return {
    file: path.basename(file), sizeMB,
    mesh: (json.meshes || []).length, verts: verts > 1e4 ? (verts / 1000).toFixed(0) + 'k' : verts,
    mats, tex, anims, skins,
    bbox: bb ? `${fmt(bb.size[0])}x${fmt(bb.size[1])}x${fmt(bb.size[2])}` : '?',
    ext: extUsed || '-', warn: warn.join('; ') || '-'
  };
}

const arg = process.argv[2] || '.';
let files = [];
if (fs.statSync(arg).isDirectory()) {
  files = fs.readdirSync(arg).filter(f => /\.(glb|gltf)$/i.test(f)).map(f => path.join(arg, f));
} else files = [arg];

const rows = files.map(report);
const cols = ['file', 'sizeMB', 'mesh', 'verts', 'mats', 'tex', 'anims', 'skins', 'bbox', 'ext', 'warn'];
const widths = cols.map(c => Math.max(c.length, ...rows.map(r => String(r[c] ?? '').length)));
console.log(cols.map((c, i) => c.padEnd(widths[i])).join(' | '));
rows.forEach(r => console.log(cols.map((c, i) => String(r[c] ?? '').padEnd(widths[i])).join(' | ')));
