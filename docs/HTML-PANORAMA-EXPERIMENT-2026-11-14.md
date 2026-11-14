# HTML 全景导入实验（2026-11-14）

## 行为范围

自定义天空配置的 `picture` 接受 `.html` / `.htm`。客户端可用本机 Chromium、Microsoft Edge 或 Google Chrome 的 headless 截图器，把网页在启动期绘制为 2:1 PNG，再交给既有羽化、资源包、图标和天空材质流程。天空运行时仍采样普通静态纹理，不增加每帧浏览器、CPU 解码或额外 GPU pass。

这是“网页生成静态全景纹理”，不是在游戏内嵌入活动网页。HTML/CSS/Canvas/JavaScript 在捕获时执行一次，默认虚拟等待 1000 ms；动画之后不会持续更新。页面自身须绘出完整等距柱状全景：横向为 360° 经度、纵向为南北纬度，左右边界连续，地平线位于画布中线。现有 8 像素边缘羽化仍会应用于网页截图。

默认关闭网页截图器。关闭开关、缺少源文件、浏览器启动失败或额度耗尽时，该样式 ID 仍注册，材质使用醒目的占位纹理并记录原因。专用服务器从不启动浏览器；它以相同配置注册同一 ID，并使用占位纹理。客户端和服务器应使用相同模组版本与包含该样式 ID 的 `.conf`；网页文件只需放客户端。

## 使用

在 `config/skyboxblock/skyboxes/` 放入页面和自定义配置：

```text
enable = true

web_sky = {
    id = "skyboxblock:web_sky"
    picture = "panorama.html"
    creative_inventory = true
    lang_zh_cn = { name = "网页全景" }
    lang_en_us = { name = "HTML Panorama" }
}
```

启动客户端时增加 JVM 参数：

```text
-Dskyboxblock.html.enabled=true
```

Edge/Chrome 会按 Windows 常见安装路径、macOS 应用路径或 Linux `PATH` 自动探测。也可指定可执行文件：

```text
-Dskyboxblock.html.browser="C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
```

页面可通过相对 URL 引用同目录资源（例如 `assets/sky.css`、图片和字体）；它们的文件名、长度和修改时间参与缓存校验。配置 `picture` 本身仍是文件名单段，不要写目录分隔符。页面按本地 `file:` URL 加载，浏览器可从同目录读取相对资源。

## 参数与限制

| JVM 参数 | 默认值 | 说明 |
|---|---:|---|
| `skyboxblock.html.enabled` | `false` | 客户端启用网页截图；服务器忽略，不启动浏览器 |
| `skyboxblock.html.browser` | 自动探测 | Chromium/Edge/Chrome 可执行文件路径 |
| `skyboxblock.html.width` | `4096` | 2:1 视口宽度，范围 512–4096，高度自动取一半 |
| `skyboxblock.html.waitMs` | `1000` | 截图前 Chromium 虚拟时间，范围 0–10000 ms |
| `skyboxblock.html.timeoutMs` | `45000` | 单个缓存未命中页的进程超时，上限 120000 ms |

HTML 源文件最大 16 MiB；缓存指纹扫描页面目录树最多 4096 个文件。每次注册最多启动 8 次未命中截图进程，总渲染时间预算 90 秒。超限页面保留注册并使用占位纹理。缓存位于 `config/skyboxblock/generated/html-cache/`，在资源包 `assets/` 之外；写入使用临时目录和原子替换。

缓存指纹包含 HTML 内容、页面目录树文件名/长度/修改时间、浏览器路径及可执行文件元数据、视口和虚拟等待时间。页面若引用网络数据，网络响应不参与指纹；依赖文件刻意保留原长度和时间戳时，手动删除该实例的 `generated/html-cache/` 可强制重新截图。Canvas/WebGL/WebGPU 输出可能受 Chromium 版本、显卡和驱动影响；WebGL/WebGPU 不属于跨设备一致性保证。

## 验证

在 `skybox-mod/` 执行默认纯逻辑回归（不启动浏览器）：

```powershell
.\gradlew.bat htmlPanoramaTest --no-daemon --console=plain
```

有浏览器的开发机再执行实际截图、相对 CSS、4K 像素、缓存命中与变更重截图：

```powershell
.\gradlew.bat htmlPanoramaTest -PhtmlBrowserTest=true `
  -PhtmlBrowser="C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" `
  --no-daemon --console=plain
```

`mc26.2/` 下也提供同名测试任务，使用该移植线需要的 Java 25。主线 `check` 已包含不启动浏览器的 HTML 回归；真实浏览器用例必须显式设置 `-PhtmlBrowserTest=true`。

