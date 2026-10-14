# VRM 资产查看器(model-viewer)

浏览 / 预览 / 筛选 / 删除 `app/src/main/assets/` 下内置的 VRM 模型(.vrm)、VRMA 动作(.vrma)与 GLB 配饰道具(.glb,P2 配饰系统)的本地网页工具。基于 three.js + three-vrm 渲染,零 npm 依赖,克隆即用。

## 启动

```bash
cd model-viewer
node server.js          # 或 npm start
# 浏览器打开 http://127.0.0.1:8173
```

端口可用 `PORT=xxxx node server.js` 改。

## 功能

- **网格卡片浏览**:文件夹 + 模型 + 动作混排,文件夹显示递归资产计数,模型卡片带缩略图(从 GLB 内提取,无声明缩略图的模型回退到 thumbnail/icon/face 贴图)。
- **3D 预览**:右侧视口实时渲染,OrbitControls 环绕/缩放,启动时自动加载 `vrms/` 下第一个模型。点击模型卡片切换模型,点击动作卡片即在当前模型上播放(循环),底部条可停止/重置视角。VRM 0.x 与 1.0 模型均支持(0.x 自动转向统一面向镜头)。普通 GLB(配饰道具)走「道具预览」模式:静态网格渲染+按包围盒自动取景(道具是厘米级小物,不取景则 3m 机位看不见),无骨骼动画语义。
- **文件夹跳转**:面包屑任意层级跳转;`animations/` 下的中文分类目录照常进入。
- **全局搜索**:搜索框输入即对整个 assets 递归搜索(防抖 300ms),结果卡片显示所在子目录,可直接播放/查看任意深处文件;类型筛选(全部/模型/动作)对目录浏览和搜索同时生效。
- **删除**:卡片悬浮出现 🗑,确认后把文件移入 `model-viewer/.trash/`(带时间戳前缀,不直接销毁,误删可拷回),列表自动刷新。只允许删 `.vrm` / `.vrma`,且不能删文件夹。

## 结构

```
model-viewer/
├── server.js           # 零依赖 Node 服务器:静态页面 + /api/list|search|file|thumb|delete
├── setup-vendor.sh     # 从 three-vrm 仓库刷新 vendor(常规无需运行)
├── public/
│   ├── index.html      # importmap 指向 /vendor
│   ├── style.css
│   ├── app.js          # three-vrm 渲染 + 文件管理 UI
│   └── vendor/         # three@0.180.0 + @pixiv/three-vrm@3.5.1(含 three-vrm-animation),已内置
└── .trash/             # 删除文件的暂存区(git 忽略)
```

## 说明与注意

- **vendor 来源**:`public/vendor/` 内文件拷贝自本地 three-vrm 仓库(`/home/neethu/projects/mtoon/three-vrm`,yarn build 产物),已随仓库提交,无需安装依赖;换版本时跑 `./setup-vendor.sh [three-vrm仓库路径]`。
- **API 与发布版 three-vrm 的差异**:本仓库版 three-vrm-animation 构建剪辑用独立函数 `createVRMAnimationClip(animation, vrm)`,不是 npm 发布版的实例方法 `animation.createAnimationClip(vrm)`,app.js 已按本仓库 API 调用。
- **删除 = 移入 .trash**:删除只影响仓库工作区,下次构建 APK 后 app 内才不包含该文件。彻底清理请删 `.trash/` 内容。
- **缩略图提取**:`/api/thumb` 直接从 GLB 二进制的 JSON chunk + BIN chunk 里抠图,VRM 1.0 读 `VRMC_vrm.thumbnailImage`,VRM 0.x 读 `VRM.meta.texture`,都缺失时按图片名回退,再不行前端显示占位图标。
- 查看器只读访问 assets 下 `.vrm`/`.vrma`;其余文件(贴图、task 模型等)不可通过 API 读取,路径均做了越界防护。
