# 多格式天空素材导入

日期：2026-11-13（当前工作轮次）。本轮在上一轮 owner 修复源码上增加图片格式支持。

## 交付内容

自定义 `picture` 现在接受：

- `.png`
- `.jpg` / `.jpeg`
- `.hdr`（Radiance RGBE）
- `.exr`（OpenEXR）
- `.tx`（内容为 OpenEXR 的 TX）

图片文件的字节大小限制已移除。像素尺寸仍限制为 4096×2048，避免超出天空纹理和方块渲染的既有资源边界；配置文件自身的 64 KiB 限制保持不变。

1.21.1 主线先加入 `PanoramaImageDecoder` 和 `OpenExr`，再移植同一解码逻辑到 `mc26.2/`。JPG/PNG 使用 JDK ImageIO；HDR 使用既有 Radiance 解码器；EXR/TX 使用纯 Java OpenEXR 读取器。EXR 支持单 part、RGB/RGBA 或 Y、half/float/uint、scanline/tiled、NONE/ZIP/ZIPS。TX 按 OpenEXR 容器识别；非 OpenEXR TX 仍会尝试 JDK ImageIO。

EXR/TX 的线性 half/float/uint 通道在启动阶段使用与 HDR 相同的中位亮度曝光和 Reinhard/sRGB 显示映射。JPG/PNG 保留 JDK 解码后的显示颜色。所有输入最终经过现有羽化、资源包、方块图标生成和天空材质归属流程；运行时没有新增解码或额外渲染 pass。

## 代码位置

- 1.21.1：`src/main/java/dev/yg/skyboxblock/custom/OpenExr.java`、`PanoramaImageDecoder.java`、`CustomSkyRegistry.java`、`SkyConfigParser.java`
- 26.2：`mc26.2/src/main/java/dev/yg/skyboxblock/custom/` 对应文件
- 纯 Java 回归：`src/test/java/dev/yg/skyboxblock/custom/OpenExrRegression.java` 与 `HdrRegistryRegression.java`。

## 最终构建结果

用户提供的 JDK25 路径 `D:\Program Files\Eclipse Adoptium\jdk-25` 已用于 26.2 正式构建；1.21.1 使用 Gradle25 运行时并显式指定工作区 JDK21 toolchain 完成 remap。最终包：

| 目标 | JAR | SHA-256 |
|---|---|---|
| 1.21.1 | `build/libs/skyboxblock-1.0.7-test.3-formats.jar` | `EDA8168634ED157C6D5E8849F30057D261FDC216CB1FEF7A1C1382172C178437` |
| 26.2 | `mc26.2/build/libs/skyboxblock-1.0.7-test.3-formats-port.1-mc26.2.jar` | `E513328C45A66674942E65C2D5DA6A3CBA0BC0AD097884DDE833B2BD235D97D7` |

26.2 `build regAll` 成功；1.21.1 `build remapR30ProbeJar` 成功。两版最终包均包含 `OpenExr.class`、`PanoramaImageDecoder.class`、`RadianceHdr.class` 和上一轮 `SkySkin` owner 修复。

## 已完成验证

1.21.1 主线在当前环境已通过：

- `OpenExrRegression`：16 条 raw/ZIP/HALF/dispatch 断言。
- `HdrRegistryRegression`：30 条真实注册器断言，含 EXR、TX、JPG，以及超过旧 16 MiB 限制的 JPG 文件。
- `SkyConfigRegression`：192 条断言，含 EXR/TX/JPG/JPEG 后缀。
- 主线 javac 全树：66 个源文件通过。

26.2 纯 Java OpenEXR/TX 组件用工作区 JDK21 编译并通过 6 条移植断言；最终 Java25 build/regAll 也已完成。

## 格式边界

OpenEXR PIZ、DWAA/DWAB、multipart/deep 和非 1×1 通道采样会给出明确诊断并跳过当前风格。常规 OpenEXR ZIP/TX 资产、JPG、Radiance HDR 和 PNG 路径均覆盖在回归中。
