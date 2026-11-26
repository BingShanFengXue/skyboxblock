# Sky Box Block · 天空盒方块

> 一个能「装天空」的方块。放下它，看向它 —— 你看到的是**独立的天空**：由模组自己生成的日出日落与立体云，或你放进配置目录的全景图。它不抓取地形，也不依赖光影包的外部天空。

**作者：Bing6512** · 模组 ID：`skyboxblock` · 许可：**MIT**（第三方素材另有协议，见文末）

---

## 这是什么

原版的天空是全局的、不可放置的。Sky Box Block 把天空做成一个方块：

- 站在方块外面看，它是普通方块；
- 看向方块内部，你看到的是**一整片天空** —— 有太阳、月亮、星空和独立的立体云，或你指定的 360° 全景；
- 天空由模组在私有渲染目标里生成，走独立的深度与不透明遮挡，**不与光影包的外部天空共享状态**。

默认提供 9 种天空盒，支持自定义全景（PNG / HDR / EXR / JPG），支持服务端统一下发环境属性与按玩家覆盖，也支持实验性的 HTML 网页全景。

---

## 支持版本与加载器

| Minecraft | 加载器 | 产物 | 运行环境 |
|---|---|---|---|
| **1.21.1** | Fabric | `skyboxblock-<版本>-1.21.1.jar` | Java 21 |
| **26.2** | Fabric（Loader 0.20.1+） | `skyboxblock-<版本>-mc26.2.jar` | Java 25 |

**客户端与服务端都需要安装**本模组和 **Fabric API** —— 方块注册是服务端权威的；纯视觉代码只在客户端执行。未安装的客户端不会收到同步。

> `26.2` 版本目前是 **OpenGL 路径**的移植测试版。Vulkan、26.1、26.3 未标记为已适配。

---

## 九种天空盒方块

全部按**连续世界视线**投影，不会把整张全景重复缩到每个面。

| ID（前缀 `skyboxblock:`） | 中文名称 | 转换染料 |
|---|---|---|
| `sky_block` | 天空盒方块（原版） | 基础合成 |
| `white_sky_block` | 天空盒方块（纯白） | 白色 |
| `black_sky_block` | 天空盒方块（纯黑） | 黑色 |
| `kloppenheim_sky_block` | 天空盒方块（晴空·A） | 淡蓝色 |
| `syferfontein_sky_block` | 天空盒方块（晴空·B） | 蓝色 |
| `dusk_sky_block` | 天空盒方块（黄昏） | 橙色 |
| `galaxy_sky_block` | 天空盒方块（银河） | 紫色 |
| `lush_sky_block` | 天空盒方块（葱郁行星） | 绿色 |
| `apocalypse_sky_block` | 天空盒方块（末日陆地） | 红色 |

> 纯白与纯黑是**无纹理的颜色乘子**，用于阴影／雾的颜色控制，不是羊毛或混凝土材质。

**合成**

- 原版天空盒：8 个玻璃围绕 1 个末影之眼
- 另外 8 种：1 个原版天空盒 + 表中对应染料（无序合成）
- 创造模式标签页「天空盒方块」列出全部 9 种

```mcfunction
/give @s skyboxblock:sky_block
/give @s skyboxblock:galaxy_sky_block
/give @s skyboxblock:apocalypse_sky_block
```

---

## 自定义天空（用你自己的全景图）

在实例目录下创建：

```
config/skyboxblock/skyboxes/
```

放一个 `.conf` 和它引用的图片即可，**重启游戏**后生效。

- 只读、**不递归**，只收 `.conf` 与图片
- 单配置文件 ≤ 64 KB；最多 64 个配置／64 种风格
- 单张图片 ≤ **4096×2048**；PNG ≤ 16 MiB，HDR ≤ 40 MiB
- 支持的图片格式：**PNG / Radiance HDR / OpenEXR（含 PIZ 压缩）/ `.tx` / JPG**
- 首次运行会生成 `00_example.conf`（默认 `enable = false`）和两张示例全景
- `enable = false` 时完全不扫描、不注册，行为与纯原版一致
- 资源注入走 `MultiPackResourceManager` 注入点，**不写 `options.txt`**、不改你的玩家设置

**限制**：自定义方块在启动时注册，改配置需重启；客户端与服务端的 `skyboxes/` 内容必须一致；从配置里删掉某个 id，存档里该方块会变成空气。

---

## HTML 网页全景（实验）

自定义天空可以用 `.html` / `.htm` 作为静态全景源：客户端显式启用后，由**本机 Chromium** 截取 2:1 的页面快照，再走现有纹理渲染路径。

**默认关闭**。服务器只注册相同样式 ID 并使用占位纹理。需要本机有 Chromium 内核浏览器（实测 Java 25 + Edge）。

---

## 指令

**客户端**

```mcfunction
/skyboxcustom              # 打开自定义天空配置目录
/skyboxcustom list         # 列出已加载的自定义风格
```

**服务端**（权限等级 2，查询对所有玩家开放）

