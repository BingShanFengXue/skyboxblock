# 自定义天空盒 HDR 支持性测试报告

**日期**：2026-11-07
**范围**：只测试，不改任何源码、不改任何 jar、不改用户实例
**被测对象**

| 目标 | 产物 | SHA-256 | 备注 |
|---|---|---|---|
| 1.21.1 主线 R38 | `skybox-mod/build/libs/skyboxblock-1.0.5-test.1-r38.jar` | `2904bb8d35e93b2d0effefd625c60d348ac2cfe6e9783ae11fc8f3ed94110817` | 与 PCL 实例 `Rapid Optimization/mods` 中的同名包**逐字节相同** |
| 26.2 移植 port.2 | `skybox-mod-26.2/output/skyboxblock-1.0.6-test.1-port.2-mc26.2.jar` | `45c256728cdac6a75f3e71b1a1b6af1335de53e753702aa3dee79aa450c5daa8` | 与 PCL 实例 `26.2-Fabric 0.20.1/mods` 中的同名包**逐字节相同** |

两个 jar 的 `dev/yg/skyboxblock/custom/*` 类分别是从这两个 jar 里**原样解出来**跑的，
不是从源码重新编译的近似实现。

---

## 一、结论（先说结果）

**当前自定义天空盒功能不支持 HDR。**

三道独立闸门，任一卡住即不成立（三处**都**卡住了）：

1. **输入侧**：`picture` 只接受扩展名 `.png`；真正能解码的格式只有
   `ImageIO.read()` 支持的 `png / jpeg / bmp / gif / tiff / wbmp`，
   JDK 自带 ImageIO **没有 Radiance (.hdr) 也没有 OpenEXR (.exr) 解码器**。
   真 HDR 文件即使改名成 `.png` 也会在解码处被拒。
2. **处理侧**：即使喂进 16bit/通道 PNG（唯一被接受的高位深格式），
   `CustomSkyRegistry.materialise()` 立刻把图像搬进 `new BufferedImage(w, h, TYPE_INT_ARGB)`
   —— 每通道 8 bit，然后 `ImageIO.write()` 出 8bit PNG。**精度在处理第一步就被压掉了**。
3. **上传侧**：Minecraft 自身的纹理上传只有 8bit 路径。逐条字节码核对：
   - 1.21.1 `NativeImage$InternalGlFormat` 的 `glFormat` 常量只有 `6408`(RGBA) / `6407`(RGB) / `6403`(RED) / `33319`；
   - 1.21.1 `TextureUtil.prepareImage` 的像素类型是 `sipush 5121` = `GL_UNSIGNED_BYTE`；
   - 1.21.1 `NativeImage._upload` 最终走 `GlStateManager._texSubImage2D`，数据源是 `Format` 那套 8bit 偏移表；
   - 26.2 `ReloadableTexture.doLoad` 直接 `GpuFormat.RGBA8_UNORM` + `GpuDevice.createTexture`，
     `GlConst.GL_RGBA8 = 32856`。

   结论：即便图片进来是浮点，**也没有承载 >1.0 的纹理通道**，更没有 HDR 目标。

1.21.1 与 26.2 在这三处行为**完全一致**：同一素材、同一配置，两版生成的纹理
SHA-256 逐字节相同（见 §三）。

---

## 二、代码级依据（可逐行核对）

### 1.21.1

