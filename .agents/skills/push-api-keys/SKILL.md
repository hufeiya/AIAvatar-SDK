---
name: push-api-keys
description: 一键把仓库 secrets.properties 里的所有 API Key(硅基流动/火山方舟/火山豆包语音/OpenRouter)通过 adb 写入已连接设备上 demo 应用的 SharedPreferences。当用户想把 API key 推送/同步/配置到真机、给设备上的 app 设置密钥、提到 secrets.properties 和 adb/真机/设备时使用;应用未安装的设备自动跳过不做任何事。
---

# push-api-keys:secrets.properties → 设备 SharedPreferences

运行脚本完成全部工作(逐台设备:检查应用是否安装 → 杀进程 → upsert XML → run-as 写回 → 回读校验 → 按需重启):

```bash
bash .agents/skills/push-api-keys/scripts/push_api_keys.sh
```

路径相对仓库根(AIAvatar-SDK)。

## 行为约定

- **应用未安装 → 该设备跳过,什么都不做**(脚本内 `pm path` 检查)。
- 没有已连接且授权的 adb 设备 → 报错退出码 1;多设备 → 逐台处理。
- 写入前先 `am force-stop`:app 存活时内存里的 prefs 会在下一次任意保存时整体覆盖磁盘改动。
- 之前在运行的设备写完后自动 `am start` 重启使其生效;没在运行则下次启动生效。
- secrets.properties 里缺失/为空的 key 跳过并提示,保留设备上现有值,不会清空。
- `run-as` 失败说明装的是 release 包(不可调试),脚本会报错提示装 debug 变体。
- 写入后回读校验每个 key 的完整值,校验失败退出码 1。

## 键映射

secrets.properties 键 → `demo_settings.xml` 里的 string 条目名。正本在
`app/src/main/java/com/neethu/aiavatar_sdk/AiChat.kt`(KEY_AI_API_KEY_* 常量),
新增/改名 key 时先改代码再同步脚本顶部的 `MAPPING` 数组。

| secrets.properties | SharedPreferences key |
|---|---|
| SILICONFLOW_API_KEY | ai_api_key_siliconflow |
| VOLCANO_ARK_API_KEY | ai_api_key_volcano |
| VOLCANO_TTS_API_KEY | ai_api_key_volcano_tts |
| OPENROUTER_API_KEY | ai_api_key_openrouter |

- 火山有两把互不通用的 key:`VOLCANO_ARK_API_KEY` 是方舟大模型(chat),`VOLCANO_TTS_API_KEY` 是豆包语音控制台(TTS WebSocket 的 X-Api-Key)。
- 硅基流动与 OpenRouter 的 key 各自同时用于其 LLM/TTS(OpenRouter 还含 ASR)。

## 手动验证

```bash
adb shell run-as com.neethu.aiavatar_sdk cat /data/data/com.neethu.aiavatar_sdk/shared_prefs/demo_settings.xml
```

值打码显示(只露首 6 位+末 4 位);完整值在仓库根 `secrets.properties`(已 gitignore,严禁提交)。
