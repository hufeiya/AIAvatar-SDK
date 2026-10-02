# 弹簧骨骼模拟与验证工具

完整上下文与使用说明见 [`docs/springbone-debug-context.md`](../../docs/springbone-debug-context.md)(项目根 docs 目录)。

## 快速使用

```bash
# 主力:10.vrm + 真实VRMA + 全部修复的回归(注意:文件尾部有多个实验块,整跑需几分钟)
python3 sim_vrma_pipeline.py

# Twist_Sample 拖动语义(root/hips、快慢拖、回弹)
python3 sim_twist_drag.py

# 10.vrm center 语义回归(静止/平移/传送,期望:零反应)
python3 sim_center_regression.py
```

- 脚本内模型路径为绝对路径(`app/src/main/assets/vrms/`、`~/projects/three-vrm/...`),换机需调整。
- `node_run_twist.ts`:在 three-vrm 仓库内用 esbuild 打包后无头运行官方库拿 ground truth(方法见文件头注释)。
- **模拟真机必须用可变 dt**(脚本内已内置),固定 1/60 不会复现真机问题。
