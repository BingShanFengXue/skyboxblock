# 自定义天空：OpenEXR PIZ 导入修复（2026-11-22）

## 定位

用户提供的 `test.conf` 已启用，两个块的定义、图片相对路径和尺寸均有效。旧解码器接受 NONE、ZIPS、ZIP，但在 OpenEXR 文件头遇到压缩编号 **4（PIZ）** 时退出；注册器因此只跳过 `skyboxblock:test2`，同文件的 HDR 定义不受影响。官方 OpenEXR 3.5.4 确认该 EXR 是 3072×1536、单部件扫描线、RGBA HALF、全分辨率通道采样的 PIZ 图像。旧版已能导入 4000×2000 的 Radiance HDR，曝光与亮度中值在修复前后一致。

## 修复范围

- 以 1.21.1 为基准，将同一份纯 Java 解码器同步到 26.2。在扫描线（32 行/块）和 tiled 路径复用 PIZ 反变换，处理 Huffman、14/16 位小波、范围表和未获得压缩收益、按原样存储的块。OpenEXR 容器的 `.tx` 同样适用。
- 校正既有 ZIP/ZIPS 的逆预测和字节重排次序：之前合成测试的编码器与解码器都采用了反向顺序，能彼此通过，却与标准写入器不符。新回归使用官方编码器生成样本，与 NONE 逐像素对照。
- 原 HDR、配置格式、素材尺寸上限、色调映射、渲染路径不变。仍要求单部件、非 deep、通道采样率 1×1；DWAA/DWAB 等其他压缩未新增支持。发布包内附 OpenEXR BSD-3-Clause 许可，不引入运行时图像库。用户素材仅在隔离测试目录使用，不随包分发。

## 验证

| 样式 | 未修改的用户素材 | 两版生成结果 |
|---|---|---|
| `skyboxblock:test1` | `Openfootage-Autumn_Forrest_4k.hdr` | 4000×2000 全景，32×32 方块图标 |
| `skyboxblock:test2` | `Cloudy_09_3K.exr` | 3072×1536 全景，32×32 方块图标 |

- 两版用原始配置同时注册两个样式：导入警告 **0**，两个样式的中英文名称及创造栏标志均通过核验，生成的全景 PNG 在两版间 SHA-256 一致。
- `openExrTest` 两版各通过 **44 项断言**。包括官方编码的 PIZ/ZIP/ZIPS 与 NONE 对照，PIZ 边缘砖块、数据窗口偏移、原样存储回退、16 位小波、损坏数据诊断及 TX 分派。
- 真实 EXR 在接缝处理之前与 OpenEXR 3.5.4 解码并应用同一色调映射后的输出逐像素对比：**0 / 4,718,592** 像素有差异。
- 1.21.1：JDK 21 `build` 成功，Loom `remapJar`，入口 29 个 intermediary 引用 / 0 个 Mojang 命名引用，refmap 9 个 mixin 类，映射校验通过。26.2：JDK 25 `build regAll` 成功，官方命名按预期保留。
- 验证覆盖真实启动注册链、生成资源、发布包解码和包内映射；未进游戏做方块摆放或渲染验证。1.21.1 发布包基于 `b96e9ab` 加本轮修复构建，不包含工作树已有的未提交天穹巨构改动；这些改动原地保留。现有 Minecraft 实例未替换。

日志：[1.21.1](<../build/exr-reference/final-1211.log>) · [26.2](<../build/exr-reference/final-262.log>) · [生成的全景预览](<../build/exr-reference/registry-preview.png>)。

## 使用和复跑

选对应 Minecraft 版本的 JAR 替换旧版模组；把原始 `test.conf` 和两张原图一起放入实例的 `config/skyboxblock/skyboxes/`，保持文件名和配置值不变，再重启游戏。

各自进入工程目录并使用对应 JDK，合成样本已在 `src/test/resources/exr/`，无需联网或 Python 依赖即可运行测试。复跑原始三文件时，把路径指向包含这三件文件的目录：

```powershell
.\gradlew.bat openExrTest mixedPanoramaRegistryTest
.\gradlew.bat openExrTest mixedPanoramaRegistryTest "-PpanoramaSampleDir=$env:USERPROFILE\Downloads"
```

| 发布构建 | SHA-256 |
|---|---|
| [1.21.1 Fabric JAR](<../build/libs/skyboxblock-1.0.8-test.3-exr-piz-1.21.1.jar>) | `100929428861173442E5F0EE6A5E1CB7F70D25F8C3CA37976B3944EA7630D7D5` |
| [26.2 Fabric JAR](<../mc26.2/build/libs/skyboxblock-1.0.8-test.3-exr-piz-mc26.2.jar>) | `7D53DA249D75E702C01A2961DCFAFE2D154CB45AD56D153DEEECC054706B9071` |
