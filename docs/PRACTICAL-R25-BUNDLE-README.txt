Sky Box Block 天空盒方块 — 1.0.2-test.5 实用测试候选 / R25配置批次
Minecraft 1.21.1 / Fabric
项目作者：Bing6512

【先看结论】
本包采用冻结R23主模组原字节，并非新编译的渲染核心。
这次解决的是一个已观察到的组合问题：ITRP与Immersive Portals一起使用时，关闭光影的Motion Blur（运动模糊）。
保持光影开启，FSR倍率保持自己的选择。用户实例和原配置未被自动修改。

【安装】
1. 先备份自己的实例与存档。
2. 只把mods目录中的skyboxblock-1.0.2-test.5.jar作为模组安装，客户端与服务端注册方块均需Fabric及Fabric API。
3. 同ID只留一个Sky Box Block版本。source中的sources.jar是源码，别装进mods。
4. Iris、Sodium、Immersive Portals、ITRP等第三方内容不在本包中；如需这些功能，沿用自己的对应安装。
5. ITRP Alpha 0.8.31 + Iris1.8.1 + Sodium0.6.7 + 本次IP嵌入版本6.0.6的实测兼容选项：

MOTION_BLUR=false

通过光影设置菜单关闭Motion Blur，或把compatibility目录选项片段中的这一项合并到自己的光影选项。
该片段不是完整配置；别用它覆盖其它选项。保持原FSR设置，不改或重新打包shader ZIP。
本次试验使用IP正常渲染模式，未关闭光影或将IP换成Dummy renderer。

【本轮实际做了什么】
- 三环境各28场主视角、手持、副手/双持、柱体遮挡、缩放、重载、掉落物与选择框复核：84场，世界831/831、位置342/342通过。
- ITRP原生与FSR2=2各baseline/empty/sky三变体：54次capture，真实步行穿越和返回均完成。
- 主线亲看两环境普通墙、天空方块、前景柱及穿越前后原图；运动模糊关后，旧墙面混成天空的严重异常明显改善。
- 主包和测试harness仍是既有冻结字节，未为凑测试重写生产渲染。

【测试与支持限度】
这是供实际使用反馈的实用候选，不是完整release验收通过。
门户严格白色/柱体材质色度与高频、未同步clock/history的跨run整帧RGB仍有红项；新颜色布局诊断并未证明完整白材质几何，未签自动PASS。
两环境最终诊断646/662、原strict588/605均保留，practicalPassed=null、automaticAcceptanceReady=false、releaseAccepted=false。
基础范围：同主世界、纯平移、非嵌套门户；旋转/缩放/镜像/嵌套/跨维度、任意shader版本及完整动态DH暂未验证。
高墙偶发缺绘在R24前序回放未复现，按用户要求暂缓，不称已修。
旧full三项移除恢复严格差异与真实水负例灵敏度缺口保留；不为疑难测试无限延长交付。
完整模组组主视角回归仍有59条资源/配方/数据fixer/IP启动ERROR；门户每组有4条startup chunk ERROR，fatal/render-thread均0。日志不被抹除，运行结束不等于clean run。

【包内内容】
mods/：实际主模组。
source/：与实际主包共同冻结的sources.jar（remapped源码）。
compatibility/：仅一个Motion Blur选项的合并片段。
reports/：本轮实际身份统计、完整原严格诊断与窄差异复核。
evidence/：人工已查看的原图对照与位置原图。
manifest.json：每个文件的实际SHA256、大小、候选身份及限制。
LICENSE、LICENSES、AUTHORS、THIRD_PARTY_NOTICES：保留原作者/第三方/AI贡献归属。
reports中若出现build/、tools/路径，指制作工作区的证据路径，不代表完整游戏实例被打入本包。选定展示图已单独放入evidence。

【产物身份】
main SHA256: 9f1903e75e3b66cd4cad147a11c9d74cddd9a2d249eaf6ada103b6419ad64129
sources SHA256: 5cbf48ed5f910e00f54acdb0034524c41a09a849a42461595d5b9d6d186fe2ab
主包内版本仍为1.0.2-test.5。R25只标识本次实用配置/复核与交付批次。

test.4已验收基线、原始红报告与旧独审仍保留。保活和关机继续取消。
