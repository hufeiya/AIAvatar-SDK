---
name: add-scene
description: 把 assets/ 下新建的 Blender 场景资产包(如 dream_bedroom_3,含 glb/meta/cutaway/anchors/interactions 五件套)一键接入 demo 应用:格式校验+GLB 节点名对拍+拷贝到 app/src/main/assets/scene+meta 补 spawn+重投影交互点表+旧场景回归守卫,全程零 Kotlin 改动。当用户说「加场景」「接入新场景」「新场景包」「把 xxx 接入 app」「dream_bedroom_N 接入」时使用;已接入场景的体检用 --check-only。
---

# add-scene:场景资产包 → app 一键接入

运行脚本完成机械部分(校验/拷贝/spawn/投影/回归守卫),模型只做两处判断
(新坐点的循环剪辑选择、校准锚点是否跳过),脚本会把缺口和候选清单打印出来:

```bash
python3 .agents/skills/add-scene/scripts/integrate_scene.py <场景名> [--check-only]
```

路径相对仓库根。`<场景名>` = `assets/` 下的目录名(也是全部文件前缀)。
退出码:0=就绪;1=包校验失败;2=已拷贝但投影配置有缺口(补完 gen 脚本重跑)。

## 前提:资产包约定

新建场景须按 `assets/dream_bedroom_2/README.md` 的交付约定(Blender 端制作,
本技能不管建模导出):`assets/<场景名>/` 内五件套齐全——

| 文件 | 要求 |
|---|---|
| `<场景名>.glb` | 米制 +Y 向上,室内可独立导入 |
| `<场景名>.meta.json` | offsetX/Y/Z + bounds(可走矩形);spawn 由本脚本补,勿手写 |
| `<场景名>.cutaway.json` | `groups[].node + renderableNodes`(防遮挡剖切组) |
| `<场景名>.anchors.json` | `anchors[].name/x/z/links`(MoveAnchor 导航图) |
| `interactions.json` | authoring 文档,GLB 坐标(坐标换算脚本做) |

## 流程

1. **跑脚本** → 包校验失败(退出 1)先回 Blender 侧修;常见失败=cutaway 引用的
   节点名在 GLB 里不存在、sit/lie 锚点没有配对的 *_APPROACH 点、
   sdkPlacement 与 meta 偏移打架。
2. **退出 2 = 需要判断的缺口**,按打印的指令改 `tools/gen_dream_interactions.py`:
   - `SCENES` 加一行(机械);
   - `SIT_LOOP` 给新坐点配循环剪辑——从 `app/src/main/assets/animations/06_坐姿/`
     按家具气质挑(先例:床=Sit With Fidgeting Feet、书桌=Hands On A Table、
     窗边长凳=Bench Swinging Her Legs);
   - `SKIPPED_ANCHORS` 加校准/抓握参考点(kind 是 stand/approach/waypoint/sit/lie
     之外的,如 lie_target/grasp)。
3. **重跑脚本**收尾(幂等:拷贝/meta 已就位的步骤自动跳过)。回归守卫会确认
   重投影没有改动其他场景的 interactions.json。
4. **真机验证**(脚本结束打印六步清单:场景面板出现/开局面向相机/走位/
   坐下起身/剖切/帧率)。

## 行为约定(固化自两次接入的教训)

- **interactions.json(投影 JSON)永不手改**——它是 `gen_dream_interactions.py`
  的生成物,手改会被下次重跑冲掉;要改语义改 gen 脚本或资产包源文件。
- **spawn.yawDeg 恒为 0**(面向相机):SDK 相机固定在 +Z 侧看向房内,资产锚点
  的 180 是房间设计朝向(朝窗),照抄=开局背对相机(911c28f 修过的坑)。
- **零 Kotlin 改动是常态**:场景面板扫 `scene/*.glb` 自动发现,剖切/导航/交互/
  出生点全按 `<场景名>.` 前缀解析。只有用户明确要换默认场景才改
  `MainActivity.kt` 的 `DEFAULT_SCENE`。
- **大面外景/背景板不得进 cutaway groups**(AABB 罩住房间=全机位误判,坑③);
  脚本对 >12m 量级的组会提示人工判断。
- `--check-only` 给已接入场景做幂等体检(如怀疑 sidecar 被误改时)。

## 手动验证(出问题时)

```bash
# 五件套落位与内容
ls app/src/main/assets/scene/ | grep <场景名>
python3 -c "import json;print(json.load(open('app/src/main/assets/scene/<场景名>.interactions.json'))['anchorCount'])"
# 重新生成全部场景的交互投影(校验 gen 脚本健康)
python3 tools/gen_dream_interactions.py && git diff --stat app/src/main/assets/scene/
```
