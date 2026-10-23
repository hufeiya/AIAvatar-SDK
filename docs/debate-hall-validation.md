# 辩论大厅、抽屉入口与火山语音验证

2026-10-18，本次在既有 P0 上接入 Blender 大厅。

MainActivity 抽屉新增「辩论赛」。普通启动 DebateActivity 自动从 APK assets 加载 `debate_default.json` 和其引用的 `layout_debate_hall_v1.json`，不会读取或覆盖应用目录中的同名调试配置。页面载入按钮也直接读内建配置；外部控制的默认文件优先级保持原样，可用 `--es config_source assets` 明确选择内建配置。

固定稿语音独立使用火山 `seed-tts-2.0`，读取 `demo_settings` 的 `ai_api_key_volcano_tts`。无须配置 LLM Key，不继承全局 Edge/其他 TTS 选择；缺语音 Key 时显示提示并禁止开赛。Ark 大模型 Key 不会替代豆包语音 Key。密钥通过本地 push-api-keys 技能同步到模拟器，没有写入源码或 APK。

九个角色在默认/短赛程 JSON 的 `participants[].voice` 中分别指定音色。新增的三种音色同步进入 SDK 目录，避免解析时回退到相同默认音色。依据[火山官方 2.0 音色表](https://docs.volcengine.com/docs/DoubaoVoice/Tonelist-1?lang=zh)，并已逐个调用实际服务验证有非空 PCM 音频：

| 席位 | 音色 |
| --- | --- |
| HOST | 云舟 2.0 |
| PRO1 | Vivi 2.0 |
| PRO2 | 小何 2.0 |
| PRO3 | 小天 2.0 |
| PRO4 | 魅力苏菲 2.0 |
| CON1 | 清新女声 2.0 |
| CON2 | 少年梓辛 |
| CON3 | 魅力女友 |
| CON4 | 知性灿灿 2.0 |

音色 ID、音频时长和实际请求耗时见 [voices.json](debate-hall-validation/voices.json)。该文件不含鉴权信息。

构建与单测：`:app:assembleDebug :app:testDebugUnitTest :avatar-orchestrator:testDebugUnitTest` 通过。app 297 项、orchestrator 586 项，失败均为 0。新增测试覆盖无 LLM Key 的火山固定稿、全局 Edge 配置隔离、Ark Key 不冒充语音 Key，以及两套配置的九种音色均能原样解析。

模拟器使用 Medium_Phone / x86_64。旧 `swiftshader_indirect` 软件渲染模式只有 256 个顶点 uniform，不能编译现有 MToon 材质；改用 `-gpu host` 验证，不为绕过模拟器限制修改产品材质。

## 已完成的运行时检查

- 从 MainActivity 实际点击抽屉中的 Debate 条目进入辩论页；[抽屉截图](debate-hall-validation/drawer.png)。
- 预先在应用目录放置 `matchId=stale-cache-must-not-load` 的旧默认配置，入口仍加载 `debate-demo-001` 与新大厅；随后删除这份临时验证文件。[九人 READY 状态](debate-hall-validation/ready-state.json)中九人均 ready、failed=false、lastError=null。
- [九人全景](debate-hall-validation/ready-wide.png)中可见新大厅、九个席位的桌椅和编号；[坐姿近景](debate-hall-validation/seated-close.png)可复核当前坐姿。座面接触还未做逐模型髋骨/IK 校准。
- 点击「开赛」，[主持人发言截图](debate-hall-validation/host-speaking.png)和[运行状态](debate-hall-validation/running-state.json)均进入 SPEAKING。Android AudioTrack 报告该应用 `state:started`、`sampleRate=16000`、单声道、CONTENT_TYPE_SPEECH。
- [默认赛程导出](debate-hall-validation/default-transcript.json)：HOST 完整播放 16651 ms、PRO1 完整播放 10552 ms，状态 PLAYED/COMPLETED；CON1 的人工暂停记录为 INTERRUPTED/PAUSED，属于本次暂停测试。
- [暂停状态](debate-hall-validation/paused-state.json)为 PAUSED；恢复后再次查询为 RUNNING。

本次没有运行完整正式时长；使用短赛程覆盖全部阶段。模拟器验证期间 app 的一次 PSS 采样为 715952 KB（约 699 MiB），包含仍在返回栈中的主页面；不能据此推断低内存设备表现或长期无泄漏。

短赛程最终运行到 **FINISHED**，覆盖全部七阶段、九个角色。[最终状态](debate-hall-validation/quick-final-state.json)中 `lastError=null`；[导出记录](debate-hall-validation/quick-transcript.json)共 22 条，FAILED 为 0。短赛程的较短发言限时会按规则截断长稿，TIMEOUT/INTERRUPTED 属于预期。阶段采样见 [smoke.log](debate-hall-validation/smoke.log)。
