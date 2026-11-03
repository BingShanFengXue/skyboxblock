# R4 / R4b / R5 / R5b / R10 实测报告：资源包注入 + 方块/物品注册（Minecraft 26.2）

- 生成时间：2026-11-01
- 被测 jar：`skybox-mod-26.2/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-a8e9c7b123/26.2/minecraft-merged-a8e9c7b123-26.2.jar`（37,396,282 字节）
- 类表：`skybox-mod-26.2/build/mc262-classes.txt`（31,707 行 jar 条目，**注意它是 jar 全量条目表，不是纯 class 表**，查类要用 `net/minecraft/...class` 前缀）
- 反编译签名工具：`C:\Program Files\Java\jdk-25.0.4.1\bin\javap.exe`
- Fabric API 0.161.0+26.2 各模块 jar：`.gradle-home/caches/modules-2/files-2.1/net.fabricmc.fabric-api/`

**标记约定**

- 🟢 **实测**：本文档中贴出的签名/字节码直接来自上面这些 jar 的 `javap` 输出，可复现。
- 🟡 **推断**：由实测签名组合出的写法，未编译验证（本轮禁止跑 gradle）。
- ❌ **查不到**：明确标注。

**结论速览**

| 项 | 26.2 现状 | 对移植的影响 |
|---|---|---|
| `PackResources` 接口 | 5 个抽象方法 + `location()`，`getMetadataSection` 换成 `MetadataSectionType<T>` | `CustomSkyPack` 必改 3 处 |
| `MetadataSectionSerializer` | **整个类不存在**（类表 0 命中） | 旧 `getMetadataSection(MetadataSectionSerializer)` 写法直接编译失败 |
| `MultiPackResourceManager(PackType, List<PackResources>)` | 一字未变（🟢 见 §R4.6） | mixin 注入方案原样可用 |
| `ResourceLocation` | **已改名 `net.minecraft.resources.Identifier`**（`ResourceLocation` 0 命中） | 机械改名 |
| `BlockIds` / `BlockItemIds` / `ItemIds` | 只是 `ResourceKey` 常量表，**不是注册入口** | 注册仍走 `Registry.register` |
| `BlockEntityType.Builder` | **已删除**，只剩 `BlockEntityType$BlockEntitySupplier` | `SkyboxBlockMod:72` 必须重写 |
| `Item.Properties` / `BlockBehaviour.Properties` | 新增 `setId(ResourceKey)`，且 `Item` 构造器内部调用 `itemIdOrThrow()` | 不调 `setId` 会在构造时抛异常 |
| `BlockStateModelGenerator` | **不存在**，`BlockModelGenerators` 搬到 `net.minecraft.client.data.models` | 不需要数据生成，运行时仍读 JSON |
| `items/<name>.json` | 1.21.4+ 的新物品模型入口，26.2 仍在用（1,538 个文件） | 内存 pack 的 item 模型要写到 `assets/<ns>/items/` |
| Fabric `ResourceManagerHelper.registerBuiltinResourcePack` | 只接受 `ModContainer`（jar 内目录） | **没有**运行时生成 pack 的公开入口 |
| Fabric `ModResourcePack` (v0) | 已标 `deprecated`，且依赖 v1 | 要用 `ModPackResources`（v1） |

---

## R4. `PackResources` 家族与"内存 pack"最小实现

### R4.1 `PackResources` 完整签名 🟢

```
$ javap -p -classpath minecraft-...-26.2.jar net.minecraft.server.packs.PackResources

public interface net.minecraft.server.packs.PackResources extends java.lang.AutoCloseable {
  public static final java.lang.String METADATA_EXTENSION;
  public static final java.lang.String PACK_META;
  public abstract net.minecraft.server.packs.resources.IoSupplier<java.io.InputStream> getRootResource(java.lang.String...);
  public abstract net.minecraft.server.packs.resources.IoSupplier<java.io.InputStream> getResource(net.minecraft.server.packs.PackType, net.minecraft.resources.Identifier);
  public abstract void listResources(net.minecraft.server.packs.PackType, java.lang.String, java.lang.String, net.minecraft.server.packs.PackResources$ResourceOutput);
  public abstract java.util.Set<java.lang.String> getNamespaces(net.minecraft.server.packs.PackType);
  public abstract <T> T getMetadataSection(net.minecraft.server.packs.metadata.MetadataSectionType<T>) throws java.io.IOException;
  public abstract net.minecraft.server.packs.PackLocationInfo location();
  public default java.lang.String packId();
  public default java.util.Optional<net.minecraft.server.packs.repository.KnownPack> knownPackInfo();
  public abstract void close();
}
```

`ResourceOutput` 就是普通 `BiConsumer` 别名 🟢：

```
public interface net.minecraft.server.packs.PackResources$ResourceOutput
    extends java.util.function.BiConsumer<net.minecraft.resources.Identifier,
            net.minecraft.server.packs.resources.IoSupplier<java.io.InputStream>> { }
```

**与 1.21.1 的差异**：方法集合（getRootResource / getResource / listResources / getNamespaces / close / location / packId）没变；
唯一实质变化是 `getMetadataSection` 的参数类型：`MetadataSectionSerializer<T>` → `MetadataSectionType<T>`，
而 `net.minecraft.server.packs.metadata.MetadataSectionSerializer` **在新 jar 里完全不存在**（`mc262-classes.txt` 中 `*MetadataSectionSerializer*` 命中数 = 0，🟢）。

### R4.2 `AbstractPackResources` 提供什么 🟢

```
public abstract class net.minecraft.server.packs.AbstractPackResources implements net.minecraft.server.packs.PackResources {
  private final net.minecraft.server.packs.PackLocationInfo location;
  private net.minecraft.server.packs.resources.ResourceMetadata metadata;
  protected net.minecraft.server.packs.AbstractPackResources(net.minecraft.server.packs.PackLocationInfo);
  public <T> T getMetadataSection(net.minecraft.server.packs.metadata.MetadataSectionType<T>) throws java.io.IOException;
  public static net.minecraft.server.packs.resources.ResourceMetadata loadMetadata(net.minecraft.server.packs.PackResources) throws java.io.IOException;
  public net.minecraft.server.packs.PackLocationInfo location();
}
```

它只替你做了两件事：**存 `location`** 和**惰性解析 metadata**。字节码证据 🟢：

```
public <T> T getMetadataSection(MetadataSectionType<T>):
  0: aload_0
  1: getfield  metadata:ResourceMetadata
  4: ifnonnull 15
  7: aload_0 ; 8: aload_0 ; 9: invokestatic loadMetadata:(Lnet/minecraft/server/packs/PackResources;)Lnet/minecraft/server/packs/resources/ResourceMetadata;
 12: putfield  metadata
 15: ... invokeinterface ResourceMetadata.getSection:(MetadataSectionType)Optional
 25: aconst_null ; 26: Optional.orElse  -> 返回 null 而不是抛
```

```
public static ResourceMetadata loadMetadata(PackResources):
  7: ldc "pack.mcmeta"
 10: invokeinterface PackResources.getRootResource([Ljava/lang/String;)IoSupplier
 16: ifnonnull 24
 20: getstatic ResourceMetadata.EMPTY     <-- 没有 pack.mcmeta 就退化成 EMPTY
 24: ... invokeinterface IoSupplier.get ; 35: invokestatic ResourceMetadata.fromJsonStream
```

所以继承 `AbstractPackResources` 后，**metadata 完全由 `getRootResource("pack.mcmeta")` 决定**；也可以在子类里直接覆写
`getMetadataSection` 用内存里的 `ResourceMetadata` 顶掉（见 §最小可行写法 A 路线）。

### R4.3 `PathPackResources` / `FilePackResources` 提供什么 🟢