已验 Edge 页面截图（4096×2048）：[html-snapshot-4096x2048.png](<evidence/html-experiment-20261114/html-snapshot-4096x2048.png>)，SHA-256 `30F90CB3AB212B081361C613F2AC8879E35A35DFDB27BDC83B61A8F059797B60`。截图两侧色块由 HTML Canvas 经同目录 CSS 绘制，最终 PNG 已进入现有纹理羽化和注册器流程。

### 本轮结果

- Minecraft 26.2：JDK 25 / Gradle 9.8.2 clean `build` 通过，另 `regAll` 通过；包括 194 项配置、3,734 项 HDR、545 项材质身份、199,046 项天空场景语义与 68,012 项天空时间检查。真实 Edge 静态集成 22 项通过；动态 Canvas 集成 8 项通过，持续会话收到 3 个不同颜色帧，测试输出 `lastEvent=Runtime.evaluate`。动态回归结束后 clean 可正常删除浏览器 profile；默认动态目标 1024×512、10 FPS、最多 4 页面。
- 26.2 动态实验 JAR：[skyboxblock-1.0.8-test.2-html-dynamic-mc26.2.jar](<../mc26.2/build/libs/skyboxblock-1.0.8-test.2-html-dynamic-mc26.2.jar>)，SHA-256 `566D4ABFE7222FE45E620C2C5D7A3BBE588B6F4FC73F15CB7DBA47171F88A84D`。旧 `1.0.7-test.3-formats-port.1-mc26.2` 基线包未覆盖。
- 1.21.1 主线：最终 69 个 main 源以官方 Java 21.0.3 `javac --release 21` 编译通过；静态 `SkyConfigRegression` 194 项、HTML/Edge 集成 22 项通过。由于本机 Java ZipFS 对所有输出路径均回报只读，Loom 合并阶段持续抛出 `ReadOnlyFileSystemException`；本轮使用已有 1.21.1 基线 JAR、官方→intermediary TinyRemapper 和普通 ZIP 组装生成独立动态包：[skyboxblock-1.0.8-test.2-html-1.21.1.jar](<../build/libs/skyboxblock-1.0.8-test.2-html-1.21.1.jar>)，SHA-256 `D51559F6A2B5557D2FC188FE8394F91AF4E559651B4E7FB0ACECFBDB6278A6C1`。该包在 Java 25 + Edge 下动态回归 8 项通过，Java 21 headless Edge 当前仍未稳定返回 CDP 动态帧；动态支持实测矩阵以 Java 25 客户端为准。历史 `1.0.7-test.3-formats.jar` 未覆盖。

### 动态模式（实验）

动态模式默认关闭。客户端启动时同时设置：

```text
-Dskyboxblock.html.enabled=true
-Dskyboxblock.html.dynamic.enabled=true
```

动态捕获默认使用 1024×512、10 FPS，最多同时运行 4 个 HTML 页面；可调整：

```text
-Dskyboxblock.html.dynamic.width=1024
-Dskyboxblock.html.dynamic.fps=10
```

动态模式保留一个 headless Chromium 页面会话，通过 DevTools 定时读取最新 Canvas 帧；游戏线程只消费最新帧，旧帧直接丢弃。每帧上传前仍执行左右边缘羽化，失败后恢复生成目录中的静态 PNG。每个动态页面独立 profile，退出客户端时回收浏览器进程树。

为获得跨浏览器/JDK 一致结果，动态页面应提供 Canvas，并可选定义 `window.skyboxblockCapture()` 返回 PNG data URL；例如：

```js
window.skyboxblockCapture = () => document.querySelector('canvas').toDataURL('image/png');
```

动态模式适合 CSS/Canvas/JavaScript 动画；页面的 WebGL 输出依赖浏览器软件渲染与显卡环境。实测支持矩阵为 Windows Edge + Java 25 + Minecraft 26.2；1.21.1 的静态 HTML 导入已通过 Java 21 字节码回归，动态 Java 21 headless Edge 在当前机器上未稳定返回 CDP 帧，因此主线动态模式继续保持默认关闭。

## 回退

- 先从客户端启动参数移除 `-Dskyboxblock.html.enabled=true`；网页样式继续注册，但显示占位纹理，其他图片格式与渲染路径保持不变。
- 若需完整回到导入前的构建，恢复 1.21.1 基线 JAR `build/libs/skyboxblock-1.0.7-test.3-formats.jar`，或 26.2 基线 JAR `mc26.2/build/libs/skyboxblock-1.0.7-test.3-formats-port.1-mc26.2.jar`，客户端和服务器使用同一版本。上述基线包由本实验保留，实验产物使用独立版本号，不覆盖它们。
- HTML 原文件只读；关闭开关后生成缓存仍留在 `generated/html-cache/`，不参与打包资源，也不影响 PNG/JPG/HDR/EXR/TX 路径。