| 位置 | 内容 |
|---|---|
| [`SkyConfigParser.java:332`](../src/main/java/dev/yg/skyboxblock/custom/SkyConfigParser.java#L332) | `if (!picture.toLowerCase(Locale.ROOT).endsWith(".png")) return "picture 必须以 .png 结尾（大小写不敏感）";` |
| [`CustomSkyRegistry.java:166-172`](../src/main/java/dev/yg/skyboxblock/custom/CustomSkyRegistry.java#L166-L172) | `image = ImageIO.read(input);` → `image == null` 时记「图片无法解析（需要 PNG）」并跳过 |
| [`CustomSkyRegistry.java:193`](../src/main/java/dev/yg/skyboxblock/custom/CustomSkyRegistry.java#L193) | `new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)` ← 8bit/通道 |
| [`CustomSkyRegistry.java:183`](../src/main/java/dev/yg/skyboxblock/custom/CustomSkyRegistry.java#L183) | `ImageIO.write(wrapped, "PNG", textureFile.toFile());` ← 输出 8bit PNG |
| [`CustomSkyRegistry.java:177`](../src/main/java/dev/yg/skyboxblock/custom/CustomSkyRegistry.java#L177) | 非 2:1 时只 `WARNINGS.add("图片不是 2:1 等距柱状…")`，不拒绝 |

### 26.2 移植

[`skybox-mod-26.2/src/main/java/dev/yg/skyboxblock/custom/CustomSkyRegistry.java:161-186`](../../skybox-mod-26.2/src/main/java/dev/yg/skyboxblock/custom/CustomSkyRegistry.java#L161-L186)
与 1.21.1 的同名文件除 `Identifier` / `ResourceLocation` 改名、`pack_format` 与物品模型入口外，
**图片处理逻辑逐行相同**。`SkyConfigParser.java` 两版 SHA-256 完全相同
（`A5F5777BC1F1ACF0...`）。

### 运行时着色

自定义风格走 `SkySkin.custom(...)` → `shaderMode 3`（全景等距柱状采样）。
三条颜色路径（`world_sky.fsh`、`sky_material_background.fsh`、`sky_resolve.fsh`）
都只有：

```glsl
return textureLod(Sampler0, vec2(u, v), 0.0).rgb;
```

采样结果按 sRGB 贴图处理；Iris 背景下再 `pow(max(rgb,0), vec3(2.2)) * (Gain / exposure)`
（[`sky_material_background.fsh:67`](../src/main/resources/assets/skyboxblock/shaders/core/sky_material_background.fsh#L67)）。
`Gain` 默认 1，是个**标量统一提亮**，不是逐像素 HDR 重建——
它不能把已经裁掉的高光还原回来。

---

## 三、实测矩阵（离线，直接跑生产 jar 的类）

测试台：`tmp-hdr-support-probe/`（stub 掉 MC 的 `class_2960`/`Identifier` 与 `SkyboxBlockMod.LOGGER`，
其它字节码一律来自生产 jar）。用例目录 `tmp-hdr-support-probe/runs/<case>/config/skyboxblock/skyboxes/`。

| # | 输入素材 | 实际内容 | 1.21.1 R38 | 26.2 port.2 | 生成纹理 |
|---|---|---|---|---|---|
| c1 | `01_png8_srgb.png` | 2048×1024 8bit RGB PNG | ✅ 注册 1 种 | ✅ 注册 1 种 | 8bit RGBA，2048×1024 |
| c2 | `02_png16.png` | 2048×1024 **16bit/通道** PNG，6.1 MB | ⚠ 注册，但**降到 8bit** | ⚠ 同 | 8bit RGBA，与 c1 **哈希相同** |
| c3 | `03_radiance_hdr_renamed.png` | 真 Radiance .hdr（1024×512）改名 | ❌「图片无法解析（需要 PNG）」 | ❌ 同 | 无 |
| c4 | `04_radiance_hdr_real.hdr` | 真 Radiance .hdr 原名 | ❌「picture 必须以 .png 结尾」 | ❌ 同 | 无 |
| c5 | `05_radiance_hdr4k_renamed.png` | 4K .hdr（17,385,661 B）改名 | ❌「超过 16 MB，已跳过」 | ❌ 同 | 无 |
| c6 | `06_float32_tiff_renamed.png` | float32 TIFF 改名 | ❌「处理图片失败：Index 65694 out of bounds for length 65536」 | ❌ 同 | 无 |
| c7 | `07_jpeg_renamed.png` | JPEG 改名 | ✅ 注册（ImageIO 按内容嗅探） | ✅ 注册 | 8bit RGBA |
| c8 | `08_oversize_4098.png` | 4098×2048 | ❌「图片超过 4096x2048」 | ❌ 同 | 无 |
| c9 | `09_gray16.png` | 16bit 灰度 | ✅ 注册，但变灰阶 | ✅ 同 | 8bit RGBA |
| c10 | `10_png16_sub_lsb_band.png` | 16bit，整幅只跨 `0x8000..0x80FF`（1 个 8bit LSB） | ⚠ **输出只剩 1 个台阶** | ⚠ 同 | 8bit；行内只有 1 个不同值 128 |
| c11 | `11_png16_full_ramp.png` | 16bit，0..65535 完整斜坡 | ⚠ **输出只剩 252 个台阶** | ⚠ 同 | 8bit；行内 252 个不同值（2..253） |

- c1 vs c2 的生成纹理 SHA-256 **完全相同**：`47559E4AB960A394BBC5981B8111A8FC0A7204EEFFDD3A5BDC06960C252F7B60`。
  这意味着 **16bit 源图和 8bit 源图在模组里产出完全一样**，高位深信息被彻底丢弃。
- c10/c11 两版生成哈希分别相同（`48BC84B0…` / `7AF12EC3…`），再次确认两版图片链路等价。
- 每次生成的 PNG 的 IHDR 都是 `bitDepth=8, colorType=6`（RGBA），解码后每通道极值上限 255。

### 精度量化

输入侧用裸 zlib 解 PNG 逐样本读（`tmp-hdr-support-probe/check-input-16bit.py`，不走 Pillow 的 8bit 视图），
输出侧读模组生成的 PNG。

| 用例 | 输入 IHDR | 输入行内可分辨台阶 | 输出 IHDR | 输出行内可分辨台阶 | 信息保留 |
|---|---|---:|---|---:|---:|
| c10（整幅只跨一个 8bit LSB：`0x8000..0x80FF`） | 16bit / colorType 2 | **256**（32768..33023） | 8bit / colorType 6 | **1**（恒为 128） | 1/256 |
| c11（0..65535 完整斜坡） | 16bit / colorType 2 | **1024**（0..65535，行宽只有 1024） | 8bit / colorType 6 | **252**（2..253） | ≈1/4 |
| c2（真实天空图） | 16bit / colorType 2 | **1785**（20151..23424） | 8bit / colorType 6 | 与 c1 **逐字节相同** | 高位深全丢 |

- c10 是最干净的证据：输入在同一个 8bit 台阶内还有 256 级可分辨信息，
  输出把它们**全部塌成 1 个值**——典型 `TYPE_INT_ARGB` 截断，不是量化噪声。
- c11 从 1024 级掉到 252 级，刚好接近 8bit 上限（256），符合「压到 8bit」的预期。
- c1（8bit 源）与 c2（16bit 源，内容相同）生成的纹理哈希**完全相同**，
  证明 16bit 输入没有给最终贴图带来任何额外信息。

---

## 四、实机验证（隐藏窗口，不抢焦点、不抓鼠标）

按你的要求，游戏进程是**后台跑**的：

- 进程一创建就轮询它自己的顶层窗口并 `ShowWindow(SW_HIDE)`，
  持续 `SetWindowPos(SWP_NOACTIVATE|SWP_HIDEWINDOW)` 压制激活；
- 窗口尺寸 320×200，`maxFps=60`，`pauseOnLostFocus=false`；
- 26.2 生产包自带夹具 mixin（`Fixture262WindowMixin` / `Fixture262MouseMixin`）：
  `GLFW_FOCUSED=false`、`GLFW_FOCUS_ON_SHOW=false`、`grabMouse` 直接取消、每帧末 `glfwHideWindow`；
- 只要启动日志里出现纹理处理结论就立即结束进程，不进世界、不加载存档。

### 结果

| 版本 | 日志证据 | 结论 |
|---|---|---|
| 1.21.1 R38 | `[skyboxblock] 图片无法解析（需要 PNG）：03_radiance_hdr_renamed.png`<br>`[skyboxblock] initialized: 9 sky window styles`（定制块未计入）<br>`independent vanilla simulation initialized (iris=false, ip=false, dh=false)` | 与离线矩阵一致：真 HDR 改名后在解码处被拒 |
| 26.2 port.2 | `[skyboxblock] 图片无法解析（需要 PNG）：03_radiance_hdr_renamed.png`<br>`[skyboxblock] initialized: 9 sky window styles` | 同上 |

两版都只初始化了 9 个内置风格，**探针自定义风格 0 个被注册**——和离线结论完全对上。

日志里剩下的 ERROR 都与本次测试无关，已逐条确认：

- 26.2：`[Download-2/ERROR]: Failed to fetch user properties` —— 离线模式拉正版皮肤属性失败，与纹理无关。
- 1.21.1：`java.lang.UnsatisfiedLinkError: Failed to create temporary file ... jnidispatch.dll`
  —— 同一 `%LOCALAPPDATA%\Temp` 不可写问题，JNA 解包失败，只影响 oshi 取硬件信息；
  纹理处理在主线程已成功打出日志。**两版都没有纹理加载/编译/上传相关的 ERROR。**

> 环境注：本机 `%LOCALAPPDATA%\Temp` 不可写，会连带让 ImageIO 的临时缓存和 JNA 解包失败，
> 并使 Fabric Loader 在 `gameDir/.fabric/remappedJars` 重映射失败。
> 测试台的处理是：`-Djava.io.tmpdir` / `-Djna.tmpdir` 钉到用例自己的目录，
> 并把原实例已生成的 `.fabric` 缓存只读复制过来（**没有改原实例**）。
> 换机器复现时若遇到同样的报错，照这两条处理即可。

---

## 五、想做 HDR 需要改什么（供后续决策，本轮不改）

按从便宜到昂贵的顺序：

1. **只扩扩展名闸门**（改 `pictureProblem`）：可以放行 `.hdr/.exr`，
   但 `ImageIO.read()` 仍然读不出来，会落到「图片无法解析」——**没意义**。
2. **加 Radiance/EXR 解码 + 线性管线**：自己解析 `.hdr`（RGBE）或引入 EXR 解码，
   在内存里保留 float，做色调映射或转到 linear U16，再以 16bit PNG 落盘。
   这需要同时改：
   - `SkyConfigParser.pictureProblem()`（放行 `.hdr`/`.exr`）
   - `CustomSkyRegistry.materialise()`（解码分支 + `TYPE_INT_ARGB` → 16bit 写出）
   - 生成纹理的加载路径（Minecraft 只按 8bit 上传，得走自己的 GL 纹理或用
     `GL_RGBA16F` 手写上传，并在三条 shader 里加一条 16F 采样分支）
3. **全线性 HDR 显示**：把整个天空合成改成 HDR 目标（RGBA16F），
   曝光/泛光按真实 HDR 单位运算。这会牵动 Iris 适配层与既有 R28/R33 的测光修复，
   属于大改，不是「支持 HDR 素材」这一个需求。

现实建议：**第 2 档**性价比最高——保持「读文件 → 烘成静态贴图」的现有架构，
只把烘焙目标从 8bit 换到 16bit 线性，运行时仍然按 1 张图采样。
项目其实已经用更省的第 2 档变体干过一次：R31 的两张晴空素材就是
`kloppenheim_06_puresky_1k.hdr` 经 **全局 Reinhard + sRGB** 转成静态 LDR（见
[`docs/evidence/r31-material-assets.json`](evidence/r31-material-assets.json)），
且明确写着 `"static LDR display, no runtime HDR exposure"`。
换句话说：**现在的自定义天空盒只接受「已经烘好的 8bit 显示图」**，
这跟项目自己的既有做法是自洽的，不是缺陷；要不要往 16bit 走需要单独决策。

---

## 六、复现方式

```powershell
# 1) 生成素材
python tmp-hdr-support-probe/make-materials.py
python tmp-hdr-support-probe/make-materials-2.py

# 2) 编译 stub 与探针
#    stubs/common  + stubs/s1211  -> out/stubs1211
#    stubs/common  + stubs/s262   -> out/stubs262
#    src/HdrProbe.java            -> out/probe

# 3) 跑离线矩阵（每个用例一个独立 gameDir）
#    java -Djava.awt.headless=true -Dprobe.jar=<jar> -cp "<stubs>;<jar>;<probe>" HdrProbe <gameDir> <label> <out.json>

# 4) 精度量化（输入侧裸解 PNG、输出侧读生成纹理）
python tmp-hdr-support-probe/check-input-16bit.py
python tmp-hdr-support-probe/measure-precision.py

# 5) 实机（隐藏窗口）
python tmp-hdr-support-probe/live-probe.py
```

产物位置：

- 原始 JSON（两版 × 11 个素材）：[`docs/evidence/hdr-support-20261107/`](evidence/hdr-support-20261107/)
- 测试台脚本与 MC stub 的完整复本：同目录（`HdrProbe.java`、`stubs/`、`make-materials*.py`、
  `measure-precision.py`、`check-input-16bit.py`、`check-texture-upload.py`、`live-probe.py`）
- 实机日志：`live-1211-client.log` / `live-262-client.log`（同目录）
- 小体积素材复本（8bit 基准、16bit 灰度、`10/11` 精度样本）：同目录
- **大体积素材不入库**（16bit 全图 6.1 MB、Radiance HDR 17.4 MB、float32 TIFF 8.4 MB、
  4098 宽 PNG 0.6 MB），用 `make-materials.py` / `make-materials-2.py` 现场重生成；
  原始 HDR 来自 `skyboxblock素材/04-定稿/`
- 生成的真实纹理：`tmp-hdr-support-probe/runs/<case>/config/skyboxblock/generated/assets/skyboxblock/textures/sky/`

---

## 七、本轮边界

- 只测「自定义天空盒（`config/skyboxblock/skyboxes`）能否吃 HDR 素材」。
  内置 9 种风格（含 R31 那两张由 HDR 烘来的）不在此列——它们的输入是已经烘好的 8bit PNG。
- 只测 OpenGL 后端。26.2 的 Vulkan 路径未适配（项目文档已如此声明），本轮不涉及。
- 未接光影做像素级比对；shader 侧结论来自源码与字节码（`Sampler0` 只做 `textureLod`，
  上传格式 8bit），不是从截图推定。
- 未改动任何源码/jar/用户实例；实机用的是 `tmp-hdr-support-probe/live/` 下的隔离 gameDir。
