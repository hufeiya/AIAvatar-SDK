import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { VRMLoaderPlugin, VRMUtils } from '@pixiv/three-vrm';
import { VRMAnimationLoaderPlugin, createVRMAnimationClip } from '@pixiv/three-vrm-animation';

// ---------------------------------------------------------------------------
// 状态
// ---------------------------------------------------------------------------

const state = {
  dir: '',            // 当前目录(相对 assets 根,'' = 根)
  search: '',         // 非空 = 全局搜索模式
  typeFilter: 'all',  // all | model | anim
  list: null,         // 最近一次列表数据
  model: null,        // { vrm, rel, name, mixer, action }
  animRel: null,      // 正在播放的动作路径
  loadingCount: 0,
};

const els = {
  breadcrumb: document.getElementById('breadcrumb'),
  brand: document.getElementById('brand'),
  grid: document.getElementById('grid'),
  gridSummary: document.getElementById('gridSummary'),
  emptyState: document.getElementById('emptyState'),
  emptyBack: document.getElementById('emptyBack'),
  search: document.getElementById('search'),
  typeFilter: document.getElementById('typeFilter'),
  refreshBtn: document.getElementById('refreshBtn'),
  canvasHost: document.getElementById('canvasHost'),
  modelBadge: document.getElementById('modelBadge'),
  animBar: document.getElementById('animBar'),
  animName: document.getElementById('animName'),
  stopAnimBtn: document.getElementById('stopAnimBtn'),
  resetCamBtn: document.getElementById('resetCamBtn'),
  stageHint: document.getElementById('stageHint'),
  loadingOverlay: document.getElementById('loadingOverlay'),
  loadingText: document.getElementById('loadingText'),
  toast: document.getElementById('toast'),
};

const fileUrl = (rel) => '/api/file?path=' + encodeURIComponent(rel);
const thumbUrl = (rel) => '/api/thumb?path=' + encodeURIComponent(rel);

function fmtSize(bytes) {
  if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB';
  if (bytes >= 1024) return (bytes / 1024).toFixed(0) + ' KB';
  return bytes + ' B';
}

let toastTimer = null;
function toast(msg, isError = false) {
  els.toast.textContent = msg;
  els.toast.classList.toggle('error', isError);
  els.toast.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { els.toast.hidden = true; }, isError ? 6000 : 2600);
}

function setLoading(text) {
  if (text == null) {
    state.loadingCount = Math.max(0, state.loadingCount - 1);
    if (state.loadingCount === 0) els.loadingOverlay.hidden = true;
  } else {
    state.loadingCount++;
    els.loadingText.textContent = text;
    els.loadingOverlay.hidden = false;
  }
}

// ---------------------------------------------------------------------------
// 3D 场景
// ---------------------------------------------------------------------------

const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
renderer.outputColorSpace = THREE.SRGBColorSpace;
els.canvasHost.appendChild(renderer.domElement);

const scene = new THREE.Scene();
scene.background = new THREE.Color(0x101015);

const camera = new THREE.PerspectiveCamera(30, 1, 0.1, 50);
const CAM_POS = new THREE.Vector3(0, 1.3, 3.0);
const CAM_TARGET = new THREE.Vector3(0, 0.95, 0);
camera.position.copy(CAM_POS);

const controls = new OrbitControls(camera, renderer.domElement);
controls.target.copy(CAM_TARGET);
controls.enableDamping = true;
controls.minDistance = 0.5;
controls.maxDistance = 12;
controls.maxPolarAngle = Math.PI * 0.95;

scene.add(new THREE.DirectionalLight(0xffffff, Math.PI).translateX(1).translateY(2).translateZ(1));
scene.add(new THREE.HemisphereLight(0xffffff, 0x30304a, 0.6));

const grid = new THREE.GridHelper(6, 12, 0x3d3d4c, 0x26262f);
scene.add(grid);

const clock = new THREE.Clock();

function resize() {
  const w = els.canvasHost.clientWidth || 1;
  const h = els.canvasHost.clientHeight || 1;
  renderer.setSize(w, h);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
}
new ResizeObserver(resize).observe(els.canvasHost);
resize();

renderer.setAnimationLoop(() => {
  const delta = clock.getDelta();
  if (state.model) {
    state.model.mixer?.update(delta);
    state.model.vrm.update(delta);
  }
  controls.update();
  renderer.render(scene, camera);
});

