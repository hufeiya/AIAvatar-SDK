---
name: publish-release
description: AIAvatar-SDK 一键全量发布:Maven Central 发版 + APK release 包 + GitHub Release 一口气发齐。自动递增 maven 版本(SDK_VERSION)和 APK 版本(versionName/versionCode,不指定就各自递增最后一位)、同步两份 README 的 implementation 版本号、用 tools/release.sh 发 Maven、按上个 tag 以来的 commit 自动生成发布说明、上传 APK 到 GitHub Release、commit 并打 tag。用户说「发个版」「发布新版本」「发 release」「更新版本发布」「打包发布 maven/apk」「出新包」时使用;支持指定版本号与 --dry-run 演练。
---

# publish-release:Maven + APK + GitHub Release 一键发齐

运行脚本完成全部工作(脚本是正本,不要手工重做里面的 sed/curl 步骤):

```bash
bash .agents/skills/publish-release/scripts/publish_release.sh [maven版本] [apk版本] [--skip-tests] [--dry-run]
```

路径相对仓库根。模型只做三件事:确认意图(版本号/是否演练)→ 跑脚本 → 转述结果。

## 参数

| 参数 | 说明 |
|---|---|
| maven版本(可选) | 缺省 = 当前 SDK_VERSION 最后一位 +1(0.1.1→0.1.2) |
| apk版本(可选) | 缺省 = 当前 versionName 最后一位 +1(1.0→1.1);versionCode 自动 +1 |
| --skip-tests | 透传 release.sh,跳过全量单测(急用) |
| --dry-run | 演练:改版本+打 APK+单测+mavenLocal+bundle+生成发布说明后打印;**不 commit/不上传/不发 Release,结束自动还原工作区** |

- 只给一个位置参数 = 只指定 maven 版本,APK 自动递增。
- GitHub Release 的 tag 取 **APK 版本**(v1.1 这种),与 v1.0 先例一致;发布说明里同时写明两个版本。
- `NOTES_FILE=文件` 环境变量可替换自动生成的发布说明(想润色措辞时用)。

## 脚本流程(顺序已修正,勿手工重排)

1. 前置检查:工作区干净、central.properties + secrets.properties 的 GITHUB_TOKEN 就绪(先在线验 token,别等 Maven 发完才发现 GitHub 发不了)、tag v<apk> 未占用。
2. 改 4 处版本号:gradle.properties 的 SDK_VERSION、app/build.gradle.kts 的 versionCode/versionName、README.md 与 README_EN.md 的 implementation 坐标。
3. `:app:assembleRelease` 快速失败,产物 `app/build/outputs/apk/release/AIAvatar-v<apk>-release.apk`(文件名自动带新版本号,AGP 里配的)。
4. 单条 release commit(上述 4 个文件一起,`TYPE: chore release ...`)。
5. `tools/release.sh <maven> --no-git`:单测→publishToMavenLocal→bundle→上传 Central Portal→盯 repo1(≤45 分钟)。
6. tag `v<apk版本>` → `git push origin main v<apk>`(必须在建 Release 前推上去)。
7. 生成发布说明(上个 tag 以来的 commit,自动过滤 release 提交)→ API 建 Release → curl 上传 APK → 校验 state=uploaded。

**为什么 README 在 commit 前改**:release.sh 要求干净工作区;且 tag 快照里的 README 必须是新版本号,不能发布后再补一次 commit。

## 失败恢复(高频)

- **release.sh 中途失败**(最常见:repo1 45 分钟没出现产物 = Central 验证没过,去 central.sonatype.com → Deployments 看错误):修复后**带相同版本号重跑**
  `publish_release.sh <maven> <apk>`。⚠ 不带参数重跑会再递增一次版本号,错!脚本各步骤幂等,已提交的不会重复提交。
- **GITHUB_TOKEN 401/404**:token 过期或被 revoke → 找用户要新 token,更新 secrets.properties 的 GITHUB_TOKEN 行(严禁写进任何会入库的文件)。
- **tag 已存在但 Release 没发成**:`git tag -d v<apk> && git push origin :refs/tags/v<apk>` 删掉重跑,或换版本号。
- **只想补传 APK**(Maven/Release 都成了但附件没传上):直接 curl 上传,或 Release 网页手动传,别重跑整个脚本。

## 凭据与红线

- Maven Central:根目录 `central.properties`(gitignored);GitHub:secrets.properties 的 `GITHUB_TOKEN`(gitignored)。脚本只读不 echo。
- token 出现在对话里(用户粘贴)没关系,但**不得**写入任何被 git 跟踪的文件,不得贴进 issue/README/发布说明。
- secrets.properties 里其他 key 是真机 demo 用的,与发布无关;新增 key 也不会被本脚本误用(只认 GITHUB_TOKEN)。

## 耗时与运行方式

全程最长约 1 小时,大头是 repo1 盯发布(≤45 分钟)和全量单测。让模型**后台跑脚本**(run_in_background)再等通知,别用 2 分钟默认超时前台硬等。dry-run 约 5-10 分钟(APK 构建+bundle)。
