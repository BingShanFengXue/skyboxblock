# 26.2 本地化回归（名称为何必须由注册处决定）

> 起因：`skybox-mod-26.2` 的方块/物品在游戏里显示成原始键名或空白，而 `skybox-mod`（1.21.1）
> 一切正常，两者的 `lang/*.json` 内容**逐字节相同**。
> 结论：**不是资源缺失，是 26.2 的注册调用漏了一个属性**。本节记录证据链与修法，避免下次又去补重复键。

---

## 1. 一句话根因

1.21.1 的 `BlockItem` 覆写了 `getDescriptionId()`，让物品去借方块的键；**26.2 / 26.3 把这个覆写删了**。
物品名改由 `Item` 构造器在**注册那一刻**固化成 `DataComponents.ITEM_NAME = Component.translatable(descriptionId)`，
而 `Item.Properties` 的构造器默认把 `descriptionId` 预置成 `ITEM_DESCRIPTION_ID`，即 `item.<ns>.<path>`。

于是：资源里只有 `block.skyboxblock.*` → 物品查 `item.skyboxblock.*` → 查不到 → 显示原始键名。

修法：在 `itemProperties(...)` 里显式 `.useBlockDescriptionPrefix()`，把前缀拨回 `block`。
**不是**去 lang 里补一批 `item.*` 重复键（那样方块名与物品名会变成两份真源，且掩盖注册缺陷）。

---

## 2. 证据链（全部来自本机 jar，`javap` 实测，不是推测）

工具：Mojang `java-runtime-delta` 的 `javap.exe`
jar：`.gradle-home/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar`
（1.21.1 对照用 `skybox-mod/.gradle-home/.../minecraft-merged-1.21.1-...jar`）

### 2.1 `BlockItem`：26.x 不再借方块键

```
# 1.21.1
public class BlockItem extends Item {
  public java.lang.String getDescriptionId();
     0: aload_0
     1: invokevirtual getBlock:()Lnet/minecraft/world/level/block/Block;
     4: invokevirtual Block.getDescriptionId:()Ljava/lang/String;   <-- 借方块的键
     7: areturn
}

# 26.2 / 26.3
public class BlockItem extends Item {
  private final Block block;
  public BlockItem(Block, Item$Properties);
  ...   // 类里已经没有任何 getDescriptionId / getName 覆写
}
```

`javap -p -c ... BlockItem` 在 26.2 上搜 `getDescriptionId` 与 `getName` **零命中**。

### 2.2 `Item.Properties`：描述键前缀的默认值

```
public class Item$Properties {
  private static final DependantName<Item,String> BLOCK_DESCRIPTION_ID;   // -> Util.makeDescriptionId("block", id)
  private static final DependantName<Item,String> ITEM_DESCRIPTION_ID;    // -> Util.makeDescriptionId("item",  id)
  private DependantName<Item,String> descriptionId;

  public Item$Properties();
    20: aload_0
    21: getstatic ITEM_DESCRIPTION_ID
    24: putfield descriptionId;              <-- 默认就是 item.*

  public Item$Properties useBlockDescriptionPrefix();
     1: getstatic BLOCK_DESCRIPTION_ID
     4: putfield descriptionId;              <-- 拨回 block.*

  public Item$Properties useItemDescriptionPrefix();  // 反向
  protected String effectiveDescriptionId() { return descriptionId.get(itemIdOrThrow()); }
}

static {}:
   invokedynamic -> lambda$static$1  //  "block" + Util.makeDescriptionId
   invokedynamic -> lambda$static$0  //  "item"  + Util.makeDescriptionId
```

### 2.3 名字在哪里被固化（关键）

```
public Item(Item$Properties p);
   19: p.effectiveDescriptionId()        -> this.descriptionId
   30: Component.translatable(this.descriptionId)
   37: p.finalizeInitializer(component, p.effectiveModel())
   41: BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.add(p.itemIdOrThrow(), initializer)

finalizeInitializer(Component, Identifier) -> lambda:
   ITEM_NAME  := 那个 translatable
   ITEM_MODEL := 那个 model
```

```
Item.getName(ItemStack stack):
   return stack.getComponents().getOrDefault(ITEM_NAME, EMPTY);
ItemStack.getHoverName() -> (customName ?: getItemName()) -> Item.getName()
```

⇒ 物品名**不经过 `Block`**，完全由注册时 `descriptionId` 决定。

### 2.4 内置方块与自定义方块的初始化顺序（本次额外核实）

```
DataComponentInitializers.add(key, init)      // 纯 List.add，不覆盖
runInitializers(provider):
   map.computeIfAbsent(key, ...)              // 同一 id 拿到同一个 Builder
        .run(builder, provider)               // 就地 set(ITEM_NAME, ...)
```
后 `add()` 的初始化器**先**执行，先 `add()` 的**覆盖**后者的结果。
因此：内置方块（用透明键）必然盖过自定义方块的重写 —— 这解释了为什么自定义天空的自定义名能正常显示，
也说明**如果自定义方块改成返回 `translatable(不存在的键)`，它就会被内置那批的空翻译盖掉**。

---

## 3. 修法

`src/main/java/dev/yg/skyboxblock/SkyboxBlockMod.java`

```java
private static Item.Properties itemProperties(Identifier itemId) {
    return new Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, itemId))
            .useBlockDescriptionPrefix();
}
```

资源侧**一行不改**：`lang/en_us.json` / `lang/zh_cn.json` 与 1.21.1 逐键一致（回归会查）。

`new BlockItem(block, itemProperties(blockId))` 里 `Item` 构造器算出的 `ITEM_NAME`
此时就是 `block.skyboxblock.<path>`，与方块键同源。

---

## 4. 回归

`src/test/java/dev/yg/skyboxblock/LocalizationRegression.java`（纯文本，只需 JDK）
入口脚本：`tools/run-lang-regression.ps1`

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\run-lang-regression.ps1
# [lang-regression] PASS
```

覆盖的断言（每条都用变异体验证过会 FAIL，不是空转）：

| 断言 | 防的是什么 | 变异体验证 |
|---|---|---|
| `itemProperties(...)` 体内含 `useBlockDescriptionPrefix()` | 本次根因复发 | 删掉该行 → FAIL |
| 同上，且不含 `useItemDescriptionPrefix()` | 有人把它改回去 | — |
| 同上，且含 `setId(` | 26.2 构造期 `Item id not set` | — |
| lang 里不得出现 `item.skyboxblock.<同名>` 镜像键 | 用资源掩盖注册缺陷 | 加一条 → FAIL |
| `SkyStyle` 每个枚举项都有 `block.skyboxblock.<id>` | 新增样式忘记补 lang | 加 `NEBULA(...)` → FAIL |
| 26.2 与 1.21.1 的 lang 逐键同名同值 | 名称漂移 | 改「葱郁」→「郁郁」 → FAIL |
| `en_us` 与 `zh_cn` 键集一致 | 回退时漏字 | — |
| `MOD_ID` 常量与源码一致 | 回归里写死的 id 与实现漂移 | — |

---

## 5. 备注：`verify-javac.ps1` 不适用于本工程

`tools/verify-javac.ps1` 把项目根硬编码为 `$base/ohwhale-mod`，并要求
`ohwhale-mod/build/compile-classpath.txt`；`skybox-mod-26.2` 既不是那个目录，也没有该文件。
所以本工程的**编译**验证仍要交主线（Gradle / 有完整 classpath 的一方）。
本回归刻意设计成**零依赖**（不 import MC、不用 Gson、不用 JUnit），因此能独立跑。