```
public class net.minecraft.server.packs.PathPackResources extends net.minecraft.server.packs.AbstractPackResources {
  public net.minecraft.server.packs.PathPackResources(net.minecraft.server.packs.PackLocationInfo, java.nio.file.Path);
  public IoSupplier<InputStream> getRootResource(String...);
  public static boolean validatePath(java.nio.file.Path);
  public IoSupplier<InputStream> getResource(PackType, net.minecraft.resources.Identifier);
  public static IoSupplier<InputStream> getResource(java.nio.file.Path, net.minecraft.resources.Identifier);
  public void listResources(PackType, String, String, PackResources$ResourceOutput);
  public static void listResources(java.nio.file.Path, String, String, PackResources$ResourceOutput);
  public static void listPath(String, java.nio.file.Path, java.util.List<String>, PackResources$ResourceOutput);
  public Set<String> getNamespaces(PackType);
  public static Set<String> getNamespaces(java.nio.file.Path);
  public void close();
}
```

```
public class net.minecraft.server.packs.FilePackResources extends net.minecraft.server.packs.AbstractPackResources {
  private net.minecraft.server.packs.FilePackResources(PackLocationInfo, FilePackResources$SharedZipFileAccess, String);  // 私有
  public IoSupplier<InputStream> getRootResource(String...);
  public IoSupplier<InputStream> getResource(PackType, Identifier);
  public Set<String> getNamespaces(PackType);
  public static String extractNamespace(String, String);
  public void close();
  public void listResources(PackType, String, String, ResourceOutput);
}
```

两个 `ResourcesSupplier`（`Pack$ResourcesSupplier` 的实现）把"路径/zip → PackResources"包了一层 🟢：

```
public class PathPackResources$PathResourcesSupplier implements Pack$ResourcesSupplier {
  public PathPackResources$PathResourcesSupplier(java.nio.file.Path);
  public PackResources openPrimary(PackLocationInfo);
  public PackResources openFull(PackLocationInfo, Pack$Metadata);
}
public class FilePackResources$FileResourcesSupplier implements Pack$ResourcesSupplier {
  public FilePackResources$FileResourcesSupplier(java.nio.file.Path);
  public FilePackResources$FileResourcesSupplier(java.io.File);
  public PackResources openPrimary(PackLocationInfo);
  public PackResources openFull(PackLocationInfo, Pack$Metadata);
}
```

**判定**：`PathPackResources` 可以吃一个磁盘目录（`new PathPackResources(info, root)`），
**但"内存/运行时生成"这个需求它解决不了**——它读的是 `root/assets/...`、`root/data/...` 真实文件。
`FilePackResources` 构造器私有，只能经 `FileResourcesSupplier` 走 zip。

### R4.4 `PackType` / `PackLocationInfo` / `Pack.Metadata` / 相关 record 构造方式 🟢

```
public final class net.minecraft.server.packs.PackType extends java.lang.Enum<PackType> {
  public static final PackType CLIENT_RESOURCES;
  public static final PackType SERVER_DATA;
  public java.lang.String getDirectory();          // "assets" / "data"
}
```

```
public final class net.minecraft.server.packs.PackLocationInfo extends java.lang.Record {
  public PackLocationInfo(java.lang.String id, net.minecraft.network.chat.Component title,
                          net.minecraft.server.packs.repository.PackSource source,
                          java.util.Optional<net.minecraft.server.packs.repository.KnownPack> knownPackInfo);
  public java.lang.String id(); public Component title(); public PackSource source();
  public java.util.Optional<KnownPack> knownPackInfo();
  public Component createChatLink(boolean, Component);
}
```

`Pack.Metadata`（仓库层"这个包是什么"，不是 pack.mcmeta）🟢：

```
public final class net.minecraft.server.packs.repository.Pack$Metadata extends java.lang.Record {
  public Pack$Metadata(net.minecraft.network.chat.Component description,
                       net.minecraft.server.packs.repository.PackCompatibility compatibility,
                       net.minecraft.world.flag.FeatureFlagSet requestedFeatures,
                       java.util.List<java.lang.String> overlays);
}
```

配套（全部实测）🟢：

```
public interface net.minecraft.server.packs.repository.Pack$ResourcesSupplier {
  PackResources openPrimary(PackLocationInfo);
  PackResources openFull(PackLocationInfo, Pack$Metadata);
}

public final class net.minecraft.server.packs.PackSelectionConfig extends java.lang.Record {
  public PackSelectionConfig(boolean required, net.minecraft.server.packs.repository.Pack$Position defaultPosition, boolean fixedPosition);
}
// Pack$Position: TOP, BOTTOM（枚举常量实测）

public final class net.minecraft.server.packs.metadata.pack.PackFormat extends java.lang.Record {
  public PackFormat(int major, int minor);
  public static PackFormat of(int);  public static PackFormat of(int, int);
  public int major(); public int minor();
  public net.minecraft.util.InclusiveRange<PackFormat> minorRange();
}

public final class net.minecraft.server.packs.metadata.pack.PackMetadataSection extends java.lang.Record {
  public PackMetadataSection(net.minecraft.network.chat.Component description,
                             net.minecraft.util.InclusiveRange<net.minecraft.server.packs.metadata.pack.PackFormat> supportedFormats);
  public static final MetadataSectionType<PackMetadataSection> CLIENT_TYPE;
  public static final MetadataSectionType<PackMetadataSection> SERVER_TYPE;
  public static final MetadataSectionType<PackMetadataSection> FALLBACK_TYPE;
  public static MetadataSectionType<PackMetadataSection> forPackType(PackType);
}

public final class net.minecraft.server.packs.metadata.MetadataSectionType<T> extends java.lang.Record {
  public MetadataSectionType(java.lang.String name, com.mojang.serialization.Codec<T> codec);
  public MetadataSectionType$WithValue<T> withValue(T);
  public java.lang.String name();  public com.mojang.serialization.Codec<T> codec();
}

public interface net.minecraft.server.packs.resources.ResourceMetadata {
  public static final ResourceMetadata EMPTY;
  public static final IoSupplier<ResourceMetadata> EMPTY_SUPPLIER;
  public static ResourceMetadata fromJsonStream(java.io.InputStream) throws java.io.IOException;
  public abstract <T> java.util.Optional<T> getSection(MetadataSectionType<T>);
  public static <T> ResourceMetadata of(MetadataSectionType<T>, T);
  public static <T1,T2> ResourceMetadata of(MetadataSectionType<T1>, T1, MetadataSectionType<T2>, T2);
  public default java.util.List<MetadataSectionType$WithValue<?>> getTypedSections(java.util.Collection<MetadataSectionType<?>>);
}

public interface net.minecraft.server.packs.repository.PackSource {
  PackSource DEFAULT; PackSource BUILT_IN; PackSource FEATURE; PackSource WORLD; PackSource SERVER;
  Component decorate(Component);
  boolean shouldAddAutomatically();
  public static PackSource create(java.util.function.UnaryOperator<Component>, boolean);
}

public interface net.minecraft.server.packs.resources.IoSupplier<T> {
  public abstract T get() throws java.io.IOException;
  public static IoSupplier<java.io.InputStream> create(java.nio.file.Path);
  public static IoSupplier<java.io.InputStream> create(java.util.zip.ZipFile, java.util.zip.ZipEntry);
}
```

`PackCompatibility` 枚举：`TOO_OLD / TOO_NEW / UNKNOWN / COMPATIBLE`，带
`forVersion(InclusiveRange<PackFormat>, PackFormat)` 🟢。

### R4.5 自定义"内存 pack"最少要实现哪几个方法 —— 结论

继承 `AbstractPackResources`（省掉 `location()`）时，**必须实现 5 个**：

