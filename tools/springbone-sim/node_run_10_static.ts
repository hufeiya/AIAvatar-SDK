// Ground truth #2: static settled state of 10.vrm under the REAL three-vrm.
// The previous ground truth (node_run_twist.ts) only covered Twist_Sample (no
// center). 10.vrm is all center=Root + 28 colliders + VRoid parameters, and its
// static state is exactly what the user compares against the app screenshot.
//
// Usage: same as node_run_twist.ts (esbuild bundle inside the three-vrm repo).

import * as THREE from 'three';
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js';
import { VRMLoaderPlugin, VRMUtils } from '../packages/three-vrm/src/index';

(globalThis as any).self = globalThis;
(globalThis as any).ProgressEvent = class {
  constructor(public type: string, init?: any) { Object.assign(this, init); }
};
import { readFileSync } from 'fs';
import { fileURLToPath } from 'url';
const _realFetch = globalThis.fetch.bind(globalThis);
(globalThis as any).fetch = async (url: any, init?: any) => {
  const s = (url && typeof url === 'object' && 'url' in url) ? String(url.url) : String(url);
  if (s.startsWith('file://')) {
    return new Response(readFileSync(fileURLToPath(s)), { status: 200 });
  }
  return _realFetch(url, init);
};
(THREE.TextureLoader.prototype as any).load = function (url: string, onLoad?: (t: THREE.Texture) => void) {
  const tex = new THREE.Texture();
  if (onLoad) queueMicrotask(() => onLoad(tex));
  return tex;
};

const MODEL = 'file:///home/neethu/projects/AIAvatar-SDK/app/src/main/assets/vrms/10.vrm';

function loadJson() {
  const buf = readFileSync('/home/neethu/projects/AIAvatar-SDK/app/src/main/assets/vrms/10.vrm');
  const chunkLen = buf.readUInt32LE(12);
  return JSON.parse(buf.slice(20, 20 + chunkLen).toString('utf8'));
}
const gltfJson = loadJson();

async function main() {
  const loader = new GLTFLoader();
  loader.register((parser) => new VRMLoaderPlugin(parser));
  const gltf = await loader.loadAsync(MODEL);
  const vrm = gltf.userData.vrm;
  VRMUtils.removeUnnecessaryVertices(gltf.scene);
  VRMUtils.removeUnnecessaryJoints(gltf.scene);

  const sbm = vrm.springBoneManager;
  console.log('joints:', sbm?.joints.size, 'colliders:', sbm?.colliders.length);
  const centers = new Set([...(sbm?.joints ?? [])].map((j: any) => j.center?.name ?? 'WORLD'));
  console.log('centers:', [...centers]);

  const jsonNodes = gltfJson.nodes;
  const joints = [...(sbm?.joints ?? [])] as any[];

  // dump: per simulated joint — head pos, tail(child) pos, dir, bone len (world, native scale)
  function dump(tag: string) {
    console.log(`== ${tag} ==`);
    const bySpring = new Map<string, any[]>();
    for (const j of joints) {
      const nm = j.bone.name;
      const key = nm.startsWith('J_Sec') ? nm.split('_').slice(0, 3).join('_') : nm;
      if (!bySpring.has(key)) bySpring.set(key, []);
      bySpring.get(key)!.push(j);
    }
    const lines: string[] = [];
    for (const [key, js] of bySpring) {
      const j = js[0];
      j.bone.updateWorldMatrix(true, false);
      if (j.child) j.child.updateWorldMatrix(true, false);
      const hp = new THREE.Vector3().setFromMatrixPosition(j.bone.matrixWorld);
      const tp = j.child
        ? new THREE.Vector3().setFromMatrixPosition(j.child.matrixWorld)
        : hp.clone().add(new THREE.Vector3(0, -0.07, 0));
      const d = tp.clone().sub(hp);
      const len = d.length();
      d.normalize();
      lines.push(`${key} ${nmDir(j)} pos=[${hp.toArray().map(v=>v.toFixed(4))}] tail=[${tp.toArray().map(v=>v.toFixed(4))}] dir=[${d.toArray().map(v=>v.toFixed(4))}] len=${len.toFixed(4)}`);
    }
    for (const l of lines) console.log(l);
  }
  function nmDir(_j: any) { return ''; }

  const dt = 1 / 60;
  for (let i = 0; i < 300; i++) vrm.update(dt);
  dump('native-settled-5s');

  // APP PARITY: transformToUnitCube root scale, springs re-bound after scaling
  const box = new THREE.Box3().setFromObject(vrm.scene);
  const size = new THREE.Vector3(); box.getSize(size);
  const s = 2.0 / Math.max(size.x, size.y, size.z);
  vrm.scene.scale.setScalar(s);
  vrm.scene.updateMatrixWorld(true);
  sbm?.reset();
  for (let i = 0; i < 300; i++) vrm.update(dt);
  dump(`scaled-${s.toFixed(3)}-settled-5s`);
}
main().catch((e) => { console.error(e); process.exit(1); });
