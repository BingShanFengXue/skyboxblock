# 原版天空盒方块：独立云层高度

适用：天空盒方块 1.0.2-test.6。这里的“原版”指本模组的 `skyboxblock:sky_block`，不是世界天空。

## 游戏内命令

```text
/skyboxblock cloudheight
/skyboxblock cloudheight 240
/skyboxblock cloudheight 96
/skyboxblock cloudheight reset
/skyboxblock reload
```

- 查询显示当前值；数值命令立即生效并保存到当前游戏实例的配置文件。
- `reset` 恢复默认 Y=192。
- `reload` 重新读取配置文件。
- 支持有限数值 -32768 到 32768，包括小数；异常文件值回落到默认192并记录日志。
- 这是客户端外观设置，单人和多人均可使用；不同玩家可以选择不同高度，无需服务器命令权限。
- 本模组添加的命令均在 `/skyboxblock` 下。

## 配置文件

实例中的 `config/skyboxblock.properties`：

```properties
vanillaCloudHeight=240.0
```

手动编辑后执行 `/skyboxblock reload`，或重启客户端。配置命令保留其余已有属性。

该设置仅由原版天空盒方块的私有云层渲染读取；不修改维度的云高、世界云层、光影配置，也不作为未来其他天空盒类型的通用参数。本模组配置 `clouds=false` 时，调整高度不会强制打开云层；本模组私有云层本来就独立于原版世界和光影的云开关。

## 已执行验证

隔离 Fabric 实机中通过实际客户端命令分派器依次设置96、240，重载保持240，再重置192；每次截图元数据记录真实渲染使用高度。完整证据见[R26交付报告](<VANILLA-SKY-R26-DELIVERY-2026-10-20.md>)。