1. `getRootResource(String... segments)` —— 至少要能吃 `("pack.mcmeta")`（否则 metadata = `ResourceMetadata.EMPTY`，🟢 字节码见 §R4.2）
2. `getResource(PackType, Identifier)`
3. `listResources(PackType, String namespace, String String prefix, ResourceOutput)`
4. `getNamespaces(PackType)` —— 返回 `Set<String>`（不是 `Collection`）
5. `close()` —— 空实现即可

`getMetadataSection` 在 `AbstractPackResources` 里已实现（可覆写，覆写后就不必给 `pack.mcmeta`）。
`packId()`、`knownPackInfo()` 是 default 方法，不必实现。

如果直接 `implements PackResources`（不继承），则要额外补 `location()`，共 **6 个**。

> 实测补充：`MultiPackResourceManager` 构造时对每个 pack 只问 `ResourceFilterSection.TYPE` 一个 section，
> 并且**捕获异常后按"没有 filter"处理**（🟢 见 §R4.6），所以注入到 manager 这一层的 pack 即使完全不给 pack.mcmeta 也能跑起来；
> 但任何走 `Pack.readMetaAndCreate` 的仓库路径都需要真实的 `PackMetadataSection`。

### R4.6 注入点：`MultiPackResourceManager` 没变，Fabric 自己也在这里 🟢

```
public class net.minecraft.server.packs.resources.MultiPackResourceManager implements CloseableResourceManager {
  public MultiPackResourceManager(net.minecraft.server.packs.PackType, java.util.List<net.minecraft.server.packs.PackResources>);
  public java.util.Set<java.lang.String> getNamespaces();
  public java.util.Optional<Resource> getResource(Identifier);
  public java.util.List<Resource> getResourceStack(Identifier);
  public java.util.Map<Identifier, Resource> listResources(String, java.util.function.Predicate<Identifier>);
  public java.util.Map<Identifier, java.util.List<Resource>> listResourceStacks(String, java.util.function.Predicate<Identifier>);
  public java.util.stream.Stream<PackResources> listPacks();
  public void close();
  private net.minecraft.server.packs.resources.ResourceFilterSection getPackFilterSection(PackResources);
}
```

`getPackFilterSection` 字节码 🟢：

```
0: aload_1
1: getstatic ResourceFilterSection.TYPE : MetadataSectionType
4: invokeinterface PackResources.getMetadataSection:(MetadataSectionType)Object
9: checkcast ResourceFilterSection
... Exception table: 0-12 -> 13 (Class java/lang/Exception)
14: getstatic LOGGER ; 17: ldc "Failed to get filter section from pack {}"
```

Fabric 侧同款做法（`fabric-resource-loader-v1-2.0.13`，`javap -v`）🟢：

```
public class net.fabricmc.fabric.mixin.resource.MultiPackResourceManagerMixin
        implements net.fabricmc.fabric.impl.resource.FabricMultiPackResourceManager
  org.spongepowered.asm.mixin.Mixin(value=[class net/minecraft/server/packs/resources/MultiPackResourceManager;])
  org.spongepowered.asm.mixin.injection.Inject(method=["<init>"], at=[@At(value="TAIL")])
```

（它只是把 `packType` 存进 shadow 字段，供 `fabric$getPackType()` 用；**没有** `@ModifyVariable` 那类干预。）

**对移植的影响**：`CustomSkyPackMixin`（`@ModifyVariable(method="<init>", at=@At("HEAD"), argsOnly=true, index=2)`）
的注入点与参数类型在 26.2 成立（构造器仍是 `(PackType, List<PackResources>)`，`index 1 = type`、`index 2 = list`），
**但"静态 handler"这一点必须实机验证**：`@ModifyVariable` 注入构造器时 handler 的静态性/形参列表由 Mixin 决定，
现有写法把形参写成 `(List<PackResources> packs, PackType type)`（与构造器顺序相反）。最稳的形态是
`@ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true)` 不写 `index`，让 Mixin 按 handler 首个形参类型匹配。

---

## R4b. `ResourceLocation` → `Identifier`

### 结论 🟢

- `net/minecraft/resources/Identifier.class` **存在**。
- `net/minecraft/resources/ResourceLocation.class` **不存在**：类表全文搜 `ResourceLocation` 命中 **0**。

### `Identifier` 关键方法 🟢

```
public final class net.minecraft.resources.Identifier implements java.lang.Comparable<Identifier> {
  public static final com.mojang.serialization.Codec<Identifier> CODEC;
  public static final net.minecraft.network.codec.StreamCodec<io.netty.buffer.ByteBuf, Identifier> STREAM_CODEC;
  public static final com.mojang.brigadier.exceptions.SimpleCommandExceptionType ERROR_INVALID;
  public static final char NAMESPACE_SEPARATOR;
  public static Identifier fromNamespaceAndPath(java.lang.String, java.lang.String);
  public static Identifier parse(java.lang.String);
  public static Identifier withDefaultNamespace(java.lang.String);
  public static Identifier tryParse(java.lang.String);
  public static Identifier tryBuild(java.lang.String, java.lang.String);
  public static Identifier bySeparator(java.lang.String, char);
  public static Identifier tryBySeparator(java.lang.String, char);
  public static com.mojang.serialization.DataResult<Identifier> read(java.lang.String);
  public java.lang.String getPath();
  public java.lang.String getNamespace();
  public Identifier withPath(java.lang.String);
  public Identifier withPath(java.util.function.UnaryOperator<java.lang.String>);
  public Identifier withPrefix(java.lang.String);
  public Identifier withSuffix(java.lang.String);
  public java.nio.file.Path resolveAgainst(java.nio.file.Path);
  public java.lang.String toDebugFileName();
  public java.lang.String toLanguageKey();
  public java.lang.String toLanguageKey(java.lang.String);
  public java.lang.String toShortString();
  public static boolean isValidPath(java.lang.String);
  public static boolean isValidNamespace(java.lang.String);
  public static net.minecraft.resources.Identifier read(com.mojang.brigadier.StringReader) throws CommandSyntaxException;
  public static net.minecraft.resources.Identifier readNonEmpty(com.mojang.brigadier.StringReader) throws CommandSyntaxException;
}
```

（上表 `ERROR_INVALID` 的完整类型是 `com.mojang.brigadier.exceptions.SimpleCommandExceptionType`，此处按 javap 原样断行。）

**旧名对照**：`ResourceLocation.tryParse` → `Identifier.tryParse`；`ResourceLocation.fromNamespaceAndPath` → 同名；
`withPrefix`/`withSuffix`/`withPath`/`bySeparator`/`tryBuild` 全部保留；新增 `tryBySeparator`、`resolveAgainst`、
`toDebugFileName`、`toLanguageKey(...)`、`isValidPath/isValidNamespace`。**没有** `of(...)`（1.21 早期写法，已确认不存在）。

### 对移植的影响

全仓机械改名即可（`PORT-PLAN` K7 已记）。额外注意两处**不在 `Identifier` 上**的同类改名：
`net.minecraft.resources.ResourceKey.create(...)` 仍叫 `create`（🟢），但它的参数是 `Identifier`
（`ResourceKey.create(ResourceKey<? extends Registry<T>>, Identifier)`）；
`ResourceFilterSection` 用的是 `net.minecraft.util.IdentifierPattern`（🟢）。

---

## R5. 方块 / 物品 / 方块实体 / 创造标签页注册

### R5.1 `BlockIds` / `BlockItemIds` / `ItemIds` 的真面目 🟢

**它们不是注册入口，也不是"ID 表 API"，而是纯常量表**：字段类型是 `ResourceKey<Block>` / `ResourceKey<Item>`，
`create(String)` 是 **private**，构造器是 public 空构造。

