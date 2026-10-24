# 1.0.3-test.2-r32 全景运动修复交付

日期：2026-10-23（Asia/Shanghai）。修复范围是用户指出的**新增非纯色全景方块**；原版天空没有作为故障路径修改。

## 已修复

- 新增全景在行走／视角摇晃时的错误视差、轻微变形。
- 移动时后排同材质面显出的轮廓与矩形分割。
- 显示名统一为**天空盒方块（晴空·A）**、**天空盒方块（晴空·B）**。内部ID仍为 `kloppenheim_sky_block` / `syferfontein_sky_block`，现有存档方块身份不变；素材生成器同步更新。

旧公式按具体方块面的插值坐标采图，而Minecraft视角摇晃的平移可能放在worldView或projection中；同一屏幕方向碰到不同距离面时，归一化坐标不同，后排回填便泄露为分割。原版按屏幕UV采独立天空画布，纯色没有图案差异，因此不表现为这个故障。

修复只改变 `SkyStyle==3`：保留原始投影与视图矩阵，求当前阶段的逆VP，从同一屏幕像素反投影两个深度点、齐次除法后相减重建无限远方向。这样无论摇晃平移放在projection（原生）还是worldView（Iris），均会被方向差分抵消。早期FSR阶段仍匹配真实jitter，显示阶段用显示投影。没有关闭玩家视角摇晃或FSR来掩盖问题，也没有删除原版折角／玻璃的既有深度规则。

## 本轮同包实机验证

新协议开启原生 `bobView=true`，用真实键盘行走／转头，前墙后有错层同材质面。检查实际相机位移、完整相机变换的非零原点偏移和旋转变化，不仅检查配置开关。独立CPU oracle以双精度在不同于GPU的两个深度点反投影相减，不复用GPU新增矩阵；数学单测另外与已知相机基向量的解析方向对照。

| 环境 | 运动场景 / 检查 | 定向回归场景 / 检查 | ERROR/致命错误 |
|---|---:|---:|---:|
| Fabric无光影 | 82 / 446 | 22 / 212 | 1 / 0 |
| Fabric+DH3.3.3，无光影 | 82 / 446 | 22 / 212 | 0 / 0 |
| Iris/Sodium/DH+ITRP，原生 | 82 / 446 | 22 / 212 | 0 / 0 |
| 同组合FSR2_SCALE=2 | 82 / 446 | 22 / 212 | 0 / 0 |

合计 **416场景、2632/2632检查**。保留1条纯Fabric静态组的Minecraft Yggdrasil公钥获取ERROR：世界载入前HTTPS连接被重置，原始堆栈明确为authlib网络请求，不是渲染故障；未删日志、未把它写成ERROR0。其他ERROR、GL错误、渲染线程错误和致命错误均为0。运动每环境82场景含全部9种方块和移除；定向静态每环境22场景，覆盖9种方块正面／砂岩玻璃持物前景及混材、资源重载、缩放、移除。这22项是明确选择的部分协议，不冒充上版85场景全量或R30动态DH重新验收。

所有实例实际安装的JAR与交付包字节相同。四环境为MC1.21.1/Fabric、DH3.3.3，Iris1.8.1/Sodium0.6.7/ITRP Alpha0.8.31；原生与FSR2分别验证。光影定量隔离回归使用 `MOTION_BLUR=false`，未修改用户光影配置。

- 旧公式负对照：同JAR10场景正常完成，8个运动帧全部按预期检出全景错误，其他检查通过；最差平均绝对RGB误差 **4.705861/255**。
- 修复后全部运动采样的最差全景平均绝对RGB误差 **0.231381/255**。这是各自实机运动帧对独立oracle的统计，不是把两次运行冒称同帧。
- 原版运动对照与原版画布最大平均绝对RGB差 **0.000000/255**。
- 数学回归 **389,385/389,385断言**，其中新增10,000组带平移视图与独立射线对照、原矩阵不变性及旧公式深度分裂负对照，共50,001条新增断言。
- 实际JAR包审计 **261/261**。原版专用类／shader、全部PNG、世界几何顶点shader及方块物品数据与R31逐字节一致；三个颜色shader的原版／纯色分支、深度和HDR主体也逐字节保留。

用户截图、旧公式实机复现、修复实机、各自独立oracle及差异图均随证据保留。对照图上下是两个独立的真实行走采样，非同一帧。

## 交付与复现

版本：**1.0.3-test.2-r32**。主包SHA-256：

```text
d18bf87d77b47322c651a0354547698c6d3b2353a9c07fdb2ca0cc94184e68d6
```

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/build.ps1 -Task build
python tools/verify-r32-package.py
python tools/render-regression.py --profile iris-dh --suite panomotion --jar build/libs/skyboxblock-1.0.3-test.2-r32.jar --label NEW_LABEL --shader "iterationRP Alpha 0.8.31.zip" --shader-settings instance --shader-option FSR2_SCALE=2 --shader-option MOTION_BLUR=false --timeout 1200
python tools/check-r32-motion.py build/render-regression/NEW_LABEL/results --output build/reports/NEW_LABEL-oracle.json --annotate
```

源码与证据归档只新增，包含原版字节核对所需的R31基线JAR、当前源码与工具、全部最终验收捕获JSON／运行身份、精选实机图和用户原始截图。用户实例安装包、存档、光影ZIP及配置均未替换。未创建Git提交或推送。

## 保留边界

仍为有范围测试版，不扩大为任意光影／模组版本兼容。资源／性能长测未启动；旧四项严格恢复红项、门户和其它既有暂缓项保持历史状态。本轮未派子个体，没有新增独立第三方终审。素材来源声明继续适用，原始素材与转换PNG未重新加工。

执行记录：[PANORAMA-MOTION-R32-WORKLOG](<PANORAMA-MOTION-R32-WORKLOG-2026-10-23.md>)。
