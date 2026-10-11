#!/usr/bin/env node
/**
 * VRM/VRMA 资产查看器 — 零依赖本地服务器
 *
 * 数据根目录:../app/src/main/assets(只读渲染 + 可选删除)
 * 删除策略:不直接 rm,而是把文件移入 model-viewer/.trash/(带时间戳前缀),
 *          误删可手动拷回;清空 .trash 才是真正删除。
 *
 * API:
 *   GET  /api/list?dir=<相对路径>          目录内容(子文件夹 + .vrm/.vrma)
 *   GET  /api/search?q=<关键词>&type=all|model|anim   递归搜索全部资产
 *   GET  /api/file?path=<相对路径>         下载 vrm/vrma 原始文件
 *   GET  /api/thumb?path=<相对路径>        提取 VRM 内置缩略图(GLB JSON chunk)
 *   POST /api/delete {path:<相对路径>}     删除(移入 .trash)
 */
'use strict';

const http = require('http');
const fs = require('fs');
const fsp = fs.promises;
const path = require('path');

const PORT = Number(process.env.PORT || 8173);
const ASSETS_ROOT = path.resolve(__dirname, '..', 'app', 'src', 'main', 'assets');
const PUBLIC_DIR = path.join(__dirname, 'public');
const TRASH_DIR = path.join(__dirname, '.trash');

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.map': 'application/json',
};

/** 相对路径 → 绝对路径,拒绝越出 ASSETS_ROOT */
function resolveSafe(rel) {
  const cleaned = String(rel || '').replace(/\\/g, '/').replace(/^\/+/, '');
  const abs = path.resolve(ASSETS_ROOT, cleaned);
  if (abs !== ASSETS_ROOT && !abs.startsWith(ASSETS_ROOT + path.sep)) {
    throw Object.assign(new Error('路径越界: ' + rel), { status: 400 });
  }
  return abs;
}

function isModel(name) { return name.toLowerCase().endsWith('.vrm'); }
function isAnim(name) { return name.toLowerCase().endsWith('.vrma'); }

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(body);
}

function sendError(res, err) {
  const status = err && err.status ? err.status : 500;
  sendJson(res, status, { error: err.message || String(err) });
}

// ---------------------------------------------------------------------------
// GLB 缩略图提取
// ---------------------------------------------------------------------------

const thumbCache = new Map(); // absPath+mtime -> {body:Buffer, mime:string} | null

/**
 * 从 GLB 里抠出缩略图字节。回退链:
 *   1. VRM 1.0 规范声明:extensions.VRMC_vrm.thumbnailImage = imageIndex
 *   2. VRM 0.x 规范声明:extensions.VRM.meta.texture → textures[tex].source
 *   3. 图片名含 thumbnail/icon(部分导出工具不规范声明但图还在)
 *   4. 图片名含 face/head 且不是 normal/roughness 类法线贴图
 * 都没有则返回 null,前端回退为占位图标。
 */
function extractThumbnail(buf) {
  if (buf.length < 20 || buf.readUInt32LE(0) !== 0x46546c67 /* glTF */) return null;
  const jsonLen = buf.readUInt32LE(12);
  if (20 + jsonLen + 8 > buf.length) return null;
  let json;
  try { json = JSON.parse(buf.slice(20, 20 + jsonLen).toString('utf8')); } catch { return null; }

  const images = json.images || [];
  let imageIndex = -1;
  const vrmc = json.extensions && json.extensions.VRMC_vrm;
  if (vrmc && Number.isInteger(vrmc.thumbnailImage)) imageIndex = vrmc.thumbnailImage;
  if (imageIndex < 0) {
    const vrm0 = json.extensions && json.extensions.VRM;
    const texIdx = vrm0 && vrm0.meta && vrm0.meta.texture;
    if (Number.isInteger(texIdx) && json.textures && json.textures[texIdx]) {
      imageIndex = json.textures[texIdx].source;
    }
  }
  if (imageIndex < 0) {
    const badTex = /normal|rough|metal|orm|mask|matcap/i;
    imageIndex = images.findIndex((img) => /thumb|icon/i.test(img.name || ''));
    if (imageIndex < 0) {
      imageIndex = images.findIndex((img) => /face|head/i.test(img.name || '') && !badTex.test(img.name || ''));
    }
  }
  if (imageIndex < 0 || !images[imageIndex]) return null;
  const img = json.images[imageIndex];
  if (!Number.isInteger(img.bufferView) || !json.bufferViews) return null;
  const bv = json.bufferViews[img.bufferView];
  if (!bv) return null;

  const binHeader = 20 + jsonLen;
  if (buf.readUInt32LE(binHeader + 4) !== 0x004e4942 /* BIN */) return null;
  const binDataStart = binHeader + 8;
  const start = binDataStart + (bv.byteOffset || 0);
  const end = start + bv.byteLength;
  if (end > buf.length) return null;
  return { body: buf.slice(start, end), mime: img.mimeType || 'image/png' };
}