```
public class net.minecraft.references.BlockIds {
  public static final net.minecraft.resources.ResourceKey<net.minecraft.world.level.block.Block> ...;  // 数百个
  public static final net.minecraft.world.level.block.ColorCollection<ResourceKey<Block>> WALL_BANNER;
  public static final net.minecraft.world.level.block.ColorCollection<ResourceKey<Block>> DYED_CANDLE_CAKE;
  public net.minecraft.references.BlockIds();
  private static net.minecraft.resources.ResourceKey<Block> create(java.lang.String);          // 私有 → 模组用不了
  private static net.minecraft.world.level.block.ColorCollection<ResourceKey<Block>> createSimpleColored(java.lang.String);
}
```

```
public class net.minecraft.references.ItemIds {
  public static final net.minecraft.resources.ResourceKey<net.minecraft.world.item.Item> SADDLE;  // 数百个
  public static final ColorCollection<ResourceKey<Item>> HARNESS / DYED_BUNDLE / DYE;
  private static ResourceKey<Item> create(java.lang.String);
  ...
}

public class net.minecraft.references.BlockItemIds {
  public static final net.minecraft.references.BlockItemId ...;   // 与方块一一对应的 BlockItemId
  public static final ColorCollection<BlockItemId> BED / WOOL / ... ;
  private static ColorCollection<BlockItemId> createSimpleColored(String);
  private static WeatheringCopperCollection<BlockItemId> createSimpleCopper(String);
}

public final class net.minecraft.references.BlockItemId extends java.lang.Record {
  public BlockItemId(ResourceKey<Block>, ResourceKey<Item>);
  public static BlockItemId create(Identifier, Identifier);
  public static BlockItemId create(java.lang.String, java.lang.String);
  public static BlockItemId create(java.lang.String);
  public ResourceKey<Block> block();  public ResourceKey<Item> item();
}
```

**用途判定**：这套东西是给**原版自己**（`Blocks`/`Items` 的 `register(...)` 辅助方法）减少重复代码的。
模组侧唯一有用的部分：想引用原版 ID 时可用 `BlockIds.STONE` 之类；**自建方块/物品仍要自己造 `ResourceKey`**：

```
public static <T> ResourceKey<T> create(ResourceKey<? extends Registry<T>> registry, Identifier id)   // 🟢
// 配合 net.minecraft.core.registries.Registries.BLOCK / ITEM / BLOCK_ENTITY_TYPE / CREATIVE_MODE_TAB（🟢 四个常量均存在）
```

### R5.2 注册入口 `Registry.register` / `WritableRegistry.register` 🟢

```
public interface net.minecraft.core.Registry<T> extends IdMap<T>, Keyable, HolderLookup$RegistryLookup<T>, FabricRegistry {
  public static <T> T register(Registry<? super T>, java.lang.String, T);
  public static <V, T extends V> T register(Registry<V>, Identifier, T);
  public static <V, T extends V> T register(Registry<V>, ResourceKey<V>, T);
  public static <R, T extends R> Holder$Reference<T> registerForHolder(Registry<R>, ResourceKey<R>, T);
  public static <R, T extends R> Holder$Reference<T> registerForHolder(Registry<R>, Identifier, T);
  ...
}

public interface net.minecraft.core.WritableRegistry<T> extends Registry<T> {
  public abstract Holder$Reference<T> register(ResourceKey<T>, T, net.minecraft.core.RegistrationInfo);
  ...
}
```

`BuiltInRegistries` 里的目标注册表全部还在 🟢：`BLOCK`（`DefaultedRegistry<Block>`）、`ITEM`（`DefaultedRegistry<Item>`）、
`BLOCK_ENTITY_TYPE`（`Registry<BlockEntityType<?>>`）、`CREATIVE_MODE_TAB`（`Registry<CreativeModeTab>`），
外加 `DATA_COMPONENT_INITIALIZERS`。

### R5.3 `BlockBehaviour.Properties` 🟢

包名：`net.minecraft.world.level.block.state.BlockBehaviour$Properties`（**注意是 `state` 子包**，
`net.minecraft.world.level.block.BlockBehaviour` 不存在）。

```
public class net.minecraft.world.level.block.state.BlockBehaviour$Properties
        implements net.fabricmc.fabric.api.block.v1.FabricBlock$FabricProperties {
  private BlockBehaviour$Properties();
  public static BlockBehaviour$Properties of();
  public static BlockBehaviour$Properties ofFullCopy(BlockBehaviour);
  public static BlockBehaviour$Properties ofLegacyCopy(BlockBehaviour);
  public BlockBehaviour$Properties mapColor(MapColor);                     public BlockBehaviour$Properties mapColor(DyeColor);
  public BlockBehaviour$Properties noCollision();                          public BlockBehaviour$Properties noOcclusion();
  public BlockBehaviour$Properties strength(float, float);                 public BlockBehaviour$Properties strength(float);
  public BlockBehaviour$Properties sound(SoundType);
  public BlockBehaviour$Properties lightLevel(java.util.function.ToIntFunction<BlockState>);
  public BlockBehaviour$Properties isViewBlocking(BlockBehaviour$StatePredicate);
  public BlockBehaviour$Properties isSuffocating(BlockBehaviour$StatePredicate);
  public BlockBehaviour$Properties isRedstoneConductor(BlockBehaviour$StatePredicate);
  public BlockBehaviour$Properties noLootTable();
  public BlockBehaviour$Properties overrideLootTable(java.util.Optional<ResourceKey<LootTable>>);
  public BlockBehaviour$Properties dynamicShape();  public BlockBehaviour$Properties randomTicks();
  public BlockBehaviour$Properties requiredFeatures(net.minecraft.world.flag.FeatureFlag...);
  public BlockBehaviour$Properties setId(net.minecraft.resources.ResourceKey<Block>);          // ← 新增，必调
  public BlockBehaviour$Properties overrideDescription(java.lang.String);
  protected java.util.Optional<ResourceKey<LootTable>> effectiveDrops();
  protected java.lang.String effectiveDescriptionId();
}
```

**`setId` 是硬要求**（不是可选）：原版 `Blocks.register(ResourceKey<Block>, Properties)` 内部第一件事就是
`Properties.setId(ResourceKey)`（🟢 字节码 `invokevirtual BlockBehaviour$Properties.setId:(Lnet/minecraft/resources/ResourceKey;)...`）。
模组手动 `Registry.register` 时没有这一层包装，必须自己调，否则方块/物品的 `descriptionId`、模型路径、
数据组件初始化 key 都会落到"无名"状态。

### R5.4 `Item.Properties` / `BlockItem` 🟢

```
public class net.minecraft.world.item.Item$Properties implements net.fabricmc.fabric.api.item.v1.FabricItem$Properties {
  public Item$Properties();
  public Item$Properties stacksTo(int);  public Item$Properties durability(int);  public Item$Properties rarity(Rarity);
  public Item$Properties food(net.minecraft.world.food.FoodProperties);
  public Item$Properties component(DataComponentType<T>, T);
  public Item$Properties setId(net.minecraft.resources.ResourceKey<Item>);        // ← 必调
  public final ResourceKey<Item> itemIdOrThrow();
  public Identifier effectiveModel();
  public Item$Properties useBlockDescriptionPrefix();  public Item$Properties useItemDescriptionPrefix();
}

public class net.minecraft.world.item.BlockItem extends net.minecraft.world.item.Item {
  public BlockItem(net.minecraft.world.level.block.Block, net.minecraft.world.item.Item$Properties);
  public net.minecraft.world.level.block.Block getBlock();
}
```

**`Item` 构造器会强制取 id**（🟢 字节码）：

