# 配饰示例资产（accessory-samples）

> 配套文档：[docs/accessory-feasibility.md](../../docs/accessory-feasibility.md)（可行性调研与落地方案）。
> 本目录是调研期下载/生成的示例资产；P0 落地时 `phone.glb` 已转正进 `app/src/main/assets/accessories/`。
> 全部资产已用 `node validate-glb.js` 校验（面数/贴图/包围盒/压缩扩展/骨骼动画检查）。
> ⚠高模二进制（Khronos 6 件 + Kenney zip + SciFiHelmet，约 90MB）经 `.gitignore` 只留本地，来源 URL 见各 `*.README.md`。

## 资产清单

| 文件 | 尺寸 | 面数 | 包围盒(单位) | 许可证 | 来源 | 演示用途 |
|---|---|---|---|---|---|---|
| `phone.glb` | 3KB | 24 | 0.068×0.142×0.008 | CC0（本仓库自制） | `make-phone-glb.js` 生成 | **手持手机（P0 主验收道具）** |
| `props/radio.glb` | 25KB | ~1k | 0.315×0.228×0.098 | CC0 | Kenney Furniture Kit | 手持/桌面道具（unlit→PBR 自动兼容实证） |
| `props/computerMouse.glb` | 6KB | 226 | 0.050×0.024×0.085 | CC0 | Kenney Furniture Kit | 手持小道具 |
| `props/kitchenBlender.glb` | 19KB | 736 | 0.172×0.160×0.132 | CC0 | Kenney Furniture Kit | 中型道具 |
| `kenney_furniture-kit.zip` | 5MB | 137 个 GLB | — | CC0 | [kenney.nl/assets/furniture-kit](https://kenney.nl/assets/furniture-kit) | 低模道具库（TV/音箱/厨具等，原生 GLB 7~35KB/个） |
| `Avocado.glb` | 7.7MB | 812 | 0.043×0.063×0.028 | CC0 | [Khronos glTF-Sample-Assets](https://github.com/KhronosGroup/glTF-Sample-Assets) | 手持搞笑道具（管线验证） |
| `WaterBottle.glb` | 8.6MB | 5k | 0.109×0.260×0.109 | CC0 | 同上 | 手持水瓶 |
| `BoomBox.glb` | 10.1MB | 7k | 0.020³（极小！） | CC0 | 同上 | 尺度各异实证 |
| `Lantern.glb` | 9.1MB | 8k | 15.5×25.7×4.6（巨大！） | CC0 | 同上 | 尺度各异实证 |
| `AntiqueCamera.glb` | 16.7MB | 42k | 5.96×12.6×4.56 | CC0 | 同上 | 手持相机（高模反面教材） |
| `DamagedHelmet.glb` | 3.6MB | 29k | 1.89×2.0×1.80 | **CC-BY 4.0**（ctxwing） | 同上 | 头戴头盔（注意署名义务） |
| `SciFiHelmet/` | glTF 外置 4 文件 | — | — | CC0 | 同上 | 「glTF 外置格式」样例（SDK 需打包成 GLB） |

每个 Khronos 资产的 `*.README.md` 是官方许可证/出处存档。

## 关键实测结论

1. **尺度完全不可信**：同为"手持道具"，BoomBox 建模 2cm、Lantern 25.7 单位——运行时**每资产 scale 校准是必选项**（方案见可行性文档 §5.3）。
2. **Kenney 道具全用 `KHR_materials_unlit`**：引擎 `GlbMorphPatcher` 恰好会剥除 unlit 转 PBR（`corelib/.../internal/GlbMorphPatcher.kt`），即 Kenney 道具**开箱即用**。
3. **体积两极分化**：Kenney 6~25KB（理想内置配饰）vs Khronos 8~17MB（只适合管线验证）。内置资产门槛建议 <2MB/个。
4. `phone.glb` 是「配饰资产规范」的活模板：米制单位、Y-up、正面朝 +Z、静态网格、PBR 材质、无骨骼无动画、发光屏用 `emissiveFactor`。

## 工具

- `validate-glb.js`：零依赖 GLB/glTF 校验器（JSON chunk 解析、面数/贴图统计、包围盒、DRACO/KTX2/unlit/骨骼动画告警）。用法：`node validate-glb.js [目录|文件]`。
- `make-phone-glb.js`：程序化生成 `phone.glb`（改几何参数可再造其他简单道具）。
