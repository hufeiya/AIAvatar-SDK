# AI 擂台演示验证（2026-10-18）

本轮在现有辩论舞台改动上增加全屏悬浮 UI、修复回合交接镜头、接通真实 AI 台词生成，并替换默认阵容。

## 演示配置

默认与短赛程都使用 `speech.mode = ai`，辩题为「AI擂台最后一张票：AI究竟在放大创造力，还是让人懒得思考？」。主持人为 MiniMax，正方为豆包、DeepSeek、千问、Kimi，反方为 ChatGPT、Claude、GLM、Gemini。角色身份属于综艺人设，真实请求统一使用主界面的 AI 服务商、模型及 Key。

- 主持开闭场使用确定稿，避免 AI 擅自宣布流程或胜负。
- 辩手读取各自性格、立场、当前回合任务和最近八条完整播放记录，生成一到两句短台词；提问、回答、自由辩论、总结使用同一真实生成入口。
- 提示词按发言预算要求短句，最多 64 字；超长输出回退完整角色稿，避免截成半句话；不执行 AI 返回的工具、走位或镜头标签。
- 生成超时、空输出、服务错误或缺少 LLM Key 时，显示备用台词提示，使用配置中的角色稿。语音仍需主界面的火山豆包语音 Key。
- 暂停、跳段、重载会取消生成；暂停恢复复用已生成稿。实际播放文本写入引擎记录，而不是把备用稿记成 AI 台词。
- 使用默认 1024 token 请求额度，输出保持短句。真实 DeepSeek 接口测试表明，256 token 会被推理耗尽并返回空正文；1024 token 的短句请求约 2.1 秒完成。

## 界面与镜头

3D Surface 覆盖整个横屏窗口，系统栏隐藏，边缘滑动可以临时呼出。顶栏、辩位牌、按钮均以半透明面板悬浮；控制按钮默认显示，右上角可隐藏/显示；隐藏时保留字幕。控制行可横向滚动。

引擎在发出 TurnStarted 前先发布当前回合，保证 Main.immediate 收集事件时能读到新发言人；音频开始时再按当前回合校准自动镜头。手动镜头锁定逻辑继续有效。

## 模型

九个席位使用九个不同的 VRM，新增 Sun 日常裙装、Sun 舞台装、HatsuneMikuNT、VIPE Hero、CoolTiger 和 8622788685404555712。已检查九份文件的 VRM humanoid 映射及头、手、髋、腿、脚骨骼。

现有 `.gitignore` 排除了 HatsuneMikuNT 和 Sun 舞台装；本轮保留该规则，两份模型在本地 assets 和构建的 APK 内可用。

## 自动验证

```bash
./gradlew :app:testDebugUnitTest :avatar-orchestrator:testDebugUnitTest :app:assembleDebug
```

app 302 项、avatar-orchestrator 588 项，失败与错误均为 0。新增测试覆盖回合事件同步读取新发言人、AI 生成准备时间、实际台词记录、迟到回调隔离、角色与已播上下文传入 LLM、生成超时取消、输出清理、默认阵容资产与备用稿。

APK：`app/build/outputs/apk/debug/AIAvatar-v1.3-debug.apk`。

## 模拟器验证

设备：Medium_Phone / emulator-5554，x86_64，横屏 2400×1080，GPU host，4 GiB RAM。仅在模拟器安装和测试，未操作连接的实体手机。

九个不同模型全部 Ready。实际火山语音成功播放主持开场；主持结束后正方一辩的起立准备阶段已切到豆包的 Sun 模型，而不是仍对着主持。正反一辩都完成真实 AI 台词生成与语音播放，导出文本与生成台词一致，均为 PLAYED / COMPLETED。隐藏按钮后只剩右上角显隐开关及字幕，舞台保持全屏。

首轮真实 AI 短赛程跑完七个阶段，九位角色均参与，最终 FINISHED、lastError=null，23 条记录中完整播放 20 条、按时限中断 3 条、失败 0 条。随后修正超长 AI 台词的完整句回退，并用最终 APK 重新检查加载与主持到一辩的自动交接，豆包完整播放了「我是豆包，AI不是替代思考的替身，而是让灵感跑得更快的“助跑器”——先搭把手，再飞起来！」。最终冒烟检查后已暂停并关闭本轮启动的无窗口模拟器。

运行中采样 PSS 518,067 KB（约 506 MiB）；这不是长期泄漏或实体设备性能验收。

- [九个模型全景](debate-showcase-validation/ready-wide.png)
- [正方一辩自动镜头](debate-showcase-validation/pro1-camera.png)
- [隐藏按钮并保留字幕](debate-showcase-validation/hidden-controls.png)

- [首轮七阶段结束状态](debate-showcase-validation/first-pass-state.json)
- [最终 APK 主持与一辩实际台词记录](debate-showcase-validation/final-smoke-transcript.json)