```
public net.minecraft.world.item.Item(Item$Properties):
   5: getstatic BuiltInRegistries.ITEM
   9: invokeinterface DefaultedRegistry.createIntrusiveHolder   -> builtInRegistryHolder
  19: invokevirtual Item$Properties.effectiveDescriptionId
  34: invokevirtual Item$Properties.effectiveModel
  47: invokevirtual Item$Properties.itemIdOrThrow               <-- 没 setId 就在这里抛
  49: invokevirtual DataComponentInitializers.add(ResourceKey, Initializer)
```

`Items.registerBlock(BlockItemId, ...)` 等原版辅助方法同样先 `Item$Properties.setId(...)`（🟢）。
→ **结论：`new BlockItem(block, new Item.Properties().setId(itemKey))`**，否则构造即抛。

### R5.5 `BlockEntityType`：`.Builder.of(...)` 已不存在 🟢

```
public class net.minecraft.world.level.block.entity.BlockEntityType<T extends BlockEntity>
        implements net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityType {
  public BlockEntityType(net.minecraft.world.level.block.entity.BlockEntityType$BlockEntitySupplier<? extends T>,
                         java.util.Set<net.minecraft.world.level.block.Block>);      // ← 公开构造器
  public T create(BlockPos, BlockState);
  public boolean isValid(BlockState);
  public Holder$Reference<BlockEntityType<?>> builtInRegistryHolder();
  public T getBlockEntity(BlockGetter, BlockPos);
  public boolean onlyOpCanSetNbt();
}

public interface net.minecraft.world.level.block.entity.BlockEntityType$BlockEntitySupplier<T extends BlockEntity> {
  public abstract T create(net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState);
}
```

- `net.minecraft.world.level.block.entity.BlockEntityType$Builder`：`javap` 报 class not found，类表里也只有
  `BlockEntityType$BlockEntitySupplier`（🟢）→ **`BlockEntityType.Builder.of(...).build(null)` 写在 26.2 编译不过**。
- 同类工具类 `BlockEntityTypes`（`register(ResourceKey<BlockEntityType<?>>, BlockEntitySupplier, Block.../List<Block>)`）存在但 **全 private** 🟢。
- Fabric 侧替代（`fabric-object-builder-api-v1-24.1.1`）🟢：

```
public final class net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder<T extends BlockEntity> {
  public static <T extends BlockEntity> FabricBlockEntityTypeBuilder<T> create(
      FabricBlockEntityTypeBuilder$Factory<? extends T>, Block...);
  public FabricBlockEntityTypeBuilder<T> addBlock(Block);
  public FabricBlockEntityTypeBuilder<T> addBlocks(Block...);
  public FabricBlockEntityTypeBuilder<T> addBlocks(java.util.Collection<? extends Block>);
  public FabricBlockEntityTypeBuilder<T> canPotentiallyExecuteCommands(boolean);
  public net.minecraft.world.level.block.entity.BlockEntityType<T> build();
  public net.minecraft.world.level.block.entity.BlockEntityType<T> build(com.mojang.datafixers.types.Type<?>);
}
```

`BlockEntity` 构造器仍是 `BlockEntity(BlockEntityType<?>, BlockPos, BlockState)` 🟢；
`EntityBlock.newBlockEntity(BlockPos, BlockState)` 是唯一抽象方法 🟢；`BaseEntityBlock` 仍是
`protected BaseEntityBlock(BlockBehaviour$Properties)` + `protected abstract MapCodec<? extends BaseEntityBlock> codec()` 🟢。

### R5.6 创造标签页：自定义 tab 怎么塞物品 🟢

```
public class net.minecraft.world.item.CreativeModeTab {
  public static CreativeModeTab$Builder builder(net.minecraft.world.item.CreativeModeTab$Row, int);
  public static Identifier createTextureLocation(java.lang.String);
  public void buildContents(CreativeModeTab$ItemDisplayParameters);
  public java.util.Collection<ItemStack> getDisplayItems();
  public Component getDisplayName();  public ItemStack getIconItem();
  public Identifier getBackgroundTexture();  public boolean showTitle();  public boolean canScroll();
  public int column();  public CreativeModeTab$Row row();  public CreativeModeTab$Type getType();
}

public class net.minecraft.world.item.CreativeModeTab$Builder {
  public CreativeModeTab$Builder(net.minecraft.world.item.CreativeModeTab$Row, int);
  public CreativeModeTab$Builder title(Component);
  public CreativeModeTab$Builder icon(java.util.function.Supplier<ItemStack>);
  public CreativeModeTab$Builder displayItems(CreativeModeTab$DisplayItemsGenerator);
  public CreativeModeTab$Builder alignedRight();  public CreativeModeTab$Builder hideTitle();
  public CreativeModeTab$Builder noScrollBar();
  public CreativeModeTab$Builder backgroundTexture(Identifier);
  public CreativeModeTab build();
}

public interface net.minecraft.world.item.CreativeModeTab$DisplayItemsGenerator {
  public abstract void accept(CreativeModeTab$ItemDisplayParameters, CreativeModeTab$Output);
}

public interface net.minecraft.world.item.CreativeModeTab$Output {
  public abstract void accept(ItemStack, CreativeModeTab$TabVisibility);
  public default void accept(ItemStack);
  public default void accept(net.minecraft.world.level.ItemLike, CreativeModeTab$TabVisibility);
  public default void accept(net.minecraft.world.level.ItemLike);
  public default void acceptAll(java.util.Collection<ItemStack>, CreativeModeTab$TabVisibility);
  public default void acceptAll(java.util.Collection<ItemStack>);
}
```

枚举常量（🟢）：`CreativeModeTab$Row.TOP / BOTTOM`；`ItemDisplayParameters(FeatureFlagSet, boolean, HolderLookup$Provider)`。

**Fabric 侧的写法变了** 🟢：
`net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup` **已不存在**（在所有 Fabric 模块 jar 里搜 `*itemgroup*` 命中 0）；
`fabric-creative-tab-api-v1-5.0.15` 提供：

```
public final class net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab {
  public static net.minecraft.world.item.CreativeModeTab$Builder builder();     // 直接返回原版 Builder
}
public final class net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents {
  public static net.fabricmc.fabric.api.event.Event<CreativeModeTabEvents$ModifyOutput>
      modifyOutputEvent(ResourceKey<CreativeModeTab>);
}
public class net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTabOutput implements CreativeModeTab$Output {
  public void prepend(net.minecraft.world.item.ItemStack);
  public void prepend(net.minecraft.world.level.ItemLike);
  public void insertAfter(net.minecraft.world.level.ItemLike, net.minecraft.world.item.ItemStack...);
  public void insertBefore(net.minecraft.world.level.ItemLike, net.minecraft.world.item.ItemStack...);
  public void insertAfter(net.minecraft.world.level.ItemLike, java.util.Collection<ItemStack>);
  public void insertBefore(net.minecraft.world.level.ItemLike, java.util.Collection<ItemStack>);
  public net.minecraft.world.item.CreativeModeTab$ItemDisplayParameters getContext();
  public net.minecraft.world.flag.FeatureFlagSet getEnabledFeatures();
  public boolean shouldShowOpRestrictedItems();
  public java.util.List<net.minecraft.world.item.ItemStack> getDisplayStacks();
}
```

→ 往**原版已有 tab** 加物品用 `CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.XXX).register(...)`；
建**自己的 tab** 用 `FabricCreativeModeTab.builder()`（或直接 `CreativeModeTab.builder(Row, int)`）再 `Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, ...)`。
`CreativeModeTabs` 本体仍在：`bootstrap(Registry<CreativeModeTab>)`、`tabs()`、`allTabs()`、`searchTab()`、
`tryRebuildTabContents(FeatureFlagSet, boolean, HolderLookup$Provider)` 🟢。