async function handleThumb(req, res, rel) {
  const abs = resolveSafe(rel);
  if (!isModel(path.basename(abs))) throw Object.assign(new Error('不是 .vrm 文件'), { status: 400 });
  const st = await fsp.stat(abs);
  const key = abs + ':' + st.mtimeMs;
  if (!thumbCache.has(key)) {
    const buf = await fsp.readFile(abs);
    thumbCache.set(key, extractThumbnail(buf));
    if (thumbCache.size > 32) thumbCache.clear(); // 简单防爆
  }
  const hit = thumbCache.get(key);
  if (!hit) { res.writeHead(404, { 'Content-Type': 'text/plain' }); res.end('no thumbnail'); return; }
  res.writeHead(200, {
    'Content-Type': hit.mime,
    'Content-Length': hit.body.length,
    'Cache-Control': 'no-cache',
  });
  res.end(hit.body);
}

// ---------------------------------------------------------------------------
// 目录列表 / 搜索
// ---------------------------------------------------------------------------

async function countAssets(dirAbs) {
  let models = 0, anims = 0;
  async function walk(dir) {
    let entries;
    try { entries = await fsp.readdir(dir, { withFileTypes: true }); } catch { return; }
    for (const e of entries) {
      if (e.name.startsWith('.')) continue;
      const p = path.join(dir, e.name);
      if (e.isDirectory()) await walk(p);
      else if (isModel(e.name)) models++;
      else if (isAnim(e.name)) anims++;
    }
  }
  await walk(dirAbs);
  return { models, anims };
}

async function handleList(res, rel) {
  const abs = resolveSafe(rel);
  const st = await fsp.stat(abs).catch(() => null);
  if (!st || !st.isDirectory()) throw Object.assign(new Error('目录不存在: ' + rel), { status: 404 });

  const entries = await fsp.readdir(abs, { withFileTypes: true });
  const folders = [];
  const files = [];
  for (const e of entries) {
    if (e.name.startsWith('.')) continue;
    if (e.isDirectory()) {
      folders.push({ name: e.name });
    } else if (isModel(e.name) || isAnim(e.name)) {
      const s = await fsp.stat(path.join(abs, e.name));
      files.push({
        name: e.name,
        type: isModel(e.name) ? 'model' : 'anim',
        size: s.size,
        mtime: s.mtimeMs,
      });
    }
  }
  folders.sort((a, b) => a.name.localeCompare(b.name, 'zh-Hans-CN'));
  files.sort((a, b) => a.name.localeCompare(b.name, 'zh-Hans-CN'));
  for (const f of folders) {
    f.counts = await countAssets(path.join(abs, f.name));
  }
  sendJson(res, 200, {
    dir: rel,
    folders,
    files,
    totals: await countAssets(ASSETS_ROOT),
  });
}

async function handleSearch(res, query, type) {
  const q = String(query || '').trim().toLowerCase();
  if (!q) return sendJson(res, 200, { results: [] });
  const want = type === 'model' ? 'model' : type === 'anim' ? 'anim' : null;
  const results = [];
  const MAX = 800;
  async function walk(rel) {
    if (results.length >= MAX) return;
    const abs = resolveSafe(rel);
    let entries;
    try { entries = await fsp.readdir(abs, { withFileTypes: true }); } catch { return; }
    for (const e of entries) {
      if (results.length >= MAX) return;
      if (e.name.startsWith('.')) continue;
      const childRel = rel ? rel + '/' + e.name : e.name;
      if (e.isDirectory()) { await walk(childRel); continue; }
      const t = isModel(e.name) ? 'model' : isAnim(e.name) ? 'anim' : null;
      if (!t || (want && t !== want)) continue;
      if (e.name.toLowerCase().includes(q)) {
        const s = await fsp.stat(path.join(ASSETS_ROOT, childRel));
        results.push({ name: e.name, rel: childRel, dir: rel, type: t, size: s.size });
      }
    }
  }
  await walk('');
  sendJson(res, 200, { results });
}