// ---------------------------------------------------------------------------
// 模型 / 动作加载
// ---------------------------------------------------------------------------

const loader = new GLTFLoader();
loader.register((parser) => new VRMLoaderPlugin(parser));
loader.register((parser) => new VRMAnimationLoaderPlugin(parser));

async function loadModel(rel) {
  setLoading('加载模型:' + rel);
  try {
    const gltf = await loader.loadAsync(fileUrl(rel));
    const vrm = gltf.userData.vrm;
    if (!vrm) throw new Error('该文件不是有效的 VRM 模型');
    try { VRMUtils.combineSkeletons(gltf.scene); } catch { /* 旧版本无此 API,忽略 */ }
    VRMUtils.rotateVRM0(vrm); // 0.x 模型转向 +Z,统一面向镜头

    clearModel();
    const mixer = new THREE.AnimationMixer(vrm.scene);
    state.model = { vrm, rel, name: rel.split('/').pop(), mixer, action: null };
    scene.add(vrm.scene);

    els.modelBadge.innerHTML = '';
    const label = document.createElement('span');
    label.append('当前模型: ');
    const b = document.createElement('b');
    b.textContent = state.model.name;
    label.appendChild(b);
    els.modelBadge.appendChild(label);
    els.modelBadge.hidden = false;
    els.stageHint.hidden = true;

    markSelected(rel);
    resize();
  } catch (err) {
    console.error(err);
    toast('模型加载失败:' + (err.message || err), true);
  } finally {
    setLoading(null);
  }
}

function clearModel() {
  if (!state.model) return;
  stopAnimation(false);
  scene.remove(state.model.vrm.scene);
  VRMUtils.deepDispose(state.model.vrm.scene);
  state.model = null;
  els.modelBadge.hidden = true;
  els.stageHint.hidden = false;
}

async function ensureModel() {
  if (state.model) return true;
  let list;
  try { list = await apiList('vrms'); } catch { list = null; }
  const first = list && list.files.find((f) => f.type === 'model');
  if (!first) { toast('assets/vrms 下没有模型,请先在左侧选择一个模型', true); return false; }
  await loadModel('vrms/' + first.name);
  return !!state.model;
}

async function playAnimation(rel) {
  if (!(await ensureModel())) return;
  setLoading('加载动作:' + rel.split('/').pop());
  try {
    const gltf = await loader.loadAsync(fileUrl(rel));
    const animation = gltf.userData.vrmAnimations && gltf.userData.vrmAnimations[0];
    if (!animation) throw new Error('该文件不是有效的 VRMA 动画');

    const { vrm, mixer } = state.model;
    stopAnimation(false);
    const action = mixer.clipAction(createVRMAnimationClip(animation, vrm));
    action.play();
    state.model.action = action;
    state.animRel = rel;

    els.animName.textContent = rel.split('/').pop();
    els.animBar.hidden = false;
    markPlaying(rel);
  } catch (err) {
    console.error(err);
    toast('动作加载失败:' + (err.message || err), true);
  } finally {
    setLoading(null);
  }
}

function stopAnimation(updateUi = true) {
  if (state.model && state.model.action) {
    state.model.action.stop();
    state.model.action = null;
    state.model.vrm.humanoid?.resetNormalizedPose?.();
  }
  state.animRel = null;
  if (updateUi) {
    els.animBar.hidden = true;
    markPlaying(null);
  }
}

// ---------------------------------------------------------------------------
// 列表渲染
// ---------------------------------------------------------------------------

async function apiList(dir) {
  const r = await fetch('/api/list?dir=' + encodeURIComponent(dir));
  const j = await r.json();
  if (!r.ok) throw new Error(j.error || ('HTTP ' + r.status));
  return j;
}

async function refreshList() {
  try {
    if (state.search) {
      const params = new URLSearchParams({ q: state.search });
      if (state.typeFilter !== 'all') params.set('type', state.typeFilter);
      const r = await fetch('/api/search?' + params);
      const j = await r.json();
      if (!r.ok) throw new Error(j.error || ('HTTP ' + r.status));
      state.list = { searchMode: true, results: j.results };
    } else {
      state.list = await apiList(state.dir);
    }
    renderGrid();
    renderBreadcrumb();
  } catch (err) {
    toast('列表加载失败:' + (err.message || err), true);
  }
}