### R5.7 对移植的影响（逐行）

| 位置 | 现状写法 | 26.2 必改 |
|---|---|---|
| `SkyboxBlockMod:39` | `Registry.register(BuiltInRegistries.BLOCK, id, block)` | 保留（🟢 静态重载在），但 `Properties` 要 `.setId(blockKey)` |
| `SkyboxBlockMod:45` | `new BlockItem(block, new Item.Properties())` | → `new Item.Properties().setId(itemKey)`，否则构造抛 |
| `SkyboxBlockMod:72` | `BlockEntityType.Builder.of(SkyBlockEntity::new, …).build(null)` | **Builder 不存在** → `new BlockEntityType<>(SkyBlockEntity::new, Set.of(...))` 或 `FabricBlockEntityTypeBuilder.create(...).build()` |
| `SkyboxBlockMod:73` | `FabricItemGroup.builder()` | 导入换成 `net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab`（`builder()` 无参） |
| `SkyboxBlockMod:76` | `.displayItems((p, out) -> …)` | 保留（`DisplayItemsGenerator` 签名未变） |
| 全部 `BlockIds`/`ItemIds` 引用 | — | 只是常量表，不参与注册；`create` 私有，别指望拿它造自定义 ID |

---

## R5b. blockstate / 模型 JSON：数据生成器变形了，但**运行时仍然读 JSON**

### R5b.1 类搬家情况 🟢

| 1.21.1 名称 | 26.2 状态 |
|---|---|
| `net.minecraft.data.models.BlockModelGenerators` | ❌ 包不存在；搬到 `net.minecraft.client.data.models.BlockModelGenerators` |
| `net.minecraft.data.models.blockstates.MultiVariantGenerator` | ❌ 搬到 `net.minecraft.client.data.models.blockstates.MultiVariantGenerator` |
| `net.minecraft.client.renderer.block.model.BlockStateModelLoader` | ❌ 搬到 `net.minecraft.client.resources.model.BlockStateModelLoader` |
| （新） | `net.minecraft.client.data.models.blockstates.BlockModelDefinitionGenerator` / `MultiPartGenerator` / `ConditionBuilder` / `PropertyDispatch` |
| （新） | `net.minecraft.client.renderer.block.dispatch.*`：`BlockStateModelDispatcher`、`Variant`、`VariantMutator`、`multipart.Condition` |

`BlockModelGenerators` 现在是**普通类 + 实例构造器**（不再是纯静态工具）🟢：

```
public class net.minecraft.client.data.models.BlockModelGenerators {
  public BlockModelGenerators(java.util.function.Consumer<BlockModelDefinitionGenerator>,
                              net.minecraft.client.data.models.ItemModelOutput,
                              java.util.function.BiConsumer<Identifier, ModelInstance>);
  public static MultiVariant plainVariant(Identifier);
  public static MultiVariant variants(net.minecraft.client.renderer.block.dispatch.Variant...);
  public static net.minecraft.client.data.models.blockstates.MultiVariantGenerator createSimpleBlock(Block, MultiVariant);
  public final void createTrivialCube(Block);
  public final void registerSimpleItemModel(Item, Identifier);
  public final void registerSimpleItemModel(Block, Identifier);
  public void run();
}
public interface net.minecraft.client.data.models.blockstates.BlockModelDefinitionGenerator {
  Block block();
  net.minecraft.client.renderer.block.dispatch.BlockStateModelDispatcher create();
}
```

### R5b.2 运行时仍按目录读 JSON（字节码铁证）🟢

```
net.minecraft.client.resources.model.BlockStateModelLoader:
  public static java.util.concurrent.CompletableFuture<LoadedModels> loadBlockStates(
      net.minecraft.server.packs.resources.ResourceManager, java.util.concurrent.Executor);
  ... 6: ldc_w #372   // String blockstates
  ... ldc_w "Failed to load blockstate definition {} from pack {}"

net.minecraft.client.resources.model.ClientItemInfoLoader:
  ... 6: ldc_w #376   // String items
  ... ldc_w "Failed to open item model {} from pack '{}'"
  ... ldc_w "Couldn't parse item model '{}' from pack '{}'"
```

→ **不需要跑 datagen**：只要资源包里出现 `assets/<ns>/blockstates/<name>.json` 与 `assets/<ns>/items/<name>.json`，
客户端 reload 就会吃掉。`BlockModelGenerators` 只是"生成这些 JSON 的开发期工具"，运行时与它无关。

### R5b.3 26.2 实际 JSON 形态（从 jar 里 `assets/` 直接读取，🟢）

`assets/minecraft/blockstates/stone.json` —— 仍然是 `variants`，且**允许四元素数组**（加权/变体列表，新）：

```json
{
  "variants": {
    "": [
      { "model": "minecraft:block/stone" },
      { "model": "minecraft:block/stone_mirrored" },
      { "model": "minecraft:block/stone", "y": 180 },
      { "model": "minecraft:block/stone_mirrored", "y": 180 }
    ]
  }
}
```

`assets/minecraft/blockstates/oak_stairs.json` —— 属性键仍是 `facing=…,half=…,shape=…`，值可带 `uvlock`/`x`/`y`：

```json
{ "variants": { "facing=east,half=bottom,shape=inner_left": { "model": "minecraft:block/oak_stairs_inner", "uvlock": true, "y": 270 } } }
```

`assets/minecraft/items/stone.json` —— **新的"客户端物品定义"**（1.21.4+ 引入，26.2 的 1,538 个物品全在 `items/` 下）：

```json
{ "model": { "type": "minecraft:model", "model": "minecraft:block/stone" } }
```

`assets/minecraft/atlases/blocks.json` —— 方块图集用 **directory 源**自动收 `textures/block/**`，不需要为每张贴图写配置：

```json
{ "sources": [ { "type": "minecraft:directory", "prefix": "block/", "source": "block" }, ... ] }
```

统计（🟢 类表）：`assets/minecraft/items/*.json` = 1,538 个；`assets/minecraft/models/item/*.json` = 1,272 个（旧路径仍在，用于 parent 继承/兼容）。

### R5b.4 对移植的影响

- **内存 pack 必须同时产出三件套**：`blockstates/<id>.json`、`items/<id>.json`（物品形态）、
  `models/block/<id>.json`（几何），三者缺一个就是紫黑格或缺模型。
- 旧代码若写 `models/item/<id>.json`（1.21.1 习惯），在 26.2 里**只有作为 parent 才会被读到**，
  物品栏显示需要 `items/<id>.json` → 这是本轮最容易踩的坑。
- `variants` 的值可以是对象**也可以是数组**，因此"一个 blockstate 对应多模型"不需要 multipart。
- 不需要引入 `net.minecraft.client.data.models` 任何东西：那是 datagen 侧，纯客户端开发期。

---

## R10. Fabric API 侧：能不能注册"运行时生成"的 pack？

### R10.1 模块现状 🟢

`fabric-resource-loader-v0/fabric.mod.json`（3.3.20）：

```json
"depends": { "fabricloader": ">=0.18.4", "fabric-resource-loader-v1": "*" },
"custom": { "fabric-api:module-lifecycle": "deprecated" }
```

→ **v0 已是 deprecated**，实现依赖 v1。新代码应直接用 v1。

### R10.2 `ResourceManagerHelper`（v0）提供的全部能力 🟢

