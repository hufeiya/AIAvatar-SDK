#!/usr/bin/env bash
# 从本地 three-vrm 仓库刷新 vendor 三方库(viewer 自包含,常规情况无需运行)。
# 用法: ./setup-vendor.sh [/path/to/three-vrm 仓库]
set -euo pipefail

SRC="${1:-/home/neethu/projects/mtoon/three-vrm}"
DEST="$(cd "$(dirname "$0")" && pwd)/public/vendor"

for f in "$SRC/node_modules/three/build/three.module.js" \
         "$SRC/node_modules/three/build/three.core.js" \
         "$SRC/packages/three-vrm/lib/three-vrm.module.js"; do
  [ -f "$f" ] || { echo "缺少文件: $f" >&2; exit 1; }
done

mkdir -p "$DEST/addons/controls" "$DEST/addons/loaders" "$DEST/addons/utils"
cp "$SRC/node_modules/three/build/three.module.js" "$DEST/"
cp "$SRC/node_modules/three/build/three.core.js" "$DEST/"
cp "$SRC/packages/three-vrm/lib/three-vrm.module.js" "$DEST/"
cp "$SRC/packages/three-vrm-animation/lib/three-vrm-animation.module.js" "$DEST/"
cp "$SRC/node_modules/three/examples/jsm/controls/OrbitControls.js" "$DEST/addons/controls/"
cp "$SRC/node_modules/three/examples/jsm/loaders/GLTFLoader.js" "$DEST/addons/loaders/"
cp "$SRC/node_modules/three/examples/jsm/utils/BufferGeometryUtils.js" "$DEST/addons/utils/"

echo "vendor 已刷新自: $SRC"
