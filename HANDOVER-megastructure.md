# 天穹巨构（路线 A）交接说明

> 最后更新：2026-11-22
> 状态：**废案归档：不参与正式运行；源码、资源和历史验证记录保留**

---

## 一、一句话总结

把 `skybox-test` 里的「恒星引擎·天穹巨构」作为**历史实验**接进过 Minecraft（路线 A）；
当前生产客户端已移除 shader 注册并固定停用，以下内容只用于回溯旧实验。

---

## 二、当前部署状态

### 历史部署记录（非当前生产）

```
D:\Program Files\Game\Minecraft\PCL\.minecraft\versions\Rapid Optimization\mods\
  skyboxblock-1.0.8-test.2-html-1.21.1.jar
  17,684,096 字节
  SHA256 381F86FC0631C5FFFB4999205F78895F8ECBBBB54C5F341DD9E030189200DAA0
```

这版代号 **V5**，包含：环心旋转修正 + 恒星辉光收敛。

### 备份链（按时间倒序，都在同目录）

| 文件 | 内容 |
|---|---|
| `*.MEGA-V4.bak` | V4：背景星云 + 合成修复 + 属性重绑（环绕世界原点转） |
| `*.MEGA-V3-alphaprobe.bak` | V3：子个体安全版（alpha 探针失败即跳过合成） |
| `*.MEGA-V1.bak` | 我第一版（r37 旧几何 3879） |
| `*.MEGA-V1-oldgeometry.bak` | 同上更早一份 |
| `*.BEFORE-MEGA.bak` | **完全没巨构的 1.0.8**（要彻底退回就用它） |
| `*.BROKEN-D51559F6.bak` | 最早未 remap 的坏包，无价值，可删 |

**完整回滚**：
```powershell
$m="D:\Program Files\Game\Minecraft\PCL\.minecraft\versions\Rapid Optimization\mods"
Copy-Item "$m\skyboxblock-1.0.8-test.2-html-1.21.1.jar.BEFORE-MEGA.bak" `
          "$m\skyboxblock-1.0.8-test.2-html-1.21.1.jar" -Force