// ---------------------------------------------------------------------------
// 删除(移入 .trash)
// ---------------------------------------------------------------------------

async function handleDelete(req, res) {
  let raw = '';
  for await (const chunk of req) raw += chunk;
  let body;
  try { body = JSON.parse(raw || '{}'); } catch { throw Object.assign(new Error('请求体不是 JSON'), { status: 400 }); }
  const abs = resolveSafe(body.path);
  const base = path.basename(abs);
  if (!isModel(base) && !isAnim(base)) {
    throw Object.assign(new Error('只允许删除 .vrm / .vrma 文件'), { status: 400 });
  }
  const st = await fsp.stat(abs).catch(() => null);
  if (!st || !st.isFile()) throw Object.assign(new Error('文件不存在: ' + body.path), { status: 404 });

  await fsp.mkdir(TRASH_DIR, { recursive: true });
  const trashName = Date.now() + '_' + base;
  await fsp.rename(abs, path.join(TRASH_DIR, trashName));
  sendJson(res, 200, { ok: true, movedTo: '.trash/' + trashName });
}

// ---------------------------------------------------------------------------
// 文件流 / 静态资源
// ---------------------------------------------------------------------------

function sendFile(res, abs, download) {
  let st;
  try { st = fs.statSync(abs); } catch {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Not Found');
    return;
  }
  if (!st.isFile()) {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Not Found');
    return;
  }
  const ext = path.extname(abs).toLowerCase();
  const headers = {
    'Content-Type': download ? 'application/octet-stream' : (MIME[ext] || 'application/octet-stream'),
    'Content-Length': st.size,
    'Cache-Control': 'no-cache',
  };
  res.writeHead(200, headers);
  fs.createReadStream(abs).pipe(res);
}

const STATIC_ROOT = path.resolve(PUBLIC_DIR);
function serveStatic(res, urlPath) {
  let rel = decodeURIComponent(urlPath);
  if (rel === '/') rel = '/index.html';
  const abs = path.resolve(STATIC_ROOT, '.' + rel);
  if (abs !== STATIC_ROOT && !abs.startsWith(STATIC_ROOT + path.sep)) {
    return sendJson(res, 400, { error: '路径越界' });
  }
  fs.stat(abs, (err, st) => {
    if (err || !st.isFile()) { res.writeHead(404); res.end('Not Found'); return; }
    sendFile(res, abs, false);
  });
}

// ---------------------------------------------------------------------------

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const pathname = url.pathname;
  try {
    if (pathname === '/api/list' && req.method === 'GET') {
      return await handleList(res, url.searchParams.get('dir') || '');
    }
    if (pathname === '/api/search' && req.method === 'GET') {
      return await handleSearch(res, url.searchParams.get('q'), url.searchParams.get('type'));
    }
    if (pathname === '/api/file' && req.method === 'GET') {
      const abs = resolveSafe(url.searchParams.get('path'));
      const base = path.basename(abs);
      if (!isModel(base) && !isAnim(base)) {
        throw Object.assign(new Error('只允许读取 .vrm / .vrma 文件'), { status: 400 });
      }
      return sendFile(res, abs, true);
    }
    if (pathname === '/api/thumb' && req.method === 'GET') {
      return await handleThumb(req, res, url.searchParams.get('path'));
    }
    if (pathname === '/api/delete' && req.method === 'POST') {
      return await handleDelete(req, res);
    }
    if (pathname.startsWith('/api/')) {
      return sendJson(res, 404, { error: '未知 API: ' + pathname });
    }
    return serveStatic(res, pathname);
  } catch (err) {
    return sendError(res, err);
  }
});

if (!fs.existsSync(ASSETS_ROOT)) {
  console.error('[model-viewer] 找不到 assets 目录: ' + ASSETS_ROOT);
  process.exit(1);
}

server.listen(PORT, '127.0.0.1', () => {
  console.log('[model-viewer] http://127.0.0.1:' + PORT);
  console.log('[model-viewer] assets: ' + ASSETS_ROOT);
  console.log('[model-viewer] trash : ' + TRASH_DIR);
});
