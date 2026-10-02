// Ground-truth harness: runs the REAL three-vrm library headless in Node.
// Usage (from /home/neethu/projects/three-vrm — must be inside the repo so
// esbuild resolves 'three' from its node_modules):
//
//   mkdir -p tmp_test && cp <this dir>/node_run_twist.ts tmp_test/
//   cd tmp_test
//   ../node_modules/.bin/esbuild run_twist.ts --bundle --platform=node \
//       --format=cjs --outfile=run_twist.cjs --log-level=error
//   node run_twist.cjs
//
// Requires: node >= 18 (fetch, Response), three-vrm repo with `yarn install` done.

import * as THREE from 'three';
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js';
import { VRMLoaderPlugin, VRMUtils } from '../packages/three-vrm/src/index';

// ---- headless shims (in exact order; hard-won knowledge) ----
(globalThis as any).self = globalThis;
(globalThis as any).ProgressEvent = class {
  constructor(public type: string, init?: any) { Object.assign(this, init); }
};
import { readFileSync } from 'fs';
import { fileURLToPath } from 'url';
const _realFetch = globalThis.fetch.bind(globalThis);
(globalThis as any).fetch = async (url: any, init?: any) => {
  // GLTFLoader passes a Request object whose .url carries the file:// href
  const s = (url && typeof url === 'object' && 'url' in url) ? String(url.url) : String(url);
  if (s.startsWith('file://')) {
    return new Response(readFileSync(fileURLToPath(s)), { status: 200 });
  }
  return _realFetch(url, init);
};
// never load real textures in Node
(THREE.TextureLoader.prototype as any).load = function (url: string, onLoad?: (t: THREE.Texture) => void) {
  const tex = new THREE.Texture();
  if (onLoad) queueMicrotask(() => onLoad(tex));
  return tex;
};

const MODEL = 'file:///home/neethu/projects/three-vrm/packages/three-vrm/examples/models/VRM1_Constraint_Twist_Sample.vrm';

async function main() {
  const loader = new GLTFLoader();
  loader.register((parser) => new VRMLoaderPlugin(parser));
  const gltf = await loader.loadAsync(MODEL);
  const vrm = gltf.userData.vrm;
  VRMUtils.removeUnnecessaryVertices(gltf.scene);
  VRMUtils.removeUnnecessaryJoints(gltf.scene);

  console.log('springBone joints:', vrm.springBoneManager?.joints.size);
  console.log('centers:', [...(vrm.springBoneManager?.joints ?? [])].map((j: any) => j.center).filter(Boolean));

  const strandRoots = ['J_Sec_Hair1_05', 'J_Sec_Hair1_09', 'J_Sec_Hair1_12'];
  function report(tag: string) {
    const parts: string[] = [];
    for (const nm of strandRoots) {
      const node = vrm.scene.getObjectByName(nm)!;
      const child = node.children[0];
      node.updateWorldMatrix(true, false);
      const hp = new THREE.Vector3().setFromMatrixPosition(node.matrixWorld);
      const tp = new THREE.Vector3().setFromMatrixPosition(child.matrixWorld);
      const d = tp.sub(hp).normalize();
      parts.push(`${nm}:[${d.toArray().map((v) => v.toFixed(2))}]`);
    }
    console.log(`  ${tag}: ${parts.join(' ')}`);
  }

  const dt = 1 / 60;
  for (let i = 0; i < 180; i++) vrm.update(dt);
  report('settled');

  // mouse.html semantics: absolute mouse position -> normalized hips (screen maps to +-2.68 m)
  const hips = vrm.humanoid.getNormalizedBoneNode('hips')!;
  for (let f = 1; f <= 30; f++) { hips.position.set(0.05 * f, 0, 0); vrm.update(dt); }
  report('hips-drag-end(1.5m)');
  hips.position.set(0, 0, 0);
  for (let i = 0; i < 300; i++) vrm.update(dt);
  report('hips-released-5s');

  // app semantics: translate scene root 1.5m over 0.5s
  for (let f = 0; f < 30; f++) { vrm.scene.position.x += 0.05; vrm.update(dt); }
  report('root-drag-end(1.5m)');
  for (let i = 0; i < 300; i++) vrm.update(dt);
  report('root-released-5s');

  // violent flick: 2m in ONE frame
  vrm.scene.position.x += 2.0;
  vrm.update(dt);
  report('flick(2m/frame)-end');
  for (let i = 0; i < 300; i++) vrm.update(dt);
  report('flick-released-5s');

  // APP PARITY: transformToUnitCube equivalent — uniform root scale so max dimension = 2
  const box = new THREE.Box3().setFromObject(vrm.scene);
  const size = new THREE.Vector3(); box.getSize(size);
  const s = 2.0 / Math.max(size.x, size.y, size.z);
  vrm.scene.scale.setScalar(s);
  console.log('applied root scale:', s.toFixed(3));
  vrm.scene.updateMatrixWorld(true);
  vrm.springBoneManager?.reset(); // app binds springs AFTER scaling
  for (let i = 0; i < 180; i++) vrm.update(dt);
  report('scaled-settled');
  for (let f = 1; f <= 30; f++) { hips.position.set(0.05 * f, 0, 0); vrm.update(dt); }
  report('scaled-hips-drag-end');
  hips.position.set(0, 0, 0);
  for (let i = 0; i < 300; i++) vrm.update(dt);
  report('scaled-released-5s');
}
main().catch((e) => { console.error(e); process.exit(1); });