```

**生产构建已固定停用**：`skyboxblock.mega.enabled` 不再重新启用该废案；需要回看历史实验时使用归档源码与旧构建。

---

## 三、源码位置

工程：`E:\DSHworkspace\DSHminecraft\skybox-mod`（Fabric 1.21.1，`loom.officialMojangMappings()`）

| 文件 | 作用 |
|---|---|
| `src\main\java\dev\yg\skyboxblock\client\SkyMegastructure.java` | 核心：`sky.bin` 解析、顶点展开、FBO 叠加层、`renderLayer()` |
| `src\main\java\dev\yg\skyboxblock\client\SkyWorldRenderer.java` | `drawMegaLayer()`：拿天空方块的面当遮罩合成叠加层 |
| `src\main\java\dev\yg\skyboxblock\client\VanillaSkyScene.java` | 调用点（L131 附近） |
| `src\main\java\dev\yg\skyboxblock\client\SkyboxBlockClient.java` | 历史版本曾注册 `mega_sky` / `mega_backdrop` / `mega_cube`；当前生产入口已移除 |
| `src\main\java\dev\yg\skyboxblock\mega\MegaGeometry.java` | 程序化几何生成（当前**未被渲染路径使用**，可当参考） |
| `src\main\resources\assets\skyboxblock\megastructure\sky.bin` | 4955 个实例的几何数据 |
| `src\main\resources\assets\skyboxblock\shaders\core\mega_cube.vsh/.fsh/.json` | 方块渲染 |
| `...\mega_sky.vsh/.fsh/.json` | 全屏程序化星云背景 |
| `...\mega_backdrop.*` | 全屏贴图背景（需要 `textures/sky/kapran.png`，该图**不存在**，实际走 `mega_sky`） |

### 数据来源

`sky.bin` 由 `E:\DSHworkspace\DSHminecraft\skybox-test\tools\export-sky-bin.mjs` 生成：

```powershell
cd E:\DSHworkspace\DSHminecraft\skybox-test
"C:\Program Files\nodejs\node.exe" tools\export-sky-bin.mjs
```

输出直接落到 `skybox-mod\src\main\resources\assets\skyboxblock\megastructure\sky.bin`。

格式 **SKB2**（小端）：
```
"SKB2" | u32 批次数 | 每批: u8 additive | u32 名长 | 名字(UTF-8) | u32 数量 | 数量×80 字节
每实例 80 字节 = 4 列 mat4(16×f32, 列主序, 第 4 列是平移) + rgba(4×f32)
```

当前 6 批：`cells 2434 / hull 934 / lamp 105 / star 820 / halo 492 / jet 170`，共 **4955**。

---

## 四、构建与部署

```powershell
$env:JAVA_HOME='D:\Program Files\Eclipse Adoptium\jdk-21'
$env:GRADLE_USER_HOME='E:\DSHworkspace\DSHminecraft\skybox-mod\.gradle-home'
cd E:\DSHworkspace\DSHminecraft\skybox-mod
.\gradlew.bat remapJar --no-daemon --console=plain --offline
```

产物成对出现才算走了 remap：`build\libs\<name>.jar` + `build\devlibs\<name>-dev.jar`

**发布前必过**：
```powershell
.\tools\verify-remap.ps1 -Jar build\libs\skyboxblock-1.0.8-test.2-html-1.21.1.jar
# 必须 exit 0 / PASS（named refs 应为 0，intermediary 29，refmap 9 mixin）
```

### ⚠️ 部署铁律

**游戏（`javaw`/`java`）在跑时绝对不能覆盖 `mods\*.jar`。**
Fabric 类加载器是懒加载的，覆盖后 zip 中央目录被改坏，之后任何 `loadClass` 都会抛
`java.util.zip.ZipException: ZipFile invalid LOC header (bad signature)` 直接崩溃。
（2026-11-14 23:42:53 实测踩过，已写进 `AGENTS.md`）

部署后建议先用 `System.IO.Compression.ZipFile` 打开新 jar 逐条读几字节，确认完整再启动。

---

## 五、已经查清的根因（别再重复排查）

### 1. 为什么画了却看不见（最致命）

`world_sky.fsh` 的 `skyColor(uv)` 只在 `SkyStyle == 0` 时采样私有 canvas：
- `== 1` 返回纯白，`== 2` 纯黑，`== 3` 用视线方向采样该样式自己的全景 PNG

而 `SkySkin.shaderMode()` 里**只有原版 `sky_block` 是 0**，6 个内置全景 + **全部自定义（含 HTML）都是 3**。
所以往 canvas 里画的东西，在自定义穹顶下天生不可见。

**现在的做法**：画进独立 FBO 叠加层，合成时把 `SkyStyle` 强制为 0、`Sampler0` 绑叠加层，
于是任何样式的天空表面都会贴上巨构。

### 2. 投影系数放错位置

旧代码 `.m22(-1.0005).m23(-2.0005).m32(-1)`（m33 还是默认 1）
→ `w = 2.0005d + 1`，角度被压 0.5 倍、深度区间塌成 (-1, 0.5)。

正确形式（JOML）：`w` 来自 `m23 = -1`，`m32` 载 `2·far·near/(near-far)`：
```java
new Matrix4f().m00(f/aspect).m11(f).m22(-1.0005001F).m32(-2.0005002F).m23(-1.0F).m33(0.0F)
```
near 1.00025 / far 4000，正好对上 `FIT_RADIUS = 3600`。

### 3. 相机平移没减

`frame.view` 只含旋转（原版天空姿态无平移），直接用绝对世界坐标只有相机在 (0,0,0) 才对。
必须 `mvp = deep · view · T(-camera)`。

### 4. 叠加层清不成透明

`TextureTarget.clear()` 不保证清成 alpha 0，实测整层读回 `alpha=255 / RGB=0`。
**现在的做法不是修它，而是绕开**：背景 pass 把整层铺成不透明星云，合成直接替换天空，
不再依赖逐像素 alpha。

### 5. 戴森云「整体公转」（V5 已修）

`mega_cube.vsh` 的 `spin()` 绕**过世界原点的轴**旋转，而环心在 `(0, 147.1, -490.4)`，
离那条轴 490 格 → 整个环绕原点甩大圈。

现在：`p = Pivot + spin(p - Pivot, RingAngle)`，`Pivot` 由 Java 从 `star` 批次质心算出
（以后重导 `sky.bin` 不用改代码）。

---

## 六、继承的坑（改代码前先看）

| 坑 | 说明 |
|---|---|
| `ps1` 编码 | PS 5.1 读无 BOM 的 `.ps1` 按 GBK 解析，中文会语法错误。**`.ps1` 一律纯 ASCII** |
| `batchData` | `build()` 里已有 `byte[] data`，新增局部变量不能用 `data` |
| `attribute()` 依赖当前绑定的 `GL_ARRAY_BUFFER` | 所以 `glBindBuffer(vbo)` 必须在它之前 |
| `ShaderInstance.clear()` 会清掉 sampler | 每个批次循环内都要重新 `setSampler` |
| `StarDir` uniform | 在 `mega_cube.fsh` 里只声明未使用，被 GLSL 优化掉 → 启动日志会报 `could not find uniform named StarDir`，**无害** |
| r37 手工 jar | `skybox-test\out\skyboxblock-r37-manual.jar` 里有旧版实现，`git` 里没有。已反编译到 `E:\DSHworkspace\DSHnormal\_diag\remap\named-src\` |

---

## 七、还没做的（按性价比排序）

### 批次 B — 几何造型（性价比最高）

动 `skybox-test\src\scene.js` 的 `buildScene({radius, rings, density, engineOn})`，然后重导 `sky.bin`：

- **环密度/分层**：现在是厚实高密度点阵盘；参考（`preview\hero.png`）是**稀疏多层细轨道、孔隙大**。降 `density`、加 `rings` 分层
- **恒星尺度**：现在归一化后约 82px，参考约 60-65px → 缩 20~30%
- **盘厚与倾角**：参考轴比约 0.87（近正面、长轴近竖直），现在约 0.75、倾斜 -55°

### 批次 C — 星云分布

调 `mega_sky.fsh`：加厚橙红结构云带覆盖主体后方、压低右下那块过亮的蓝白斑（参考 `preview\r25-flame-nebula.png`）

### 批次 D — 光束（成本最高，放最后）

参考里有**一道从恒星/发动机贯穿到画外的青白长束**，现在完全没有。

原因：`scene.js` 的 `buildScene()` 返回两个数组，`beams` 从来没被导出过：
```js
return { batches, beams: new Float32Array(beamLines) };   // beams = 267 段
```
`export-sky-bin.mjs` 只写了 `scene.batches`。

需要：① 扩导出格式（`SKB3` 或在 `SKB2` 后追加 beam 段）；② 新增 billboard 渲染 pass + `mega_beam` 着色器。
每段字段：`a(x,y,z),0, b(x,y,z),0, radius, alpha, seed, taper, color(r,g,b), style`。

**放最后的原因**：光束的对准方向要靠环的最终朝向去验证，环没定型就做光束容易白做。

### 批次 E — 发动机细节

现在偏大、偏灰黑、线框感重。缩比例 + 提升青色功能件局部对比。

---

## 八、诊断输出（保留着，别删）

启动/运行时日志会打：

```
[skyboxblock] megastructure: 6 batches, 4955 cubes, scale 1.0
[skyboxblock] mega pivot from batch star: (0.0,147.1,-490.4) over 820 instances
[skyboxblock] mega layer target WxH fbo=.. tex=.. format=0x..        ← 必须 0x8058 (RGBA8)
[skyboxblock] mega layer draw ... status=0x.. program=.. position=..  ← status 0x8cd5，location 不能 -1
[skyboxblock] mega layer clear produced alpha N ...                   ← 出现也不影响（已绕开）
[skyboxblock] mega composite batches=.. vertices=.. err=0
[skyboxblock] mega layer dumped: <游戏目录>\skyboxblock-mega\mega-layer-*.png
```

**最后一条最有价值**：它把渲染层直接存成 PNG，不用猜屏幕。

### 玩家该看哪

结构在世界坐标中心约 `(0, 150, 490)`，半径 ≤ 730（含 jet 到 1053）。
离线扫过 **yaw 340°~60° 才可见**，yaw=20°/pitch=0 时 97% 顶点在屏内。
**yaw=148° 时是 0%**（之前截图就踩这个坑）。

---

## 九、相关文件索引

```
E:\DSHworkspace\DSHminecraft\skybox-test\          网页预览器源码（scene.js 是几何权威）
E:\DSHworkspace\DSHminecraft\skybox-test\preview\  参考效果图（hero.png 是主参考）
E:\DSHworkspace\DSHnormal\_diag\remap\named-src\   r37 jar 反编译出的 Mojang 名源码
E:\DSHworkspace\DSHnormal\_diag\r37_recover\       r37 jar 里提取的原始资源
E:\DSHworkspace\DSHnormal\_diag\mega_next_patch.md 子个体留的判据表
E:\DSHworkspace\DSHnormal\_diag\mega_visible.log   真实游戏日志证据
E:\DSHworkspace\DSHnormal\_diag\mega_preview.png   离线渲染预览（黑底，参考价值有限）
```

---

## 十、如果只想快速确认"还能跑"

1. 启动游戏，`F3` 看 yaw，转到 **yaw≈20° / pitch≈0**
2. 应该看到：暗棕橙星云 + 散落星点 + 灰白点阵圆盘 + 橙色恒星在盘心 + 右侧桁架引擎
3. 环应该**在自己平面里缓慢自转**（约 66 秒一圈），恒星不应该跟着跑