```
public interface net.fabricmc.fabric.api.resource.ResourceManagerHelper {
  public default void addReloadListener(IdentifiableResourceReloadListener);
  public abstract void registerReloadListener(IdentifiableResourceReloadListener);
  public abstract void registerReloadListener(Identifier, java.util.function.Function<HolderLookup$Provider, IdentifiableResourceReloadListener>);
  public static ResourceManagerHelper get(PackType);
  public static boolean registerBuiltinResourcePack(Identifier, net.fabricmc.loader.api.ModContainer, ResourcePackActivationType);
  public static boolean registerBuiltinResourcePack(Identifier, ModContainer, net.minecraft.network.chat.Component, ResourcePackActivationType);
  public static boolean registerBuiltinResourcePack(Identifier, ModContainer, String, ResourcePackActivationType);
  public static boolean registerBuiltinResourcePack(Identifier, String, ModContainer, boolean);
}
public enum net.fabricmc.fabric.api.resource.ResourcePackActivationType { NORMAL, DEFAULT_ENABLED, ALWAYS_ENABLED }
public interface net.fabricmc.fabric.api.resource.ModResourcePack extends net.fabricmc.fabric.api.resource.v1.pack.ModPackResources {
  ModMetadata getFabricModMetadata();
  ModResourcePack createOverlay(String);
}
```

### R10.3 `ResourceLoader`（v1）🟢

```
public interface net.fabricmc.fabric.api.resource.v1.ResourceLoader {
  public static ResourceLoader get(net.minecraft.server.packs.PackType);
  public abstract void registerReloadListener(Identifier, net.minecraft.server.packs.resources.PreparableReloadListener);
  public abstract void addListenerOrdering(Identifier, Identifier);
  public static boolean registerBuiltinPack(Identifier, ModContainer, PackActivationType);
  public static boolean registerBuiltinPack(Identifier, ModContainer, Component, PackActivationType);
}
public interface net.fabricmc.fabric.api.resource.v1.DataResourceLoader extends ResourceLoader {
  public static DataResourceLoader get();
  public abstract void registerReloadListener(Identifier, java.util.function.Function<HolderLookup$Provider, PreparableReloadListener>);
}
public interface net.fabricmc.fabric.api.resource.v1.pack.ModPackResources extends net.minecraft.server.packs.PackResources {
  ModMetadata getFabricModMetadata();
  ModPackResources createOverlay(String);
}
```

### R10.4 结论：**没有**注册"运行时生成 pack"的公开入口

逐条排除（🟢 全部来自上面的签名）：

1. `registerBuiltinResourcePack` / `registerBuiltinPack` 的每一个重载都要求 `ModContainer` —— 即
   **包内容必须在模组 jar 里**（`assets/<id>` / `data/<id>` + `pack.mcmeta`）。运行时在磁盘上生成的目录塞不进去。
2. `registerReloadListener` 只挂"重载监听器"，不参与资源解析，改不了被读取的 pack 列表。
3. `net.fabricmc.fabric.impl.resource.pack.ModPackResourcesFactory` 是 **public record**，构造器 public，
   接受任意 `ModPackResources` 并实现 `Pack$ResourcesSupplier`：

   ```
   public final class ModPackResourcesFactory extends Record implements Pack$ResourcesSupplier {
     public ModPackResourcesFactory(net.fabricmc.fabric.api.resource.v1.pack.ModPackResources);
     public PackResources openPrimary(PackLocationInfo);
     public PackResources openFull(PackLocationInfo, Pack$Metadata);
   }
   ```

   但没有任何公开方法把它交给 `PackRepository`：`ModResourcePackCreator implements RepositorySource` 的
   `loadPacks(Consumer<Pack>)` 是 public，但内部只用 **private** 的 `registerModPack(Consumer, String, Predicate<Set<String>>)`
   枚举 Fabric 自己的模块包（🟢 javap 签名），外部无法往里塞自定义 `Pack`；`ModResourcePackCreator` 的构造器虽 public，
   却没有任何公开入口把这个 creator 注册进游戏里的 `PackRepository`。
4. `ModPackRepository` 侧的入口也没有：`PackRepository` 只有 `PackRepository(RepositorySource...)` 构造器和
   `addPack(String)` / `removePack(String)`（按**已发现**的 id 操作，🟢），没有 `addSource` / `setSources`。
5. `ModPackResourcesUtil` 是**读**方向（`getModResourcePacks(FabricLoader, PackType, String)` → `List<ModPackResources>`），
   不是注册方向。

**因此**：想注入"运行时生成"的 pack，只剩三条路 🟡（基于上面实测事实的组合，未编译）：

| 路线 | 做法 | 评价 |
|---|---|---|
| A（现状） | mixin `MultiPackResourceManager.<init>`，把自建 `PackResources` 追加进 `List` | 现有实现即此；26.2 签名未变，Fabric 自己也在这注入 |
| B | 自建 `RepositorySource` + `new Pack(location, new ModPackResourcesFactory(自建 ModPackResources), metadata, selectionConfig)`，再 mixin 一处把 source 塞进 `PackRepository` | 仍需 1 处 mixin，收益是"包在 UI 里可见、可开关" |
| C | 只走 `ModContainer`：把生成物**预先**写进 jar（构建期） | 与"运行时解析 `.conf` 生成"的需求冲突，不适用 |

`Pack` 的构造器与工厂（🟢）：

```
public class net.minecraft.server.packs.repository.Pack {
  public Pack(PackLocationInfo, Pack$ResourcesSupplier, Pack$Metadata, PackSelectionConfig);
  public static Pack readMetaAndCreate(PackLocationInfo, Pack$ResourcesSupplier, PackType, PackSelectionConfig);
  public static Pack$Metadata readPackMetadata(PackLocationInfo, Pack$ResourcesSupplier, PackFormat, PackType);
  public PackResources open();
}
```

---

## 最小可行写法

> 标注：**🟢 = 只用实测签名**；**🟡 = 由实测签名推断的组装方式，未编译**。

### A. 内存 pack（🟢 签名 / 🟡 组装）

```java
package dev.yg.skyboxblock.custom;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.AbstractPackResources;                    // 🟢
import net.minecraft.server.packs.PackLocationInfo;                        // 🟢
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;                                // 🟢
import net.minecraft.server.packs.metadata.MetadataSectionType;            // 🟢（不是 MetadataSectionSerializer！）
import net.minecraft.server.packs.resources.IoSupplier;                    // 🟢
import net.minecraft.server.packs.resources.ResourceMetadata;              // 🟢

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 全内存资源包：key 形如 "assets/skyboxblock/blockstates/sky_x.json"。 */
public final class MemoryPack extends AbstractPackResources {              // 🟢 只需 5 个方法
    private final Map<String, byte[]> files;
    private final ResourceMetadata meta;                                   // 🟢 of(MetadataSectionType, T)

    public MemoryPack(PackLocationInfo info, Map<String, byte[]> files, ResourceMetadata meta) {
        super(info);                                                       // 🟢 protected AbstractPackResources(PackLocationInfo)
        this.files = files;
        this.meta = meta;
    }

    // 1/5
    @Override public IoSupplier<InputStream> getRootResource(String... segments) {
        String key = String.join("/", segments);                           // loadMetadata 只会要 "pack.mcmeta"
        byte[] data = files.get(key);
        return data == null ? null : () -> new ByteArrayInputStream(data);  // 🟢 返回 null 合法（→ ResourceMetadata.EMPTY）
    }

    // 2/5
    @Override public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
        byte[] data = files.get(type.getDirectory() + "/" + id.getNamespace() + "/" + id.getPath());
        return data == null ? null : () -> new ByteArrayInputStream(data);
    }

    // 3/5
    @Override public void listResources(PackType type, String namespace, String prefix,
                                        PackResources.ResourceOutput output) {   // 🟢 ResourceOutput = BiConsumer
        String base = type.getDirectory() + "/" + namespace + "/" + prefix;
        files.forEach((key, data) -> {
            if (!key.startsWith(base)) return;
            String path = key.substring(type.getDirectory().length() + namespace.length() + 2);
            output.accept(Identifier.fromNamespaceAndPath(namespace, path),     // 🟢
                          () -> new ByteArrayInputStream(data));
        });
    }

    // 4/5
    @Override public Set<String> getNamespaces(PackType type) {            // 🟢 Set<String>，不是 Collection
        String prefix = type.getDirectory() + "/";
        Set<String> out = new LinkedHashSet<>();
        files.keySet().forEach(k -> {
            if (k.startsWith(prefix)) out.add(k.substring(prefix.length(), k.indexOf('/', prefix.length())));
        });
        return out;
    }

    // 5/5
    @Override public void close() { }

    // 可选：跳过 pack.mcmeta，直接给内存 metadata（覆盖 AbstractPackResources 的惰性 JSON 解析）
    @Override public <T> T getMetadataSection(MetadataSectionType<T> type) {   // 🟢
        return meta.getSection(type).orElse(null);
    }
}
```

