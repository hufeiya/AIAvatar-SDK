---
name: publish-release
description: AIAvatar-SDK 一键全量发布:Maven Central 发版 + APK release 包 + GitHub Release 一口气发齐。自动递增 maven 版本(SDK_VERSION)和 APK 版本(versionName/versionCode,不指定就各自递增最后一位)、同步两份 README 的 implementation 版本号、用 tools/release.sh 发 Maven、上传 APK 到 GitHub Release、commit 并打 tag。发布说明由模型总结提交历史写成(NOTES_FILE 必填,禁止罗列原始 commit)。用户说「发个版」「发布新版本」「发 release」「更新版本发布」「打包发布 maven/apk」「出新包」时使用;支持指定版本号与 --dry-run 演练。
---

# publish-release:Maven + APK + GitHub Release 一键发齐

运行脚本完成全部工作(脚本是正本,不要手工重做里面的 sed/curl 步骤):

```bash
NOTES_FILE=/tmp/aiavatar-release-notes-v<apk>.md \
  bash .agents/skills/publish-release/scripts/publish_release.sh [maven版本] [apk版本] [--skip-tests] [--dry-run]
```

路径相对仓库根。模型做四件事:确认意图(版本号/是否演练)→ **总结写发布说明** → 跑脚本 → 转述结果。

## 发布说明:必须总结,禁止罗列 commit(2026-10-11 用户反馈)

v1.1 曾把 commit 原文全量贴进 Release Note——本仓库 commit 主题动辄整段排查记录,用户明确打回。**正式发布必须自带 NOTES_FILE**,前置检查强制校验(漏了会在动任何东西之前 fail,补好重跑零成本)。写法:

1. `git log $(git describe --tags --abbrev=0)..HEAD --pretty=format:'- %s (%h)'` 拿提交列表;
2. 按用户视角总结成「## 新特性」「## 问题修复」两节,一行一条,讲「用户得到什么」;
3. 过滤掉:chore release、内部工具/技能、纯 docs(README/推广文案)提交;
4. 不写排查过程、根因链、文件级细节、单测数量——那是 commit message 的事;
5. 保留骨架三段:## 版本 / ## 下载 / ## SDK 坐标(版本号按本次目标版本写)。

模板(替换 `<…>` 后存 /tmp,NOTES_FILE 指向它):

````markdown
## 版本
- APK **v<apk>**(versionCode <code>)
- SDK **<maven>**(Maven Central)

## 下载
下方 Assets 的 `AIAvatar-v<apk>-release.apk` 直接安装;首次启动有新手引导,配一个免费 LLM Key 就能聊(语音输入输出全程免费)。

## 新特性
- **<特性名>**:<一句话讲用户得到什么>

## 问题修复
- **<问题现象>**:<一句话讲修了什么>

## SDK 坐标
```kotlin
implementation("io.github.hufeiya:corelib:<maven>")
implementation("io.github.hufeiya:avatar-orchestrator:<maven>")
```
````

## 参数

| 参数 | 说明 |
|---|---|
| maven版本(可选) | 缺省 = 当前 SDK_VERSION 最后一位 +1(0.1.3→0.1.4) |
| apk版本(可选) | 缺省 = 当前 versionName 最后一位 +1(1.1→1.2);versionCode 自动 +1 |
| NOTES_FILE(正式发布必填) | 总结好的发布说明文件;缺失或路径拼错都在前置检查 fail,不会静默回退成罗列 commit |
| --skip-tests | 透传 release.sh,跳过全量单测(急用) |
| --dry-run | 演练:改版本+打 APK+单测+mavenLocal+bundle 后打印自动生成的草稿说明;**不 commit/不上传/不发 Release,结束自动还原工作区**;不需要 NOTES_FILE |

- 只给一个位置参数 = 只指定 maven 版本,APK 自动递增。
- GitHub Release 的 tag 取 **APK 版本**(v1.2 这种),与 v1.0 先例一致;发布说明里同时写明两个版本。

## 脚本流程(顺序已修正,勿手工重排)

1. 前置检查:工作区干净、central.properties + secrets.properties 的 GITHUB_TOKEN 在线验通(先验再动版本号)、tag v<apk> 未占用、**NOTES_FILE 是存在的文件**(dry-run 免)。
2. 改 4 处版本号:gradle.properties 的 SDK_VERSION、app/build.gradle.kts 的 versionCode/versionName、README.md 与 README_EN.md 的 implementation 坐标。
3. `:app:assembleRelease` 快速失败,产物 `app/build/outputs/apk/release/AIAvatar-v<apk>-release.apk`(文件名自动带新版本号,AGP 里配的)。
4. 单条 release commit(上述 4 个文件一起,`TYPE: chore release ...`)。
5. `tools/release.sh <maven> --no-git`:单测→publishToMavenLocal→bundle→上传 Central Portal→盯 repo1(≤45 分钟)。
6. tag `v<apk版本>` → `git push origin main v<apk>`(必须在建 Release 前推上去)。
7. 创建 GitHub Release(**正文 = NOTES_FILE 原文**)→ curl 上传 APK → 校验 state=uploaded。

**为什么 README 在 commit 前改**:release.sh 要求干净工作区;且 tag 快照里的 README 必须是新版本号,不能发布后再补一次 commit。

## 失败恢复(高频)

- **忘带/写错 NOTES_FILE**:前置检查直接 fail,此时还没动任何东西——补好文件原样重跑即可。
- **release.sh 中途失败**(最常见:repo1 45 分钟没出现产物 = Central 验证没过,去 central.sonatype.com → Deployments 看错误):修复后**带相同版本号 + 相同 NOTES_FILE 重跑**。⚠ 不带参数重跑会再递增一次版本号,错!脚本各步骤幂等,已提交的不会重复提交。
- **GITHUB_TOKEN 401/404**:token 过期或被 revoke → 找用户要新 token,更新 secrets.properties 的 GITHUB_TOKEN 行(严禁写进任何会入库的文件)。
- **tag 已存在但 Release 没发成**:`git tag -d v<apk> && git push origin :refs/tags/v<apk>` 删掉重跑,或换版本号。
- **只想补传 APK**(Maven/Release 都成了但附件没传上):直接 curl 上传,或 Release 网页手动传,别重跑整个脚本。
- **Release Note 发出去后想改**:PATCH API 改 body 即可(`PATCH /repos/<owner>/<repo>/releases/<id>`,jq 打包 body),别删 Release 重发。

## 凭据与红线

- Maven Central:根目录 `central.properties`(gitignored);GitHub:secrets.properties 的 `GITHUB_TOKEN`(gitignored)。脚本只读不 echo。
- token 出现在对话里(用户粘贴)没关系,但**不得**写入任何被 git 跟踪的文件,不得贴进 issue/README/发布说明。
- secrets.properties 里其他 key 是真机 demo 用的,与发布无关;新增 key 也不会被本脚本误用(只认 GITHUB_TOKEN)。

## 耗时与运行方式

全程最长约 1 小时,大头是 repo1 盯发布(≤45 分钟)和全量单测。让模型**后台跑脚本**(run_in_background)再等通知,别用 2 分钟默认超时前台硬等。dry-run 约 5-10 分钟(APK 构建+bundle)。
