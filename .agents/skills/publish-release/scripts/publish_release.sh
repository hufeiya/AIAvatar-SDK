#!/usr/bin/env bash
# ── AIAvatar-SDK 一键全量发布(技能 publish-release 主脚本) ────────────
# Maven Central + APK release 包 + GitHub Release 一口气发齐。
#
# 用法:
#   .agents/skills/publish-release/scripts/publish_release.sh [maven版本] [apk版本] [--skip-tests] [--dry-run]
# 例:
#   publish_release.sh                    # 两版本都自动递增最后一位(SDK 0.1.1→0.1.2,APK 1.0→1.1)
#   publish_release.sh 0.2.0              # maven 指定 0.2.0,APK 自动递增
#   publish_release.sh 0.2.0 1.2          # 两个都指定
#   publish_release.sh --dry-run          # 全流程演练:改版本+打APK+测试+bundle+生成发布说明,
#                                         #   不 commit/不上传/不发 Release,结束自动还原工作区
#   NOTES_FILE=notes.md publish_release.sh 0.2.0   # 用外部文件当发布说明(其余不变)
#
# 流程: 前置检查(工作区/凭据/tag 占用) → 改 4 处版本号(gradle.properties /
#       app 版本 / 两份 README)→ assembleRelease(快速失败)→ 单条 release commit
#       → release.sh --no-git(单测→mavenLocal→bundle→上传→盯 repo1 ≤45 分钟)
#       → tag v<apk版本> → push → 发布说明(上个 tag 以来的 commit)→ 建 GitHub
#       Release → 上传 APK → 校验 uploaded
#       注意 README 必须在 commit 前改好:release.sh 要求干净工作区,且 tag 快照里
#       README 才能是新版本号。
#
# 凭据: central.properties(Maven Central)+ secrets.properties 的 GITHUB_TOKEN
#       (GitHub REST API)。token 只读不 echo、不落盘到任何会入库的文件。
set -euo pipefail
step() { echo; echo "═══ $1 ═══"; }
die() { echo; echo "✗ $*"; exit 1; }
# 自定位仓库根:脚本在 <root>/.agents/skills/publish-release/scripts/ 下,上跳 4 层
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/../../../.."
[ -f gradle.properties ] || die "仓库根定位失败(找不到 gradle.properties),请从仓库内运行"

SKIP_TESTS=0
DRY_RUN=0
ARGS=()
for a in "$@"; do
  case "$a" in
    --skip-tests) SKIP_TESTS=1 ;;
    --dry-run) DRY_RUN=1 ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    --*) { echo "✗ 未知参数: $a"; exit 1; } ;;
    *) ARGS+=("$a") ;;
  esac
done
[ ${#ARGS[@]} -le 2 ] || { echo "✗ 最多两个位置参数:<maven版本> <apk版本>"; exit 1; }

# ── 现版本读取与目标版本解析 ────────────────────────────────────────────
bump_last() { awk -F. -v OFS=. '{ $NF = $NF + 1; print }' <<< "$1"; }   # 勿写 $NF++:mawk 解析成 $(NF++) 会递增 NF 而非末字段

CUR_SDK=$(grep -E '^SDK_VERSION=' gradle.properties | cut -d= -f2 | tr -d '[:space:]' || true)
CUR_APK=$(grep -E '^[[:space:]]*versionName = ' app/build.gradle.kts | head -1 | sed -E 's/.*"([^"]+)".*/\1/' || true)
CUR_CODE=$(grep -E '^[[:space:]]*versionCode = ' app/build.gradle.kts | head -1 | grep -oE '[0-9]+' || true)
[ -n "$CUR_SDK" ] && [ -n "$CUR_APK" ] && [ -n "$CUR_CODE" ] \
  || die "读不到当前版本(gradle.properties SDK_VERSION / app versionName/versionCode)"

MV_VER="${ARGS[0]:-$(bump_last "$CUR_SDK")}"
APK_VER="${ARGS[1]:-$(bump_last "$CUR_APK")}"
NEW_CODE=$((CUR_CODE + 1))
[[ "$MV_VER" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-._a-zA-Z0-9]+)?$ ]] || die "maven 版本号形如 1.2.3: $MV_VER"
[[ "$APK_VER" =~ ^[0-9]+(\.[0-9]+){1,3}$ ]] || die "apk 版本号形如 1.2 或 1.2.3: $APK_VER"

REPO_SLUG=$(git remote get-url origin | sed -E 's#.*github\.com[:/]##; s#\.git$##')
[ -n "$REPO_SLUG" ] || die "解析不出 GitHub 仓库(git remote get-url origin)"
LAST_TAG=$(git describe --tags --abbrev=0 2>/dev/null || true)   # 发布说明的起点,必须在新 tag 打出前取

