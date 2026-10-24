# 天空方块内部云层距离指令

R30新增客户端外观设置。只作用于 `skyboxblock:sky_block` 内部独立生成的云，不改变维度、DH或光影包外部云层；不是服务器全局规则。

## 指令

```mcfunction
/skyboxblock
/skyboxblock clouddistance 768
/skyboxblock cloudfade 256 768
/skyboxblock clouddistance reset
/skyboxblock cloudfade reset
/skyboxblock reload
```

- `clouddistance <距离>`：完整淡出的结束距离，支持 **16–4096格**；开始淡出的位置自动设为结束距离的三分之一。
- `cloudfade <开始> <结束>`：独立指定淡出区间，要求有限数值且 `0 ≤ 开始 < 结束`，结束在16–4096格。
- `reset`：恢复默认 **128格开始、384格结束**。
- 不带参数的 `/skyboxblock`、`clouddistance`、`cloudfade` 均显示当前云高和淡出区间。
- 指令立即生效并保存；重启后仍保留。`reload` 重新读取配置文件。
- 既有 `/skyboxblock cloudheight <高度|reset>` 保留。

距离按相机到云几何的三维距离计算，不只是水平半径。相机与云层的高度差已经大于结束距离时，云完全淡出属于预期结果。

## 配置与优先级

实例的 `config/skyboxblock.properties` 可包含：

```properties
clouds=true
vanillaCloudHeight=192.0
vanillaCloudFadeStart=128.0
vanillaCloudFadeEnd=384.0
```

优先级继续为：默认值 < properties文件 < 对应 `-Dskyboxblock.<key>` JVM参数。存在JVM距离覆盖时，指令仍保存文件，但运行时采用JVM参数，聊天反馈会说明这一点。

## 渲染与成本

不是只把透明度数值调大：超过原有范围时，相应扩展私有云网格，保持云单元尺寸、纹理UV与移动速度；必要时只扩展私有云投影远裁剪，不修改世界相机投影。原版云构建器范围通过仅私有构建期间生效的线程局部作用域调整，普通世界云仍使用原有边界。

较远距离会增加云网格顶点和绘制成本。无需为了看到更多云直接设为最大值，按实际画面选择距离即可。

## 已有实测

候选 `1.0.2-test.9-r30.2` 的原版路径完成8个实机场景，核验 **70/70**：命令结果、活动配置、保存属性和真实GPU FogStart/End一致；包括128、768、1536、自定义64→256、reset、反向区间负例和reload。

- 默认384距离云VBO逻辑存储150528字节；768为611184；1536为2191728；reset恢复150528。
- 实际GPU远裁剪约512.08、783.75、1550.44，均覆盖对应淡出结束距离。
- 反向区间 `cloudfade 512 128` 返回0，原配置与GPU设置保持不变。
- 上述是明确候选与范围的证据，不冒充最终发布包全矩阵或所有配置的性能承诺。

[实测核验](<../build/reports/r30-candidate2-cloud-commands-vanilla-r1-check.json>) · [R30工作记录与当前收口状态](<DH-PERFORMANCE-R30-WORKLOG-2026-10-22.md>)