构造 metadata（不写 JSON 的路线，🟢 签名 / 🟡 组装）：

```java
ResourceMetadata meta = ResourceMetadata.of(
        PackMetadataSection.CLIENT_TYPE,                                        // 🟢
        new PackMetadataSection(Component.literal("skyboxblock custom"),
                new InclusiveRange<>(PackFormat.of(88))));                      // 🟢 PackFormat.of(int) / InclusiveRange(T)
```

注入（沿用现有 mixin，🟢 目标签名 / 🟡 下标需现场核）：

```java
@Mixin(net.minecraft.server.packs.resources.MultiPackResourceManager.class)
public class CustomSkyPackMixin {
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, index = 2)
    private static List<PackResources> skyboxblock$append(List<PackResources> packs, PackType type) { … }
}
```

> 若走 mixin 不想动下标：改用 `@Inject(method="<init>", at=@At("TAIL"))` 拿不到参数，需
> `@ModifyArg(method="<init>", at=@At(value="INVOKE", target="..."))`；最稳的是先用
> `@ModifyVariable(argsOnly=true)` 不带 `index`（按类型唯一匹配）。🟡

### B. 注册一个方块 + 物品 + 方块实体 + 创造页（🟢 签名 / 🟡 组装）

```java
public final class Registration {
    static final Identifier BLOCK_ID = Identifier.fromNamespaceAndPath("skyboxblock", "sky_block");   // 🟢
    static final ResourceKey<Block> BLOCK_KEY = ResourceKey.create(Registries.BLOCK, BLOCK_ID);       // 🟢
    static final ResourceKey<Item>  ITEM_KEY  = ResourceKey.create(Registries.ITEM,  BLOCK_ID);        // 🟢

    static Block BLOCK;
    static BlockEntityType<SkyBlockEntity> BE;

    static void init() {
        // 1) 方块：Properties#setId 必调（🟢 原版 Blocks.register 内部行为）
        BLOCK = Registry.register(BuiltInRegistries.BLOCK, BLOCK_KEY,
                new SkyBlock(BlockBehaviour.Properties.of()
                        .setId(BLOCK_KEY)                                          // 🟢
                        .mapColor(MapColor.COLOR_LIGHT_BLUE)
                        .strength(0.3F)
                        .sound(SoundType.GLASS)
                        .noOcclusion()
                        .isViewBlocking((s, l, p) -> false)
                        .isSuffocating((s, l, p) -> false)));                      // 🟢 Registry.register 静态重载

        // 2) 物品：Item 构造器内部调 itemIdOrThrow()，所以必须先 setId（🟢 字节码）
        Registry.register(BuiltInRegistries.ITEM, ITEM_KEY,
                new BlockItem(BLOCK, new Item.Properties().setId(ITEM_KEY)));      // 🟢

        // 3) 方块实体：BlockEntityType.Builder 已删 → 公开构造器 (Supplier, Set<Block>)
        BE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                Identifier.fromNamespaceAndPath("skyboxblock", "sky_block"),
                new BlockEntityType<>(SkyBlockEntity::new, Set.of(BLOCK)));        // 🟢
        // 等价 Fabric 写法（🟢）：
        // FabricBlockEntityTypeBuilder.create(SkyBlockEntity::new, BLOCK).build()

        // 4) 自己的创造页
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
                Identifier.fromNamespaceAndPath("skyboxblock", "sky"),
                net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab.builder()   // 🟢 无参
                        .title(Component.translatable("itemGroup.skyboxblock.sky"))
                        .icon(() -> new ItemStack(BLOCK))
                        .displayItems((params, output) -> output.accept(BLOCK))          // 🟢 DisplayItemsGenerator
                        .build());
    }
}
```

方块类骨架（🟢 全部签名已在 §R5.5 核对）：

```java
public final class SkyBlock extends BaseEntityBlock {
    public static final MapCodec<SkyBlock> CODEC = simpleCodec(SkyBlock::new);      // 🟢 BlockBehaviour.simpleCodec
    public SkyBlock(Properties p) { super(p); }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }  // 🟢 抽象方法
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new SkyBlockEntity(pos, state); }
}
public final class SkyBlockEntity extends BlockEntity {
    public SkyBlockEntity(BlockPos pos, BlockState state) { super(Registration.BE, pos, state); }  // 🟢
}
```

### C. 内存 pack 必须产出的资源文件（🟢 形态取自 jar 内实物）

```
pack.mcmeta                                          # 仅在走 Pack/Repository 路线时需要
assets/skyboxblock/blockstates/sky_block.json        # {"variants":{"":{"model":"skyboxblock:block/sky_block"}}}
assets/skyboxblock/items/sky_block.json              # {"model":{"type":"minecraft:model","model":"skyboxblock:block/sky_block"}}
assets/skyboxblock/models/block/sky_block.json       # {"parent":"minecraft:block/cube_all","textures":{"all":"skyboxblock:block/sky_block"}}
assets/skyboxblock/textures/block/sky_block.png      # 会被 atlases/blocks.json 的 directory 源自动收进方块图集
assets/skyboxblock/lang/en_us.json                   # {"block.skyboxblock.sky_block": "Sky Block"}
```

**顺序要点**：`blockstates/` 与 `items/` 由运行时 loader 直接读目录（🟢），`textures/block/**` 由图集 directory 源自动收集（🟢），
`models/block/*.json` 由 `ModelBakery` 按 `Identifier` 拉取 —— 三者都经过同一个 `ResourceManager`，所以内存 pack 全部能供上。

---

## 待现场验证（本轮查不到 / 无法只靠 javap 定论）

| 项 | 为什么定不了 | 建议验证方式 |
|---|---|---|
| `@ModifyVariable(index = 2)` 在 26.2 是否仍指向 `List<PackResources>` | javap 只能看签名，看不到 mixin 的局部变量表语义 | 实机启动看 mixin apply 是否成功 |
| `BlockBehaviour.Properties#setId` 缺失时方块侧是否**立即**抛 | 只在 `Blocks.register` 里看到调用 `setId`；`BlockBehaviour` 构造器无 `itemIdOrThrow` 式断言 | 写两个反例（有/无 setId）跑客户端 |
| Fabric 是否在 26.2 另开了"动态 pack"入口（非 public API 之外的实验 API） | 只核对了 `fabric-resource-loader-v0/v1` 两个模块 jar 的全部 class | 若要押注，去 Fabric API 源码/issue 查 `RepositorySource` 注入 |
| `PackFormat` 对 26.2 资源包的正确 major/minor | `PORT-PLAN` 记资源包格式 88.0，但 `PackFormat.of(...)` 的具体取值只有写 `pack.mcmeta` 时才需要 | 走注入路线时可完全不填（§R4.5 实测：manager 只要 `ResourceFilterSection`） |
