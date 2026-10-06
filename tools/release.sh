#!/usr/bin/env bash
# ── AIAvatar-SDK 一键发布 Maven Central ─────────────────────────────────
# 用法:
#   tools/release.sh <版本号> [--skip-tests] [--dry-run]
# 例:
#   tools/release.sh 0.1.2               # 完整发布(默认先跑全量单测)
#   tools/release.sh 0.1.2 --skip-tests  # 跳过单测(急用)
#   tools/release.sh 0.1.2 --dry-run     # 演练: bump+测试+mavenLocal+bundle,不上传不提交
#
# 流程: 前置检查(工作区干净/凭据在/版本未发过) → 改 SDK_VERSION → 全量单测
#       → 提交版本号 → publishToMavenLocal → bundle → 上传 Central Portal
#       → 盯 repo1 直到三产物可下载(≤45 分钟) → 打 v<版本> tag
#
# 凭据: 仓库根 central.properties(gitignored):centralTokenUser/centralTokenPass/
#       gpgPassphrase(缺了脚本会明确提示),或同名环境变量。
set -euo pipefail
cd "$(dirname "$0")/.."

SKIP_TESTS=0
DRY_RUN=0
ARGS=()
for a in "$@"; do
  case "$a" in
    --skip-tests) SKIP_TESTS=1 ;;
    --dry-run) DRY_RUN=1 ;;
    *) ARGS+=("$a") ;;
  esac
done
[ ${#ARGS[@]} -ge 1 ] || { sed -n '2,14p' "$0"; exit 1; }
VERSION="${ARGS[0]}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-._a-zA-Z0-9]+)?$ ]] || { echo "✗ 版本号形如 1.2.3: $VERSION"; exit 1; }

step() { echo; echo "═══ $1 ═══"; }

# ── 前置检查 ────────────────────────────────────────────────────────────
step "前置检查"
git diff-index --quiet HEAD -- || { echo "✗ 工作区不干净,先提交/暂存(git status)"; exit 1; }
grep -q "^SDK_VERSION=$VERSION$" gradle.properties && { echo "✗ gradle.properties 已经是 $VERSION,换一个新版本号"; exit 1; }
for a in corelib avatar-ai-adapter avatar-orchestrator; do
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 20 \
    "https://repo1.maven.org/maven2/io/github/hufeiya/$a/$VERSION/$a-$VERSION.pom")
  [ "$code" = "200" ] && { echo "✗ $a:$VERSION 已在 Maven Central 上,版本必须递增"; exit 1; }
done
echo "✓ 版本 $VERSION 未发布过;工作区干净"
python3 tools/publish-central.py check

# ── 改版本号 ────────────────────────────────────────────────────────────
step "SDK_VERSION → $VERSION"
sed -i "s/^SDK_VERSION=.*/SDK_VERSION=$VERSION/" gradle.properties
grep "^SDK_VERSION" gradle.properties

# ── 全量单测 ────────────────────────────────────────────────────────────
if [ "$SKIP_TESTS" = "1" ]; then
  step "跳过单测(--skip-tests)"
else
  step "全量单测(四模块 debug 变体)"
  ./gradlew :app:testDebugUnitTest :corelib:testDebugUnitTest \
    :avatar-orchestrator:testDebugUnitTest :avatar-ai-adapter:test
fi

# ── 构建与打包 ──────────────────────────────────────────────────────────
step "publishToMavenLocal"
./gradlew publishToMavenLocal

step "打 Central bundle"
python3 tools/publish-central.py bundle "$VERSION"

if [ "$DRY_RUN" = "1" ]; then
  git checkout -- gradle.properties
  echo
  echo "═══ dry-run 完成:未提交/未上传。bundle 在 /tmp/bundle-$VERSION.zip,可直接检查 ═══"
  exit 0
fi

# ── 提交版本号 ──────────────────────────────────────────────────────────
step "提交版本号"
git add gradle.properties
git commit -q -m "TYPE: chore release v$VERSION(一键发布 tools/release.sh)"
echo "✓ $(git log --oneline -1 | head -c 60)"

# ── 上传与盯发布 ────────────────────────────────────────────────────────
step "上传 Central Portal"
DEPLOY_ID=$(python3 tools/publish-central.py upload "$VERSION" | grep -oE "deployment [0-9a-f-]+" | awk '{print $2}')
echo "deployment id: $DEPLOY_ID(状态查询: python3 tools/publish-central.py status $DEPLOY_ID)"

step "盯发布确认(repo1)"
if python3 tools/publish-central.py watch "$VERSION"; then
  git tag -f "v$VERSION" -m "release v$VERSION"
  echo
  echo "═══ 发布完成 🎉  版本 $VERSION 已上 Maven Central,tag v$VERSION 已打 ═══"
  echo "    集成坐标: implementation(\"io.github.hufeiya:avatar-orchestrator:$VERSION\")"
else
  echo
  echo "⚠ 上传已成功(deployment $DEPLOY_ID)但 45 分钟内 repo1 未出现产物——"
  echo "  大概率验证失败(登录 central.sonatype.com → Publish → Deployments 看错误),"
  echo "  修复后重跑 tools/release.sh $VERSION 即可(版本号未占用会直接走完)。"
  exit 1
fi