function visibleFiles(files) {
  if (state.typeFilter === 'all') return files;
  return files.filter((f) => f.type === state.typeFilter);
}

function renderGrid() {
  const list = state.list;
  els.grid.textContent = '';
  let folders = [];
  let files = [];
  let summary = '';
  let searchMode = false;

  if (list && list.searchMode) {
    searchMode = true;
    files = list.results;
    summary = '搜索「' + state.search + '」:找到 ' + files.length + ' 条';
  } else if (list) {
    folders = list.folders;
    files = visibleFiles(list.files);
    const t = list.totals;
    summary = (state.dir || 'assets') +
      ' — ' + folders.length + ' 个文件夹 · ' +
      list.files.filter((f) => f.type === 'model').length + ' 个模型 · ' +
      list.files.filter((f) => f.type === 'anim').length + ' 个动作' +
      '   (全库共 ' + t.models + ' 模型 / ' + t.anims + ' 动作)';
  }
  els.gridSummary.textContent = summary;

  const empty = folders.length === 0 && files.length === 0;
  els.emptyState.hidden = !empty;
  els.grid.style.display = empty ? 'none' : '';

  for (const f of folders) {
    const card = makeFolderCard(f, searchMode);
    els.grid.appendChild(card);
  }
  for (const f of files) {
    const card = makeFileCard(f, searchMode);
    els.grid.appendChild(card);
  }
  if (state.model) markSelected(state.model.rel);
  if (state.animRel) markPlaying(state.animRel);
}

function makeFolderCard(f, searchMode) {
  const card = document.createElement('div');
  card.className = 'card';

  const thumb = document.createElement('div');
  thumb.className = 'thumb';
  thumb.textContent = '📁';
  card.appendChild(thumb);

  const badge = document.createElement('span');
  badge.className = 'badge';
  badge.textContent = '文件夹';
  card.appendChild(badge);

  const body = document.createElement('div');
  body.className = 'body';
  const name = document.createElement('div');
  name.className = 'name';
  name.textContent = f.name;
  name.title = f.name;
  const meta = document.createElement('div');
  meta.className = 'meta';
  const count = document.createElement('span');
  count.textContent = f.counts ? '模型 ' + f.counts.models + ' · 动作 ' + f.counts.anims : '';
  meta.appendChild(count);
  body.append(name, meta);
  card.appendChild(body);

  const rel = state.dir ? state.dir + '/' + f.name : f.name;
  card.addEventListener('click', () => {
    clearSearch();
    state.dir = rel;
    refreshList();
  });
  return card;
}

function makeFileCard(f, searchMode) {
  const rel = searchMode ? f.rel : (state.dir ? state.dir + '/' + f.name : f.name);
  const isModel = f.type === 'model';

  const card = document.createElement('div');
  card.className = 'card';
  card.dataset.rel = rel;
  card.dataset.type = f.type;

  const thumb = document.createElement('div');
  thumb.className = 'thumb';
  if (isModel) {
    const img = document.createElement('img');
    img.loading = 'lazy';
    img.alt = f.name;
    img.src = thumbUrl(rel);
    img.onerror = () => { thumb.textContent = '🧍'; };
    thumb.appendChild(img);
  } else {
    thumb.textContent = '🏃';
  }
  card.appendChild(thumb);

  const badge = document.createElement('span');
  badge.className = 'badge';
  badge.textContent = isModel ? '模型' : '动作';
  card.appendChild(badge);

  const del = document.createElement('button');
  del.className = 'del';
  del.title = '删除(移入 .trash,可恢复)';
  del.textContent = '🗑';
  del.addEventListener('click', (e) => { e.stopPropagation(); deleteAsset(rel, f.name); });
  card.appendChild(del);

  const body = document.createElement('div');
  body.className = 'body';
  const name = document.createElement('div');
  name.className = 'name';
  name.textContent = f.name;
  name.title = f.name;
  const meta = document.createElement('div');
  meta.className = 'meta';
  const size = document.createElement('span');
  size.textContent = fmtSize(f.size);
  meta.appendChild(size);
  if (searchMode && f.dir) {
    const p = document.createElement('span');
    p.className = 'path';
    p.textContent = f.dir;
    p.title = f.dir;
    meta.appendChild(p);
  }
  body.append(name, meta);
  card.appendChild(body);

  card.addEventListener('click', () => {
    if (isModel) {
      if (state.model && state.model.rel === rel) return;
      stopAnimation();
      loadModel(rel);
    } else {
      playAnimation(rel);
    }
  });
  return card;
}