echo "发布计划:"
echo "  SDK  : $CUR_SDK → $MV_VER(Maven Central)"
echo "  APK  : v$CUR_APK(code $CUR_CODE) → v$APK_VER(code $NEW_CODE)"
echo "  tag  : v$APK_VER(上个 tag: ${LAST_TAG:-无})"
echo "  仓库 : $REPO_SLUG"
[ "$DRY_RUN" = "1" ] && echo "  ⚠ DRY-RUN:不 commit/不上传/不发 Release,结束还原工作区"

# ── 前置检查 ────────────────────────────────────────────────────────────
step "前置检查"
if [ "$DRY_RUN" = "0" ]; then
  git diff-index --quiet HEAD -- || die "工作区不干净,先提交/暂存(git status)"
else
  echo "ℹ dry-run:跳过工作区干净检查"
fi
git rev-parse -q --verify "refs/tags/v$APK_VER" >/dev/null \
  && die "tag v$APK_VER 已存在;要重发同版本先删 tag(本地+远程)或换版本号"
[ "$APK_VER" != "$CUR_APK" ] || die "APK 版本与当前相同: $APK_VER"
[ -f central.properties ] || die "缺 central.properties(Maven Central 凭据,见 tools/release.sh 头注释)"
GH_TOKEN=$(grep -E '^GITHUB_TOKEN=' secrets.properties 2>/dev/null | head -1 | cut -d= -f2- | tr -d '[:space:]')
[ -n "$GH_TOKEN" ] || die "secrets.properties 缺 GITHUB_TOKEN(gitignored,严禁写进其他文件)"
# 先验 token 再动版本号:免得 Maven 发完才发现 GitHub 发不了
_code=$(curl -sS -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $GH_TOKEN" \
  "https://api.github.com/repos/$REPO_SLUG")
[ "$_code" = "200" ] || die "GitHub API 校验失败(HTTP $_code):token 失效/仓库不可见,更新 secrets.properties 的 GITHUB_TOKEN"
echo "✓ 工作区/凭据/tag 均就绪"

# dry-run 随时退出都还原(gradle.properties 由 release.sh --dry-run 自行还原)
if [ "$DRY_RUN" = "1" ]; then
  trap 'git checkout -q -- gradle.properties app/build.gradle.kts README.md README_EN.md 2>/dev/null || true; echo "(dry-run 工作区已还原)"' EXIT
fi

# ── 改版本号(4 处) ─────────────────────────────────────────────────────
step "版本号 → SDK $MV_VER / APK v$APK_VER(code $NEW_CODE)"
sed -i -E "s/^SDK_VERSION=.*/SDK_VERSION=$MV_VER/" gradle.properties
grep -q "^SDK_VERSION=$MV_VER$" gradle.properties || die "SDK_VERSION 写入失败"
sed -i -E "s/^([[:space:]]*versionCode = ).*/\1$NEW_CODE/" app/build.gradle.kts
sed -i -E "s/^([[:space:]]*versionName = ).*/\1\"$APK_VER\"/" app/build.gradle.kts
grep -qE "versionCode = $NEW_CODE$" app/build.gradle.kts || die "versionCode 写入失败"
grep -qE "versionName = \"$APK_VER\"$" app/build.gradle.kts || die "versionName 写入失败"
for f in README.md README_EN.md; do
  sed -i -E "s#(io\.github\.hufeiya:(corelib|avatar-orchestrator|avatar-ai-adapter):)[0-9][0-9A-Za-z.+_-]*#\1$MV_VER#g" "$f"
  _n=$(grep -cE "io\.github\.hufeiya:(corelib|avatar-orchestrator):$MV_VER" "$f" || true)
  [ "$_n" -ge 2 ] || die "$f 的 implementation 版本号更新异常(找到 $_n 处,应≥2)"
done
echo "✓ gradle.properties / app 版本 / README.md / README_EN.md 已更新"

# ── 打 APK release 包(快速失败) ────────────────────────────────────────
step "打 APK(:app:assembleRelease)"
./gradlew :app:assembleRelease --console=plain -q || die "APK 构建失败(工作区未提交,dry-run 会自动还原)"
APK_PATH="app/build/outputs/apk/release/AIAvatar-v$APK_VER-release.apk"
[ -f "$APK_PATH" ] || die "未找到产物 $APK_PATH"
echo "✓ $(ls -lh "$APK_PATH" | awk '{print $5, $9}')"

# ── 提交版本号(单条 release commit) ───────────────────────────────────
if [ "$DRY_RUN" = "1" ]; then
  step "(dry-run)跳过 commit —— 将提交以下改动:"
  git status --short -- gradle.properties app/build.gradle.kts README.md README_EN.md
else
  step "提交版本号"
  git add gradle.properties app/build.gradle.kts README.md README_EN.md
  if git diff --cached --quiet; then
    echo "✓ 四个文件无改动(失败重跑场景,release commit 已在历史里)"
  else
    git commit -q -m "TYPE: chore release SDK $MV_VER + APK v$APK_VER(一键发布技能 publish-release)"
    echo "✓ $(git log --oneline -1 | head -c 80)"
  fi
fi

# ── 发 Maven Central(release.sh --no-git:不提交不 tag) ───────────────
step "发布 Maven Central(tools/release.sh --no-git)"
RL=(bash tools/release.sh "$MV_VER" --no-git)
if [ "$DRY_RUN" = "1" ]; then RL+=("--dry-run"); fi
if [ "$SKIP_TESTS" = "1" ]; then RL+=("--skip-tests"); fi
if ! "${RL[@]}"; then
  die "release.sh 失败。修复后带相同版本号重跑(勿不带参数,会重复递增): bash .agents/skills/publish-release/scripts/publish_release.sh $MV_VER $APK_VER"
fi

# ── tag + push ─────────────────────────────────────────────────────────
if [ "$DRY_RUN" = "1" ]; then
  step "(dry-run)跳过 tag/push —— 将执行: git tag -a v$APK_VER && git push origin main v$APK_VER"
else
  step "打 tag 并推送"
  git tag -a "v$APK_VER" -m "release v$APK_VER (SDK $MV_VER)"
  git push origin main "v$APK_VER" || die "push 失败:检查网络/远程分叉(git pull --rebase 后重跑本脚本,同版本号)"
  echo "✓ main + tag v$APK_VER 已推送"
fi

# ── 发布说明 ───────────────────────────────────────────────────────────
step "生成发布说明"
NOTES="${NOTES_FILE:-/tmp/aiavatar-release-notes-v$APK_VER.md}"
if [ -n "${NOTES_FILE:-}" ] && [ -f "$NOTES_FILE" ]; then
  echo "✓ 使用外部发布说明: $NOTES_FILE"
else
  if [ -n "$LAST_TAG" ]; then
    CHANGES=$(git log "$LAST_TAG"..HEAD --pretty=format:'- %s (%h)' | grep -v 'TYPE: chore release' || true)
  else
    CHANGES=$(git log --pretty=format:'- %s (%h)' | head -40)
  fi
  [ -n "$CHANGES" ] || CHANGES="- (无功能性提交,纯版本发布)"
  cat > "$NOTES" <<EOF
## 版本
- APK **v$APK_VER**(versionCode $NEW_CODE)
- SDK **$MV_VER**(Maven Central)

## 下载
下方 Assets 的 \`AIAvatar-v$APK_VER-release.apk\` 直接安装;首次启动有新手引导,配一个免费 LLM Key 就能聊(语音输入输出全程免费)。

## 自 ${LAST_TAG:-起点} 以来的提交
$CHANGES

## SDK 坐标
\`\`\`kotlin
implementation("io.github.hufeiya:corelib:$MV_VER")
implementation("io.github.hufeiya:avatar-orchestrator:$MV_VER")
\`\`\`
EOF
  echo "✓ $NOTES"
fi
sed 's/^/  │ /' "$NOTES"

if [ "$DRY_RUN" = "1" ]; then
  echo
  echo "═══ dry-run 完成:未 commit/未发 Maven/未发 GitHub Release,工作区即将还原 ═══"
  exit 0
fi

# ── GitHub Release + 上传 APK ──────────────────────────────────────────
step "创建 GitHub Release 并上传 APK(约 260MB)"
api() { curl -sS -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github+json" "$@"; }
RESP=$(api -X POST "https://api.github.com/repos/$REPO_SLUG/releases" -d "$(jq -n \
  --arg tag "v$APK_VER" --arg body "$(cat "$NOTES")" \
  '{tag_name:$tag, name:$tag, body:$body, target_commitish:"main", draft:false, prerelease:false}')")
REL_ID=$(jq -r '.id // empty' <<<"$RESP")
if [ -z "$REL_ID" ]; then
  echo "$RESP" | jq -r '.message // .' >&2 || true
  die "创建 Release 失败(token 失效就更新 secrets.properties 的 GITHUB_TOKEN;tag 已推送可手动补发)"
fi
echo "✓ Release 已创建(id $REL_ID),上传 APK 中…"
UP=$(api -X POST -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @"$APK_PATH" \
  "https://uploads.github.com/repos/$REPO_SLUG/releases/$REL_ID/assets?name=$(basename "$APK_PATH")")
STATE=$(jq -r '.state // empty' <<<"$UP")
if [ "$STATE" != "uploaded" ]; then
  echo "$UP" | jq -r '.message // .' >&2 || true
  die "APK 上传失败(Release 页面在 https://github.com/$REPO_SLUG/releases/tag/v$APK_VER,可手动补传 $APK_PATH)"
fi

echo
echo "═══ 全量发布完成 🎉 ═══"
echo "  Maven Central : io.github.hufeiya:corelib:$MV_VER"
echo "  APK           : $(jq -r '.browser_download_url' <<<"$UP")"
echo "  Release 页面  : https://github.com/$REPO_SLUG/releases/tag/v$APK_VER"