```mcfunction
/skyboxblock env <属性> <值>                       # 15 项原版环境属性，服务端 world 级权威
/skyboxblock env render_skybox false               # 隐藏全服的天空盒画面（方块保留碰撞）
/skyboxblock player @s render_skybox true|false    # 只切换当前玩家的可见性
/skyboxblock player <目标> show|set|reset|time ... # 按玩家覆盖环境属性与私有天空时间
/skyboxblock cloudheight 240                       # 独立云层高度，reset 恢复 192
```

颜色统一写 `#RRGGBB` 或 `#AARRGGBB`，其他写法（如 `0x…`）一律拒绝。

---

## 配置

`config/skyboxblock.properties`：

```properties
# 仅为原版天空盒方块内部的立体云，不影响世界或光影的云层
clouds=true
vanillaCloudHeight=192.0
debug=false
```

优先级：**默认值 < properties 文件 < 显式 JVM 参数**（例如 `-Dskyboxblock.clouds=false`）。

---

## 渲染开关快捷键

客户端新增「切换天空盒渲染」按键，**默认 `U`**，可在「选项 → 控制 → 天空盒方块」改键。它只切换你自己，并发送与 `/skyboxblock player @s render_skybox true|false` 相同的服务端指令；等当前连接的服务端快照确认后才允许下一次切换。

---

## 已知限制

- 巨构（megastructure）实验路线已归档为废案，不参与当前生产渲染，源码与资源仅作历史参考
- 门户内部与嵌套视角的最终颜色恢复，根视角之外单独跳过
- 玻璃、水等透明前景的最终合成
- 只对**实测过的组合**给出结论：Iris 1.8.1 / Sodium 0.6.7 / iterationRP Alpha 0.8.31 / Distant Horizons 3.3.3。其他光影包和全部动态分辨率方案未覆盖
- 方块天空独立于光影包，**不承诺**与外部天空的颜色、后处理或云形一致
- 26.2 版为 OpenGL 路径测试版

---

## 渲染架构（简述）

1. 独立天空生成器读取时间、天气、维度和生物群系颜色，在私有渲染目标中生成背景、日月星与立体云 —— **不抓取地形或光影的天空**。
2. BER 收集世界相机相对坐标的真实方块面；世界阶段渲染器在不透明地形之后、Iris 手部与透明层之前绘制颜色与深度，遮挡由 GPU 完成。
3. Iris 适配层读取实际 FSR 比例与 TAA jitter，分别维护完整 GL viewport 与左下角有效光栅，避免重复缩放或错读深度。
4. Iris 合成结束后，用同一组世界空间面与手部绘制后的深度恢复最终颜色，避免灰白化。
5. 独立的 GL 状态保护层同时保存／恢复 Iris 的 `isMainBound`，隔离私有绘制期间的 Iris 世界着色器替换，结束后按嵌套层级恢复。

---

## 许可与署名

本项目代码与原创资源采用 **MIT**，见仓库 [`LICENSE`](https://github.com/BingShanFengXue/skyboxblock/blob/main/LICENSE)。完整分类与核查限度见 [`THIRD_PARTY_NOTICES.md`](https://github.com/BingShanFengXue/skyboxblock/blob/main/THIRD_PARTY_NOTICES.md)。

**第三方素材**

- **CC0 1.0** — Poly Haven 的 `kloppenheim_06_puresky`、`syferfontein_6d_clear_puresky`、`qwantani_dusk_2_puresky`（Greg Zaal / Jarod Guest）；Screaming Brain Studios 的 `planet-surface-skyboxes`；VertexZero 的 `retro-skyboxes-pack`（OpenGameArt）
- **CC BY 4.0（必须署名）** — 银河素材来自 Solar System Scope

  > Night sky textures by Solar System Scope (solarsystemscope.com), licensed under CC BY 4.0 — <https://creativecommons.org/licenses/by/4.0/>
  >
  > 改动说明：原图经提亮（gamma 0.55）后以 Lanczos 缩至 4096×2048 RGB PNG；对应的方块贴图与物品图标由该图派生，同受 CC BY 4.0 约束。

早期结构设计参考 [Literal Sky Block](https://github.com/latvian-dev/literal-sky-block)（2022 Kristiāns Micītis / MIT），当前渲染管线已重写，不宣称是其完整移植。

**AI 协作**：早期部分实现由 DeepSeek V4.1 Flash 辅助；R35–R36 与 R40–R41 阶段的实现、验证与文档由 ChatGPT、GPT6.1SOL 与 GPT-6.2 SOL 路由上的工程子任务辅助完成。均为运行时路由标签，不构成独立版权主张。人机分工逐条记录在仓库 [`AUTHORS.md`](https://github.com/BingShanFengXue/skyboxblock/blob/main/AUTHORS.md)。

---

## 链接

- 源码仓库：<https://github.com/BingShanFengXue/skyboxblock>
- 版本下载：<https://github.com/BingShanFengXue/skyboxblock/releases>
- 设计文档：仓库内 `docs/` 目录（每轮功能、验证与边界都有独立报告）