function markSelected(rel) {
  for (const card of els.grid.querySelectorAll('.card[data-type="model"]')) {
    card.classList.toggle('selected', card.dataset.rel === rel);
  }
}

function markPlaying(rel) {
  for (const card of els.grid.querySelectorAll('.card[data-type="anim"]')) {
    card.classList.toggle('playing', rel != null && card.dataset.rel === rel);
    const badge = card.querySelector('.badge');
    if (badge) badge.textContent = card.classList.contains('playing') ? '▶ 播放中' : '动作';
  }
}

// ---------------------------------------------------------------------------
// 面包屑
// ---------------------------------------------------------------------------

function clearSearch() {
  state.search = '';
  els.search.value = '';
}

function renderBreadcrumb() {
  const nav = els.breadcrumb;
  nav.textContent = '';

  const rootCrumb = document.createElement('span');
  rootCrumb.className = 'crumb' + (state.dir === '' ? ' current' : '');
  rootCrumb.textContent = '📦 assets';
  rootCrumb.addEventListener('click', () => {
    if (state.dir === '' && !state.search) return;
    clearSearch();
    state.dir = '';
    refreshList();
  });
  nav.appendChild(rootCrumb);

  if (state.search) {
    const sep = document.createElement('span');
    sep.className = 'crumb-sep';
    sep.textContent = '›';
    const crumb = document.createElement('span');
    crumb.className = 'crumb current';
    crumb.textContent = '搜索:「' + state.search + '」';
    nav.append(sep, crumb);
    return;
  }

  const parts = state.dir ? state.dir.split('/') : [];
  let acc = '';
  for (const part of parts) {
    acc = acc ? acc + '/' + part : part;
    const target = acc;
    const sep = document.createElement('span');
    sep.className = 'crumb-sep';
    sep.textContent = '›';
    const crumb = document.createElement('span');
    crumb.className = 'crumb' + (target === state.dir ? ' current' : '');
    crumb.textContent = part;
    crumb.addEventListener('click', () => {
      clearSearch();
      state.dir = target;
      refreshList();
    });
    nav.append(sep, crumb);
  }
}

// ---------------------------------------------------------------------------
// 删除
// ---------------------------------------------------------------------------

async function deleteAsset(rel, name) {
  const kind = rel.toLowerCase().endsWith('.vrm') ? '模型' : '动作';
  const ok = window.confirm(
    '确定删除' + kind + '「' + name + '」?\n' +
    '文件会移入 model-viewer/.trash/(不直接销毁,可手动拷回),\n' +
    '下次构建 APK 后 app 内将不再包含该文件。'
  );
  if (!ok) return;
  try {
    const r = await fetch('/api/delete', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path: rel }),
    });
    const j = await r.json();
    if (!r.ok) throw new Error(j.error || ('HTTP ' + r.status));
    toast('已删除:' + name);
    if (state.animRel === rel) stopAnimation();
    if (state.model && state.model.rel === rel) clearModel();
    refreshList();
  } catch (err) {
    toast('删除失败:' + (err.message || err), true);
  }
}

// ---------------------------------------------------------------------------
// 事件绑定 / 启动
// ---------------------------------------------------------------------------

let searchTimer = null;
els.search.addEventListener('input', () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    state.search = els.search.value.trim();
    refreshList();
  }, 300);
});
els.typeFilter.addEventListener('change', () => {
  state.typeFilter = els.typeFilter.value;
  refreshList();
});
els.refreshBtn.addEventListener('click', () => refreshList());
els.brand.addEventListener('click', () => {
  clearSearch();
  state.dir = '';
  refreshList();
});
els.emptyBack.addEventListener('click', () => {
  state.dir = state.dir.split('/').slice(0, -1).join('/');
  refreshList();
});
els.stopAnimBtn.addEventListener('click', () => stopAnimation());
els.resetCamBtn.addEventListener('click', () => {
  camera.position.copy(CAM_POS);
  controls.target.copy(CAM_TARGET);
});

refreshList();
ensureModel(); // 启动时预加载第一个模型,点动作即可直接播放
