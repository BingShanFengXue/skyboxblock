# R7 / R7b / R7c / R11 / R11b —— MC 26.2 命令树 + 自定义网络包现状

> 只读调查报告。**本次未运行 gradle、未修改任何 `.java`、未改动 `skybox-mod/`**，全部结论来自 `javap` 反汇编与 jar 内 class 清单实测。
>
> 取证对象（本机实测存在的路径）：
>
> | 目标 | 路径 / 版本 |
> |---|---|
> | MC 26.2 合并 jar（未混淆） | `skybox-mod-26.2\.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-a8e9c7b123\26.2\minecraft-merged-a8e9c7b123-26.2.jar` |
> | 全量 class 清单（10952 项） | `skybox-mod-26.2\build\mc262-classes.txt` |
> | Brigadier | `com.mojang:brigadier:1.3.10`（jar 在 `.gradle-home\caches\modules-2\files-2.1\com.mojang\brigadier\1.3.10\d15b53a1…\brigadier-1.3.10.jar`） |
> | Fabric command API | `fabric-command-api-v2 3.1.0+00cb03469e` |
> | Fabric networking API | `fabric-networking-api-v1 6.3.4+2989c6a09e` |
> | Fabric permission API | `fabric-permission-api-v1 1.0.5+e442d8d09e` |
> | javap | `C:\Program Files\Java\jdk-25.0.4.1\bin\javap.exe` |
>
> 复现命令模板（classpath 用 `;` 拼）：
>
> ```powershell
> & 'C:\Program Files\Java\jdk-25.0.4.1\bin\javap.exe' -p -classpath "$mc;$brigadier;$fabricNet" <FQCN>
> # 需要看实现时加 -c
> ```

---

## R7 命令参数的序列化面

### R7.1 结论

1. **`net.minecraft.commands.arguments.EntityArgument` 完整保留**，包路径、类名、`entity()/entities()/player()/players()` 四个工厂、`getEntity/getEntities/getOptionalEntities/getOptionalPlayers/getPlayer/getPlayers` 六个取值器全部未改名。**`getPlayers` 返回 `Collection<ServerPlayer>`**（不是 `List`），`getPlayer` 返回 `ServerPlayer`。
2. **26.2 判断"某参数类型可序列化"的机制 = 一张 `Class → ArgumentTypeInfo` 的静态 HashMap + 一个内置注册表**：
   - `ArgumentTypeInfos.BY_CLASS`（`private static final Map<Class<?>, ArgumentTypeInfo<?,?>>`，只在静态块里 `Maps.newHashMap()`）；
   - `BuiltInRegistries.COMMAND_ARGUMENT_TYPE`（`Registry<ArgumentTypeInfo<?,?>>`，`Registries.COMMAND_ARGUMENT_TYPE` 的 `ResourceKey`）。
   - 两者由**同一个** `register(Registry, String, Class, ArgumentTypeInfo)` 一次性写入（`BY_CLASS.put(cls, info)` + `Registry.register(registry, name, info)`）。
3. **白名单不在 `ClientboundCommandsPacket` 里，而在 `ArgumentTypeInfos`**：`ClientboundCommandsPacket.createEntry` 对每个 argument 节点调用 `ArgumentTypeInfos.unpack(type)` → `byClass(type)` → `BY_CLASS.get(type.getClass())`，取不到就抛
   `IllegalArgumentException("Unrecognized argument type %s (%s)")`。
4. 真正写包时（`ClientboundCommandsPacket$ArgumentNodeStub.serializeCap`）只写 **注册表数字 id**：
   `BuiltInRegistries.COMMAND_ARGUMENT_TYPE.getId(argumentType)` → `FriendlyByteBuf.writeVarInt(int)`。取消注册的类型会得到 `-1`，客户端 `getRoot` 直接还原不出来。
5. `Commands.validate()` 这条"友好报错"**只在 IDE 里跑**：`Bootstrap.validate()` 里 `getstatic SharedConstants.IS_RUNNING_IN_IDE; ifeq →` 才 `invokestatic Commands.validate`。**生产/正式服务器不会提前警告，错误只会表现为"发命令树时抛异常 / 客户端命令树残缺"** —— 这正是 1.21.1 那次踩坑的形态。

### R7.2 javap：`EntityArgument`

```text
public class net.minecraft.commands.arguments.EntityArgument
        implements com.mojang.brigadier.arguments.ArgumentType<net.minecraft.commands.arguments.selector.EntitySelector> {
  public static final com.mojang.brigadier.exceptions.SimpleCommandExceptionType ERROR_NOT_SINGLE_ENTITY;
  public static final com.mojang.brigadier.exceptions.SimpleCommandExceptionType ERROR_NOT_SINGLE_PLAYER;
  public static final com.mojang.brigadier.exceptions.SimpleCommandExceptionType ERROR_ONLY_PLAYERS_ALLOWED;
  public static final com.mojang.brigadier.exceptions.SimpleCommandExceptionType NO_ENTITIES_FOUND;
  public static final com.mojang.brigadier.exceptions.SimpleCommandExceptionType NO_PLAYERS_FOUND;
  public static final com.mojang.brigadier.exceptions.SimpleCommandExceptionType ERROR_SELECTORS_NOT_ALLOWED;
  private final boolean single;
  private final boolean playersOnly;
  protected net.minecraft.commands.arguments.EntityArgument(boolean, boolean);
  public static net.minecraft.commands.arguments.EntityArgument entity();
  public static net.minecraft.world.entity.Entity getEntity(
      com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack>, java.lang.String)
      throws com.mojang.brigadier.exceptions.CommandSyntaxException;
  public static net.minecraft.commands.arguments.EntityArgument entities();
  public static java.util.Collection<? extends net.minecraft.world.entity.Entity> getEntities(...);
  public static java.util.Collection<? extends net.minecraft.world.entity.Entity> getOptionalEntities(...);
  public static java.util.Collection<net.minecraft.server.level.ServerPlayer> getOptionalPlayers(...);
  public static net.minecraft.commands.arguments.EntityArgument player();
  public static net.minecraft.server.level.ServerPlayer getPlayer(...);
  public static net.minecraft.commands.arguments.EntityArgument players();
  public static java.util.Collection<net.minecraft.server.level.ServerPlayer> getPlayers(...);
  public net.minecraft.commands.arguments.selector.EntitySelector parse(com.mojang.brigadier.StringReader);
  public <S> net.minecraft.commands.arguments.selector.EntitySelector parse(com.mojang.brigadier.StringReader, S);
  public <S> java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> listSuggestions(...);
  public java.util.Collection<java.lang.String> getExamples();
}
```

配套存在（`mc262-classes.txt` 实测）：`net/minecraft/commands/arguments/EntityArgument$Info.class`、`EntityArgument$Info$Template.class`。

### R7.3 javap：`ArgumentTypeInfo` / `ArgumentTypeInfo$Template`（注册一个参数类型要实现什么）

```text
public interface net.minecraft.commands.synchronization.ArgumentTypeInfo<
        A extends com.mojang.brigadier.arguments.ArgumentType<?>,
        T extends net.minecraft.commands.synchronization.ArgumentTypeInfo$Template<A>> {
  public abstract void serializeToNetwork(T, net.minecraft.network.FriendlyByteBuf);
  public abstract T deserializeFromNetwork(net.minecraft.network.FriendlyByteBuf);
  public abstract void serializeToJson(T, com.google.gson.JsonObject);
  public abstract T unpack(A);
}

public interface net.minecraft.commands.synchronization.ArgumentTypeInfo$Template<
        A extends com.mojang.brigadier.arguments.ArgumentType<?>> {
  public abstract A instantiate(net.minecraft.commands.CommandBuildContext);
  public abstract net.minecraft.commands.synchronization.ArgumentTypeInfo<A, ?> type();
}
```

对比 1.21.1：四个方法名与顺序**未变**（`serializeToNetwork` / `deserializeFromNetwork` / `serializeToJson` / `unpack`），`Template` 的 `instantiate(CommandBuildContext)` + `type()` 也未变。

### R7.4 javap：`ArgumentTypeInfos`（白名单本体）

```text
public class net.minecraft.commands.synchronization.ArgumentTypeInfos {
  private static final java.util.Map<java.lang.Class<?>,
      net.minecraft.commands.synchronization.ArgumentTypeInfo<?, ?>> BY_CLASS;
  private static <A ..., T ... > net.minecraft.commands.synchronization.ArgumentTypeInfo<A, T> register(
      net.minecraft.core.Registry<net.minecraft.commands.synchronization.ArgumentTypeInfo<?, ?>>,
      java.lang.String, java.lang.Class<? extends A>,
      net.minecraft.commands.synchronization.ArgumentTypeInfo<A, T>);          // private
  public static net.minecraft.commands.synchronization.ArgumentTypeInfo<?, ?> bootstrap(
      net.minecraft.core.Registry<net.minecraft.commands.synchronization.ArgumentTypeInfo<?, ?>>);
  public static boolean isClassRecognized(java.lang.Class<?>);
  public static <A ...> net.minecraft.commands.synchronization.ArgumentTypeInfo<A, ?> byClass(A);
  public static <A ...> net.minecraft.commands.synchronization.ArgumentTypeInfo$Template<A> unpack(A);
}
```

`isClassRecognized` / `byClass` / `unpack` 的实现（`-c` 实测）：

```text
public static boolean isClassRecognized(java.lang.Class<?>);
  0: getstatic BY_CLASS
  3: aload_0
  4: invokeinterface java/util/Map.containsKey
  → return

public static ArgumentTypeInfo byClass(ArgumentType);
  0: getstatic BY_CLASS
  3: aload_0 → ArgumentType.getClass()
  9: invokeinterface java/util/Map.get
 14: checkcast ArgumentTypeInfo
 18: ifnonnull 56
 22: new java/lang/IllegalArgumentException
 29: ldc_w "Unrecognized argument type %s (%s)"
 → throw

public static ArgumentTypeInfo$Template unpack(ArgumentType);
  1: invokestatic  byClass(ArgumentType)
  5: invokeinterface ArgumentTypeInfo.unpack(ArgumentType)
```

### R7.5 白名单内容（`bootstrap` 里 `ldc // String xxx` 实测，51 + 6 项）

`ArgumentTypeInfos.bootstrap` 用字符串常量逐项 `register`，字符串**不带命名空间前缀时默认归 `minecraft`**（brigadier 那六项显式写 `brigadier:`）。

- brigadier 六项：`brigadier:bool`、`brigadier:float`、`brigadier:double`、`brigadier:integer`、`brigadier:long`、`brigadier:string`
- minecraft 51 项：`entity`、`game_profile`、`block_pos`、`column_pos`、`vec3`、`vec2`、`block_state`、`block_predicate`、`item_stack`、`item_predicate`、`team_color`、`hex_color`、`component`、`style`、`message`、`nbt_compound_tag`、`nbt_tag`、`nbt_path`、`objective`、`objective_criteria`、`operation`、`particle`、`angle`、`rotation`、`scoreboard_slot`、`score_holder`、`swizzle`、`team`、`item_slot`、`item_slots`、`resource_location`、`function`、`entity_anchor`、`int_range`、`float_range`、`dimension`、`gamemode`、`time`、`resource_or_tag`、`resource_or_tag_key`、`resource`、`resource_key`、`resource_selector`、`template_mirror`、`template_rotation`、`heightmap`、`loot_table`、`loot_predicate`、`loot_modifier`、`dialog`、`uuid`

其中 `entity` 注册的实现类是 `EntityArgument$Info`：

```text
  84: ldc  // class com/mojang/brigadier/arguments/StringArgumentType
      ...
 100: ldc  // class net/minecraft/commands/arguments/EntityArgument
 102: new  // class net/minecraft/commands/arguments/EntityArgument$Info
 106: invokespecial EntityArgument$Info."<init>":()V
```

`EntityArgument$Info` 的序列化体（`-p` 实测）：

```text
public class net.minecraft.commands.arguments.EntityArgument$Info
    implements net.minecraft.commands.synchronization.ArgumentTypeInfo<
        net.minecraft.commands.arguments.EntityArgument,
        net.minecraft.commands.arguments.EntityArgument$Info$Template> {
  private static final byte FLAG_SINGLE;
  private static final byte FLAG_PLAYERS_ONLY;
  public void serializeToNetwork(EntityArgument$Info$Template, net.minecraft.network.FriendlyByteBuf);
  public EntityArgument$Info$Template deserializeFromNetwork(net.minecraft.network.FriendlyByteBuf);
  public void serializeToJson(EntityArgument$Info$Template, com.google.gson.JsonObject);
  public EntityArgument$Info$Template unpack(net.minecraft.commands.arguments.EntityArgument);
}
```

### R7.6 `ClientboundCommandsPacket`：序列化发生点

```text
public class net.minecraft.network.protocol.game.ClientboundCommandsPacket
        implements net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> {
  public static final net.minecraft.network.codec.StreamCodec<
      net.minecraft.network.FriendlyByteBuf,
      net.minecraft.network.protocol.game.ClientboundCommandsPacket> STREAM_CODEC;
  private static final byte MASK_TYPE = 3;               // javap -constants 实测
  private static final byte FLAG_EXECUTABLE = 4;
  private static final byte FLAG_REDIRECT = 8;
  private static final byte FLAG_CUSTOM_SUGGESTIONS = 16;
  private static final byte FLAG_RESTRICTED = 32;
  private static final byte TYPE_ROOT = 0;
  private static final byte TYPE_LITERAL = 1;
  private static final byte TYPE_ARGUMENT = 2;
  public <S> ClientboundCommandsPacket(
      com.mojang.brigadier.tree.RootCommandNode<S>,
      net.minecraft.network.protocol.game.ClientboundCommandsPacket$NodeInspector<S>);
  public <S> com.mojang.brigadier.tree.RootCommandNode<S> getRoot(
      net.minecraft.commands.CommandBuildContext,
      net.minecraft.network.protocol.game.ClientboundCommandsPacket$NodeBuilder<S>);
}

public interface net.minecraft.network.protocol.game.ClientboundCommandsPacket$NodeInspector<S> {
  public abstract net.minecraft.resources.Identifier suggestionId(
      com.mojang.brigadier.tree.ArgumentCommandNode<S, ?>);
  public abstract boolean isExecutable(com.mojang.brigadier.tree.CommandNode<S>);
  public abstract boolean isRestricted(com.mojang.brigadier.tree.CommandNode<S>);
}

final class net.minecraft.network.protocol.game.ClientboundCommandsPacket$ArgumentNodeStub extends java.lang.Record
    implements net.minecraft.network.protocol.game.ClientboundCommandsPacket$NodeStub {
  private final java.lang.String id;
  private final net.minecraft.commands.synchronization.ArgumentTypeInfo$Template<?> argumentType;
  private final net.minecraft.resources.Identifier suggestionId;   // ← 注意：Identifier 不是 ResourceLocation
  private static <A ...> void serializeCap(net.minecraft.network.FriendlyByteBuf,
      net.minecraft.commands.synchronization.ArgumentTypeInfo$Template<A>);
  private static <A ..., T ...> void serializeCap(net.minecraft.network.FriendlyByteBuf,
      net.minecraft.commands.synchronization.ArgumentTypeInfo<A, T>,
      net.minecraft.commands.synchronization.ArgumentTypeInfo$Template<A>);
}
```

关键字节码（两道闸门）：

```text
# 闸门 1：建包时把 ArgumentType 换成 Template，取不到 info 就抛 IAE
ClientboundCommandsPacket.createEntry(...):
 156: invokestatic net/minecraft/commands/synchronization/ArgumentTypeInfos.unpack:(...)
 161: invokespecial ClientboundCommandsPacket$ArgumentNodeStub."<init>":(String, Template, Identifier)

# 闸门 2：写包时只写注册表数字 id
ClientboundCommandsPacket$ArgumentNodeStub.serializeCap(buf, info, template):
   0: aload_0
   1: getstatic net/minecraft/core/registries/BuiltInRegistries.COMMAND_ARGUMENT_TYPE
   5: invokeinterface net/minecraft/core/Registry.getId:(Ljava/lang/Object;)I
  10: invokevirtual net/minecraft/network/FriendlyByteBuf.writeVarInt:(I)
```

闸门**只对 argument 节点生效**（literal / root 分支不会碰 `ArgumentTypeInfos`，`javap -c createEntry` 实测）：

```text
createEntry:
 76: invokedynamic  typeSwitch
 81: tableswitch { 0 → 108(RootCommandNode) / 1 → 125(ArgumentCommandNode) / 2 → 183(Literal) }
108: checkcast com/mojang/brigadier/tree/RootCommandNode
115: iload_3; iconst_0; ior         // flags |= TYPE_ROOT
125: checkcast com/mojang/brigadier/tree/ArgumentCommandNode
135: invokeinterface NodeInspector.suggestionId(ArgumentCommandNode)
148: invokevirtual  ArgumentCommandNode.getName()
153: invokevirtual  ArgumentCommandNode.getType()
156: invokestatic   ArgumentTypeInfos.unpack(...)      // ← 只有这里查白名单
166: iload_3; iconst_2; ior         // flags |= TYPE_ARGUMENT
```

以及"IDE 才校验"的证据：

```text
# net.minecraft.commands.Commands
private static boolean lambda$validate$1(ArgumentType);
  1: invokeinterface ArgumentType.getClass
  6: invokestatic  ArgumentTypeInfos.isClassRecognized:(Ljava/lang/Class;)Z
  9: ifne 16 / 12: iconst_1 …            // 收集"未注册"的类型

public static void validate();
  …
 81: getstatic LOGGER
 84: ldc_w "Missing type registration for following arguments:\n {}"
115: invokeinterface Logger.warn
120: new java/lang/IllegalStateException
124: ldc_w "Unregistered argument types"

# net.minecraft.server.Bootstrap.validate()
  8: getstatic net.minecraft.SharedConstants.IS_RUNNING_IN_IDE:Z
 11: ifeq 33
 …
 30: invokestatic net.minecraft.commands.Commands.validate:()V   // ← 仅 IDE
 33: invokestatic DefaultAttributes.validate:()V
```

> 全 jar 扫描（10952 个 class，ASCII 常量池匹配）确认：含 `"Missing type registration"` 的类**只有** `Commands.class` 自己；引用 `ArgumentTypeInfos` 的类只有 `Commands`、`ArgumentTypeInfos`、`ArgumentUtils`、`BuiltInRegistries`、`ClientboundCommandsPacket` 五个。

### R7.7 注册入口（mod 侧，Fabric）

```text
public final class net.fabricmc.fabric.api.command.v2.ArgumentTypeRegistry {
  public static <A extends com.mojang.brigadier.arguments.ArgumentType<?>,
                 T extends net.minecraft.commands.synchronization.ArgumentTypeInfo$Template<A>>
      void registerArgumentType(net.minecraft.resources.Identifier,
                                java.lang.Class<? extends A>,
                                net.minecraft.commands.synchronization.ArgumentTypeInfo<A, T>);
}

public interface net.fabricmc.fabric.mixin.command.ArgumentTypeInfosAccessor {
  public static java.util.Map<java.lang.Class<?>,
      net.minecraft.commands.synchronization.ArgumentTypeInfo<?, ?>> fabric_getClassMap();
}
```

`registerArgumentType` 的字节码说明它**同时**写两张表（这是 mod 自定义参数类型能过闸门的唯一正路）：

```text
0: invokestatic ArgumentTypeInfosAccessor.fabric_getClassMap   → BY_CLASS
5: invokeinterface java/util/Map.put(Class, ArgumentTypeInfo)
11: getstatic BuiltInRegistries.COMMAND_ARGUMENT_TYPE
16: invokestatic net/minecraft/core/Registry.register(Registry, Identifier, Object)
```

### R7.8 对移植的影响

- `EntityArgument.players()`、`EntityArgument.getPlayers(ctx, "targets")`、`EntityArgument.player()/getPlayer()` **一行都不用改**。
- `getPlayers` 的返回类型是 `Collection<ServerPlayer>`，沿用现有 `List.copyOf(...)` 写法即可。
- **不要再自己造 ArgumentType**（1.21.1 时期的临时类型如果还在，26.2 必崩在 `ArgumentTypeInfos.byClass`）。真要造，必须走 `ArgumentTypeRegistry.registerArgumentType(Identifier, Class, ArgumentTypeInfo)`，且 `ArgumentTypeInfo` 必须实现 4 个方法 + 内部 `Template` 2 个方法。
- **本地/联调期没有任何预检**（`Commands.validate()` 只在 `IS_RUNNING_IN_IDE` 跑），所以"单人正常、多人发树崩"这种坑不会提前暴露，只能靠真机多人验证。

---

## R7b `ResourceLocationArgument` 是否改名成 `IdentifierArgument`

### R7b.1 结论

**是，已改名，且旧名连同 `ResourceLocation` 一起彻底消失。**

- 对整个 MC jar 做条目扫描：`jar tf | Select-String 'ResourceLocation'` → **0 命中**；`Identifier` → `net/minecraft/resources/Identifier.class`、`net/minecraft/IdentifierException.class`、`net/minecraft/util/IdentifierPattern.class`、`net/minecraft/client/searchtree/IdentifierSearchTree.class` 等（名字都换了）。
- `mc262-classes.txt` 里同样 **0 处 `ResourceLocation`**。
- 命令参数类实际为 `net.minecraft.commands.arguments.IdentifierArgument`，静态工厂 `id()`，取值器 `getId(...)` 返回 `Identifier`。

### R7b.2 javap

```text
public class net.minecraft.commands.arguments.IdentifierArgument
        implements com.mojang.brigadier.arguments.ArgumentType<net.minecraft.resources.Identifier> {
  private static final java.util.Collection<java.lang.String> EXAMPLES;
  public net.minecraft.commands.arguments.IdentifierArgument();
  public static net.minecraft.commands.arguments.IdentifierArgument id();
  public static net.minecraft.resources.Identifier getId(
      com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack>, java.lang.String);
  public net.minecraft.resources.Identifier parse(com.mojang.brigadier.StringReader)
      throws com.mojang.brigadier.exceptions.CommandSyntaxException;
  public java.util.Collection<java.lang.String> getExamples();
}
```

注册名**仍是 `resource_location`**（类改名了，协议名没改）：

```text
ArgumentTypeInfos.bootstrap:
 606: ldc   // String resource_location
 608: ldc   // class net/minecraft/commands/arguments/IdentifierArgument
 610: invokedynamic   // Supplier<IdentifierArgument::new>
 615: invokestatic  SingletonArgumentInfo.contextFree(Supplier)
 618: invokestatic  ArgumentTypeInfos.register(Registry, String, Class, ArgumentTypeInfo)
```

配对工具类：

```text
public class net.minecraft.commands.synchronization.SingletonArgumentInfo<A extends ArgumentType<?>>
    implements ArgumentTypeInfo<A, SingletonArgumentInfo<A>.Template> {
  public static <T extends ArgumentType<?>> SingletonArgumentInfo<T> contextFree(java.util.function.Supplier<T>);
  public static <T extends ArgumentType<?>> SingletonArgumentInfo<T> contextAware(java.util.function.Function<CommandBuildContext, T>);
  public void serializeToNetwork(Template, FriendlyByteBuf);      // 空体：单例无参数
  ...
}
```

`Identifier` 的工厂（`ResourceLocation` 的旧名对应表）：

```text
public static net.minecraft.resources.Identifier fromNamespaceAndPath(java.lang.String, java.lang.String);
public static net.minecraft.resources.Identifier parse(java.lang.String);
public static net.minecraft.resources.Identifier withDefaultNamespace(java.lang.String);
public static net.minecraft.resources.Identifier tryParse(java.lang.String);
public static net.minecraft.resources.Identifier tryBuild(java.lang.String, java.lang.String);
public static net.minecraft.resources.Identifier bySeparator(java.lang.String, char);
public static net.minecraft.resources.Identifier read(com.mojang.brigadier.StringReader);
public static final com.mojang.serialization.Codec<net.minecraft.resources.Identifier> CODEC;
public static final net.minecraft.network.codec.StreamCodec<io.netty.buffer.ByteBuf,
        net.minecraft.resources.Identifier> STREAM_CODEC;
```

### R7b.3 对移植的影响

| 1.21.1 写法 | 26.2 写法 |
|---|---|
| `ResourceLocation` | `net.minecraft.resources.Identifier` |
| `new ResourceLocation(ns, path)` | `Identifier.fromNamespaceAndPath(ns, path)` |
| `ResourceLocation.parse(s)` | `Identifier.parse(s)` |
| `ResourceLocationArgument.id()` | `IdentifierArgument.id()` |
| `ResourceLocationArgument.getId(ctx, name)` | `IdentifierArgument.getId(ctx, name)` → 返回 `Identifier` |
| `ResourceLocation.tryParse` | `Identifier.tryParse` |

现有 26.2 分支源码 `server/SkyboxServerCommands.java` 已经是 `IdentifierArgument.id()` / `IdentifierArgument.getId(...)`（第 294、310 行），这部分**无需再动**。协议层注册名还是 `minecraft:resource_location`，所以 JSON 里的 `{"type":"minecraft:resource_location"}` 不变。

---

## R7c 服务端注册命令的现行入口

### R7c.1 结论

- Fabric 侧入口仍是 `CommandRegistrationCallback`，但**回调三参固定为 `(CommandDispatcher<CommandSourceStack>, CommandBuildContext, Commands$CommandSelection)`**；`CommandSelection` 是 `ALL / DEDICATED / INTEGRATED` 枚举。
- 注入点在 `Commands` 构造器尾部（`CommandsMixin.fabric_addCommands`），即每次 `new Commands(...)`（含数据包 reload 重建 dispatcher）都会重新触发回调。
- Vanilla 构树 API 仍是 `Commands.literal(String)` 与 `Commands.argument(String, ArgumentType<T>)`。

### R7c.2 javap

```text
public interface net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback {
  public static final net.fabricmc.fabric.api.event.Event<CommandRegistrationCallback> EVENT;
  public abstract void register(
      com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>,
      net.minecraft.commands.CommandBuildContext,
      net.minecraft.commands.Commands$CommandSelection);
}

public final class net.minecraft.commands.Commands$CommandSelection
        extends java.lang.Enum<net.minecraft.commands.Commands$CommandSelection> {
  public static final net.minecraft.commands.Commands$CommandSelection ALL;
  public static final net.minecraft.commands.Commands$CommandSelection DEDICATED;
  public static final net.minecraft.commands.Commands$CommandSelection INTEGRATED;
  public final boolean includeIntegrated;
  public final boolean includeDedicated;
}
```

```text
public class net.minecraft.commands.Commands {
  public static final PermissionCheck LEVEL_ALL;
  public static final PermissionCheck LEVEL_MODERATORS;
  public static final PermissionCheck LEVEL_GAMEMASTERS;
  public static final PermissionCheck LEVEL_ADMINS;
  public static final PermissionCheck LEVEL_OWNERS;
  public Commands(Commands$CommandSelection, CommandBuildContext);
  public static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> literal(java.lang.String);
  public static <T> com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, T> argument(
      java.lang.String, com.mojang.brigadier.arguments.ArgumentType<T>);
  public void sendCommands(net.minecraft.server.level.ServerPlayer);     // ← 就是这里发命令树
  public static net.minecraft.commands.CommandBuildContext createValidationContext(HolderLookup$Provider);
  public static void validate();
  public static <T extends PermissionSetSupplier> PermissionProviderCheck<T> hasPermission(PermissionCheck);
  public static CommandSourceStack createCompilationContext(PermissionSet);
  public CommandDispatcher<CommandSourceStack> getDispatcher();
}
```

Fabric 的注入（字节码）：

```text
public abstract class net.fabricmc.fabric.mixin.command.CommandsMixin {
  private com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher;
  private void fabric_addCommands(Commands$CommandSelection, CommandBuildContext, CallbackInfo);
    getstatic  CommandRegistrationCallback.EVENT
    invokevirtual Event.invoker()
    invokeinterface CommandRegistrationCallback.register(CommandDispatcher, CommandBuildContext, CommandSelection)
}
```

### R7c.3 对移植的影响

- 现有 `SkyboxServerCommands` 第 73 行的 lambda `(dispatcher, registryAccess, environment) -> buildTree(dispatcher)` **签名兼容**（第二个参数现在类型是 `CommandBuildContext`，只是变量名可改）。
- **权限模型变了**：`Commands.LEVEL_*` 现在是 `net.minecraft.server.permissions.PermissionCheck`（不再是整数等级），`CommandSourceStack.hasPermission(int)` 已被删除。现有 `canMutate` 用的是 `source.hasPermission(MUTATION_LEVEL)`，26.2 编译不过 —— 详细证据与替换写法见 **R7c.4**。
- 因为回调在 `Commands` 构造器里跑，**重复注册要幂等**（现有 `buildTree` 每次 `dispatcher.register` 同一棵字面树，Brigadier 会合并同名节点；但仍建议加 `registered` 闸门）。

### R7c.4 顺带核对：`CommandSourceStack.hasPermission(int)` 在 26.2 已不存在（会直接编译失败）

`javap CommandSourceStack` 全文里**没有** `hasPermission(int)`（也没有 `hasPermission(PermissionCheck)`）；权限改为对象模型：

```text
public class net.minecraft.commands.CommandSourceStack
    implements net.minecraft.commands.SharedSuggestionProvider,
               net.minecraft.commands.ExecutionCommandSource<CommandSourceStack>,
               net.fabricmc.fabric.api.permission.v1.PermissionContextOwner {
  private final net.minecraft.server.permissions.PermissionSet permissions;   // ← 不再是 int 等级
  public net.minecraft.server.permissions.PermissionSet permissions();
  public net.minecraft.commands.CommandSourceStack withPermission(net.minecraft.server.permissions.PermissionSet);
  public net.minecraft.commands.CommandSourceStack withMaximumPermission(net.minecraft.server.permissions.PermissionSet);
}

public interface net.minecraft.server.permissions.PermissionSet {
  public static final net.minecraft.server.permissions.PermissionSet NO_PERMISSIONS;
  public static final net.minecraft.server.permissions.PermissionSet ALL_PERMISSIONS;
  public abstract boolean hasPermission(net.minecraft.server.permissions.Permission);
}

public interface net.minecraft.server.permissions.PermissionCheck {
  public abstract boolean check(net.minecraft.server.permissions.PermissionSet);
  public abstract com.mojang.serialization.MapCodec<? extends PermissionCheck> codec();
}

public final class net.minecraft.server.permissions.PermissionProviderCheck<T extends PermissionSetSupplier>
    extends java.lang.Record implements java.util.function.Predicate<T> { … }

public interface net.minecraft.server.permissions.PermissionSetSupplier {
  public abstract net.minecraft.server.permissions.PermissionSet permissions();
}
```

vanilla 自己的写法（`net.minecraft.server.commands.TimeCommand` 字节码实测）：

```text
  5: getstatic      net/minecraft/commands/Commands.LEVEL_GAMEMASTERS:Lnet/minecraft/server/permissions/PermissionCheck;
  8: invokestatic   net/minecraft/commands/Commands.hasPermission:(PermissionCheck)PermissionProviderCheck
 11: invokevirtual  com/mojang/brigadier/builder/LiteralArgumentBuilder.requires:(Ljava/util/function/Predicate;)
```

即：`Commands.literal("x").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))`。

**对本项目**：`server/SkyboxServerCommands.java` 第 285 行 `return source.hasPermission(MUTATION_LEVEL);`（`hasPermission` 出现在 100/252/255/261/266/269/274/277/284/347/353/359/362 的 `.requires(...)` 链上）在 26.2 **编译不过**，需要改成
`private static boolean canMutate(CommandSourceStack source) { return Commands.LEVEL_GAMEMASTERS.check(source.permissions()); }`
或直接把 `.requires(SkyboxServerCommands::canMutate)` 换成 `.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))`。

---

## R11 自定义包

### R11.1 结论

1. `net.minecraft.network.protocol.common.custom.CustomPacketPayload`、其内部 `$Type`、`$TypeAndCodec` **全部保留**，`Type` 仍是 `record Type(Identifier id)`（字段类型从 `ResourceLocation` 换成 `Identifier`）。
2. **Fabric 侧的静态工厂改名了**：`PayloadTypeRegistry` **没有** `playS2C()` / `playC2S()` / `configurationS2C()` / `configurationC2S()`。26.2 是：
   - `serverboundConfiguration()`
   - `clientboundConfiguration()`
   - `serverboundPlay()` → `PayloadTypeRegistry<RegistryFriendlyByteBuf>`
   - `clientboundPlay()` → `PayloadTypeRegistry<RegistryFriendlyByteBuf>`
3. `ServerPlayNetworking.send(ServerPlayer, CustomPacketPayload)`、`ServerPlayNetworking.canSend(ServerPlayer, Type)`、`ClientPlayNetworking.registerGlobalReceiver(Type<T>, PlayPayloadHandler<T>)` 的**形状与 1.21.1 一致**（handler 是 `receive(T, Context)`）。
4. 26.2 新增了 `registerLarge(Type, StreamCodec, int|IntSupplier)`：Fabric 内部 `PayloadTypeRegistryImpl` 有 `minimalSplittableSize` / `getMaxPacketSizeForSplitting(Identifier)` / `pendingMaxPacketSizes`，说明大 payload 有"分片阈值"这套机制（**具体默认阈值数值查不到** —— 只做了字节码层取证，未反编译源码）。
5. 未注册的 type 在客户端不会炸：vanilla 有 `DiscardedPayload` + `CustomPacketPayload$FallbackProvider` 兜底。

### R11.2 javap：`CustomPacketPayload`

```text
public interface net.minecraft.network.protocol.common.custom.CustomPacketPayload {
  public abstract net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type<
      ? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type();

  public static <B extends io.netty.buffer.ByteBuf,
                 T extends net.minecraft.network.protocol.common.custom.CustomPacketPayload>
      net.minecraft.network.codec.StreamCodec<B, T> codec(
          net.minecraft.network.codec.StreamMemberEncoder<B, T>,
          net.minecraft.network.codec.StreamDecoder<B, T>);

  public static <T extends net.minecraft.network.protocol.common.custom.CustomPacketPayload>
      net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type<T> createType(java.lang.String);

  public static <B extends net.minecraft.network.FriendlyByteBuf>
      net.minecraft.network.codec.StreamCodec<B, net.minecraft.network.protocol.common.custom.CustomPacketPayload> codec(
          net.minecraft.network.protocol.common.custom.CustomPacketPayload$FallbackProvider<B>,
          java.util.List<net.minecraft.network.protocol.common.custom.CustomPacketPayload$TypeAndCodec<? super B, ?>>);
}

public final class net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type<
        T extends net.minecraft.network.protocol.common.custom.CustomPacketPayload>
    extends java.lang.Record {
  private final net.minecraft.resources.Identifier id;
  public net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type(
      net.minecraft.resources.Identifier);
  public net.minecraft.resources.Identifier id();
}

public final class net.minecraft.network.protocol.common.custom.CustomPacketPayload$TypeAndCodec<
        B extends net.minecraft.network.FriendlyByteBuf,
        T extends net.minecraft.network.protocol.common.custom.CustomPacketPayload>
    extends java.lang.Record {
  private final net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type<T> type;
  private final net.minecraft.network.codec.StreamCodec<B, T> codec;
  public net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type<T> type();
  public net.minecraft.network.codec.StreamCodec<B, T> codec();
}

public final class net.minecraft.network.protocol.common.custom.DiscardedPayload extends java.lang.Record
    implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {
  public static <T extends net.minecraft.network.FriendlyByteBuf>
      net.minecraft.network.codec.StreamCodec<T, DiscardedPayload> codec(
          net.minecraft.resources.Identifier, int);
  public net.minecraft.resources.Identifier id();
}

public interface net.minecraft.network.protocol.common.custom.CustomPacketPayload$FallbackProvider<
        B extends net.minecraft.network.FriendlyByteBuf> {
  public abstract net.minecraft.network.codec.StreamCodec<B, ? extends CustomPacketPayload> create(
      net.minecraft.resources.Identifier);
}
```

### R11.3 javap：Fabric `PayloadTypeRegistry`（**改名点**）

```text
public interface net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry<
        B extends net.minecraft.network.FriendlyByteBuf> {
  public abstract <T extends CustomPacketPayload> CustomPacketPayload$TypeAndCodec<? super B, T> register(
      CustomPacketPayload$Type<T>, net.minecraft.network.codec.StreamCodec<? super B, T>);

  public abstract <T extends CustomPacketPayload> CustomPacketPayload$TypeAndCodec<? super B, T> registerLarge(
      CustomPacketPayload$Type<T>, net.minecraft.network.codec.StreamCodec<? super B, T>, int);

  public abstract <T extends CustomPacketPayload> CustomPacketPayload$TypeAndCodec<? super B, T> registerLarge(
      CustomPacketPayload$Type<T>, net.minecraft.network.codec.StreamCodec<? super B, T>,
      java.util.function.IntSupplier);

  public static PayloadTypeRegistry<net.minecraft.network.FriendlyByteBuf> serverboundConfiguration();
  public static PayloadTypeRegistry<net.minecraft.network.FriendlyByteBuf> clientboundConfiguration();
  public static PayloadTypeRegistry<net.minecraft.network.RegistryFriendlyByteBuf> serverboundPlay();
  public static PayloadTypeRegistry<net.minecraft.network.RegistryFriendlyByteBuf> clientboundPlay();
}
```

实现类（说明四个实例就是"协议阶段 × 方向"）：

```text
public class net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl<B extends FriendlyByteBuf>
    implements net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry<B> {
  public static final PayloadTypeRegistryImpl<FriendlyByteBuf> SERVERBOUND_CONFIGURATION;
  public static final PayloadTypeRegistryImpl<FriendlyByteBuf> CLIENTBOUND_CONFIGURATION;
  public static final PayloadTypeRegistryImpl<RegistryFriendlyByteBuf> SERVERBOUND_PLAY;
  public static final PayloadTypeRegistryImpl<RegistryFriendlyByteBuf> CLIENTBOUND_PLAY;
  public <T ...> TypeAndCodec<? super B, T> register(Type<T>, StreamCodec<? super B, T>);
  public <T ...> TypeAndCodec<? super B, T> registerLarge(Type<T>, StreamCodec<? super B, T>, int);
  public <T ...> TypeAndCodec<? super B, T> registerLarge(Type<T>, StreamCodec<? super B, T>, IntSupplier);
  public int getMaxPacketSizeForSplitting(net.minecraft.resources.Identifier);
  public ConnectionProtocol getProtocol();
  public PacketFlow getFlow();
  // static{} 里 new 出四个实例：
  //   CONFIGURATION + SERVERBOUND / CLIENTBOUND, PLAY + SERVERBOUND / CLIENTBOUND
}
```

### R11.4 javap：`ServerPlayNetworking` / `ClientPlayNetworking`

```text
public final class net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking {
  public static <T extends CustomPacketPayload> boolean registerGlobalReceiver(
      CustomPacketPayload$Type<T>, ServerPlayNetworking$PlayPayloadHandler<T>);
  public static ServerPlayNetworking$PlayPayloadHandler<?> unregisterGlobalReceiver(Identifier);
  public static java.util.Set<Identifier> getGlobalReceivers();
  public static <T extends CustomPacketPayload> boolean registerReceiver(
      net.minecraft.server.network.ServerGamePacketListenerImpl,
      CustomPacketPayload$Type<T>, ServerPlayNetworking$PlayPayloadHandler<T>);
  public static boolean canSend(net.minecraft.server.level.ServerPlayer, Identifier);
  public static boolean canSend(net.minecraft.server.level.ServerPlayer, CustomPacketPayload$Type<?>);
  public static <T extends CustomPacketPayload>
      net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.common.ClientCommonPacketListener>
      createClientboundPacket(T);
  public static net.fabricmc.fabric.api.networking.v1.PacketSender getSender(
      net.minecraft.server.level.ServerPlayer);
  public static void send(net.minecraft.server.level.ServerPlayer, CustomPacketPayload);
  public static void reconfigure(net.minecraft.server.level.ServerPlayer);
}
public interface net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking$PlayPayloadHandler<
        T extends CustomPacketPayload> {
  public abstract void receive(T, ServerPlayNetworking$Context);
}
public interface net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking$Context {
  public abstract net.minecraft.server.MinecraftServer server();
  public abstract net.minecraft.server.level.ServerPlayer player();
  public abstract net.fabricmc.fabric.api.networking.v1.PacketSender responseSender();
  public default net.fabricmc.fabric.api.networking.v1.context.PacketContext packetContext();
}
```

```text
public final class net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking {
  public static <T extends CustomPacketPayload> boolean registerGlobalReceiver(
      CustomPacketPayload$Type<T>, ClientPlayNetworking$PlayPayloadHandler<T>);
  public static ClientPlayNetworking$PlayPayloadHandler<?> unregisterGlobalReceiver(Identifier);
  public static java.util.Set<Identifier> getReceived();          // throws IllegalStateException
  public static java.util.Set<Identifier> getSendable();
  public static boolean canSend(Identifier);                      // throws IllegalArgumentException
  public static boolean canSend(CustomPacketPayload$Type<?>);
  public static net.fabricmc.fabric.api.networking.v1.PacketSender getSender();
  public static void send(CustomPacketPayload);
}
public interface net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking$PlayPayloadHandler<
        T extends CustomPacketPayload> {
  public abstract void receive(T, ClientPlayNetworking$Context);
}
public interface net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking$Context {
  public abstract net.minecraft.client.Minecraft client();
  public abstract net.minecraft.client.player.LocalPlayer player();
  public abstract net.fabricmc.fabric.api.networking.v1.PacketSender responseSender();
}
```

配置阶段（若要把配置提前到 configuration phase 下发）：

```text
public final class net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking {
  public static <T extends CustomPacketPayload> boolean registerGlobalReceiver(
      CustomPacketPayload$Type<T>, ServerConfigurationNetworking$ConfigurationPacketHandler<T>);
  public static boolean canSend(net.minecraft.server.network.ServerConfigurationPacketListenerImpl,
      CustomPacketPayload$Type<?>);
  public static void send(net.minecraft.server.network.ServerConfigurationPacketListenerImpl,
      CustomPacketPayload);
  public static net.fabricmc.fabric.api.networking.v1.PacketSender getSender(
      net.minecraft.server.network.ServerConfigurationPacketListenerImpl);
}
```

```text
public interface net.fabricmc.fabric.api.networking.v1.PacketSender {
  public abstract net.minecraft.network.protocol.Packet<?> createPacket(CustomPacketPayload);
  public default void sendPacket(net.minecraft.network.protocol.Packet<?>);
  public default void sendPacket(CustomPacketPayload);
  public abstract void sendPacket(net.minecraft.network.protocol.Packet<?>, io.netty.channel.ChannelFutureListener);
  public default void sendPacket(CustomPacketPayload, io.netty.channel.ChannelFutureListener);
  public abstract void disconnect(net.minecraft.network.chat.Component);
}
```

### R11.5 对移植的影响（现成代码要改的地方）

现有 `network/SkyboxNetworking.java` 第 23 行：

```java
PayloadTypeRegistry.playS2C().register(SkyboxConfigPayload.TYPE, SkyboxConfigPayload.CODEC);   // 26.2 编译失败
```

改成：

```java
PayloadTypeRegistry.clientboundPlay().register(SkyboxConfigPayload.TYPE, SkyboxConfigPayload.CODEC);
```

其余保持：

- `new Type<>(Identifier.fromNamespaceAndPath("skyboxblock", "settings_v1"))` — 合法（`Type` 的字段就是 `Identifier`）。
- `ServerPlayNetworking.canSend(player, TYPE)` / `ServerPlayNetworking.send(player, payload)` — 签名不变。
- 客户端 `ClientPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) -> …)` — 签名不变。
- **`playC2S()` 已不存在**；现有注释"Deliberately NO playC2S registration"如要真正注册，应改成 `PayloadTypeRegistry.serverboundPlay()`。
- 32 KiB 的文本快照若担心超限，可换 `registerLarge(TYPE, CODEC, 32768)`（`int` 版本就够）；**默认阈值具体数字查不到**，需要实测或读 Fabric 源码。

---

## R11b `StreamCodec` 还在不在

### R11b.1 结论

**还在，而且方法名一个没改**（`of` / `ofMember` / `unit` / `apply` / `map` / `mapStream` / `dispatch` / `composite(1..12)` / `recursive` / `cast`）。`StreamEncoder.encode(B, V)` 与 `StreamDecoder.decode(B)` 也仍是这两个名字 —— 所以**匿名 `new StreamCodec<>() { decode / encode }` 的写法照旧可用**。

### R11b.2 javap（关键项）

```text
public interface net.minecraft.network.codec.StreamCodec<B, V>
        extends net.minecraft.network.codec.StreamEncoder<B, V>,
                net.minecraft.network.codec.StreamDecoder<B, V> {
  public static <B, V> StreamCodec<B, V> of(StreamEncoder<B, V>, StreamDecoder<B, V>);
  public static <B, V> StreamCodec<B, V> ofMember(StreamMemberEncoder<B, V>, StreamDecoder<B, V>);
  public static <B, V> StreamCodec<B, V> unit(V);
  public default <O> StreamCodec<B, O> apply(StreamCodec$CodecOperation<B, V, O>);
  public default <O> StreamCodec<B, O> map(java.util.function.Function<? super V, ? extends O>,
                                           java.util.function.Function<? super O, ? extends V>);
  public default <O extends io.netty.buffer.ByteBuf> StreamCodec<O, V> mapStream(
      java.util.function.Function<O, ? extends B>);
  public default <U> StreamCodec<B, U> dispatch(
      java.util.function.Function<? super U, ? extends V>,
      java.util.function.Function<? super V, ? extends StreamCodec<? super B, ? extends U>>);
  public static <B, C, T1> StreamCodec<B, C> composite(
      StreamCodec<? super B, T1>, java.util.function.Function<C, T1>,
      java.util.function.Function<T1, C>);
  public static <B, C, T1, T2> StreamCodec<B, C> composite(
      StreamCodec<? super B, T1>, java.util.function.Function<C, T1>,
      StreamCodec<? super B, T2>, java.util.function.Function<C, T2>,
      java.util.function.BiFunction<T1, T2, C>);
  // … T3..T12 同形（最后一个参数是 datafixerupper 的 FunctionN）
  public static <B, T> StreamCodec<B, T> recursive(
      java.util.function.UnaryOperator<StreamCodec<B, T>>);
  public default <S extends B> StreamCodec<S, V> cast();
}

public interface net.minecraft.network.codec.StreamEncoder<O, T> { public abstract void encode(O, T); }
public interface net.minecraft.network.codec.StreamDecoder<I, T> { public abstract T decode(I); }
public interface net.minecraft.network.codec.StreamMemberEncoder<O, T> { public abstract void encode(T, O); }
public interface net.minecraft.network.codec.StreamCodec$CodecOperation<B, S, T> {
  public abstract StreamCodec<B, T> apply(StreamCodec<B, S>);
}
```

`ByteBufCodecs` 里常用件（用于手写 composite）：

```text
BOOL       StreamCodec<ByteBuf, Boolean>
BYTE       StreamCodec<ByteBuf, Byte>
SHORT      StreamCodec<ByteBuf, Short>
INT        StreamCodec<ByteBuf, Integer>
VAR_INT    StreamCodec<ByteBuf, Integer>
LONG / VAR_LONG / FLOAT / DOUBLE
STRING_UTF8 StreamCodec<ByteBuf, String>
COMPOUND_TAG / TAG / TRUSTED_COMPOUND_TAG
VECTOR3F / QUATERNIONF / RGB_COLOR
static <B, V> StreamCodec<B, Optional<V>> optional(StreamCodec<? super B, V>);
static <B, V, C extends Collection<V>> StreamCodec<B, C> collection(IntFunction<C>, StreamCodec<? super B, V>[, int]);
static <B, V> CodecOperation<B, V, java.util.List<V>> list([int]);
static <B, K, V, M extends Map<K,V>> StreamCodec<B, M> map(IntFunction<M>, StreamCodec<? super B, K>,
                                                           StreamCodec<? super B, V>[, int]);
static <T> StreamCodec<RegistryFriendlyByteBuf, T> registry(ResourceKey<? extends Registry<T>>);
static <B extends ByteBuf, V, C extends Collection<V>> … collection(IntFunction<C>);
```

`FriendlyByteBuf` 的字符串/ID 原语也都在：

```text
public java.lang.String readUtf();
public java.lang.String readUtf(int);
public net.minecraft.network.FriendlyByteBuf writeUtf(java.lang.String);
public net.minecraft.network.FriendlyByteBuf writeUtf(java.lang.String, int);
public net.minecraft.resources.Identifier readIdentifier();
public net.minecraft.network.FriendlyByteBuf writeIdentifier(net.minecraft.resources.Identifier);
public net.minecraft.network.FriendlyByteBuf writeVarInt(int);
public net.minecraft.network.FriendlyByteBuf writeVarIntArray(int[]);
```

`RegistryFriendlyByteBuf` 仍是 `FriendlyByteBuf` 子类：

```text
public class net.minecraft.network.RegistryFriendlyByteBuf extends net.minecraft.network.FriendlyByteBuf {
  public net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.ByteBuf, net.minecraft.core.RegistryAccess);
  public net.minecraft.core.RegistryAccess registryAccess();
  public static java.util.function.Function<io.netty.buffer.ByteBuf, RegistryFriendlyByteBuf> decorator(
      net.minecraft.core.RegistryAccess);
}
```

### R11b.3 对移植的影响

现有 `SkyboxConfigPayload.CODEC`（匿名 `StreamCodec<RegistryFriendlyByteBuf, SkyboxConfigPayload>` + `readUtf(int)`/`writeUtf(String,int)`）**可以一字不改编译通过**。可选的现代化写法（不是必须）：

```java
public static final StreamCodec<RegistryFriendlyByteBuf, SkyboxConfigPayload> CODEC =
        StreamCodec.of(
            (buf, payload) -> buf.writeUtf(encodeSettings(payload.settings()), MAX_PAYLOAD_CHARS),
            buf -> new SkyboxConfigPayload(decodeSettings(buf.readUtf(MAX_PAYLOAD_CHARS))));
```

注意 `StreamCodec.of` 的第一个参数是 `StreamEncoder`（`encode(B, V)`，正是上面 λ 的顺序），别和 `ofMember`（`encode(V, B)`）搞反。

---

## 移植结论

### 1. `/skyboxblock player <targets> <attr> [value]` 带 `EntityArgument` 的命令，26.2 多人环境还能不能原样保留？

**能，原样保留。** 依据逐项对齐 26.2 的序列化白名单：

| 命令树元素 | 26.2 类型 | 是否在 `ArgumentTypeInfos` 白名单 | 证据 |
|---|---|---|---|
| `SkyboxServerCommands.literal("skyboxblock"/"player"/"set"/"show"/"reset"/"time"/"all")` | Brigadier `LiteralArgumentBuilder` | literal 不需要注册（`TYPE_LITERAL` 分支不查 `ArgumentTypeInfos`） | `ClientboundCommandsPacket$LiteralNodeStub` |
| `Commands.argument("targets", EntityArgument.players())` | `net.minecraft.commands.arguments.EntityArgument` | ✅ 注册名 `minecraft:entity`，Info = `EntityArgument$Info` | `bootstrap` ldc `entity` + `EntityArgument$Info` |
| `Commands.argument(ATTRIBUTE, IdentifierArgument.id())` | `net.minecraft.commands.arguments.IdentifierArgument` | ✅ 注册名 `minecraft:resource_location` | `bootstrap` offset 606–618 |
| `Commands.argument(VALUE, StringArgumentType.greedyString())` / `.word()` | `com.mojang.brigadier.arguments.StringArgumentType` | ✅ 注册名 `brigadier:string` | `bootstrap` offset 82 |
| `EntityArgument.getPlayers(ctx, "targets")` | 取值器，与协议无关 | — | `javap EntityArgument` |

保留的同时必须守住三条：

1. **只允许「literal + 上述 51+6 种参数类型」**。任何自定义 `ArgumentType` 都会在 `ClientboundCommandsPacket.createEntry → ArgumentTypeInfos.unpack → byClass` 抛 `IllegalArgumentException: Unrecognized argument type …`，**而且只在真正给玩家发命令树（`Commands.sendCommands`）时才炸**——单人 IDE 里 `Commands.validate()` 会提前告诉你，正式服不会。
2. **自定义类型必须注册**：`ArgumentTypeRegistry.registerArgumentType(Identifier, Class, ArgumentTypeInfo)`，并且 `ArgumentTypeInfo` 要满足 26.2 的 4 方法接口 + 2 方法 `Template`。本项目现有树**一个自定义类型都没有**，所以这条目前用不上。
3. `Commands` 侧权限从整数等级换成了 `PermissionCheck`（`LEVEL_GAMEMASTERS` 等），`requires(...)` 的谓词要按新 API 重写；这跟序列化无关，但同一个命令树绕不开。

### 2. 配置下发要不要改 codec 写法？

**codec 写法不用改；注册入口必须改一行。**

- `StreamCodec` / `StreamEncoder.encode` / `StreamDecoder.decode` 名字全都没变 → `SkyboxConfigPayload.CODEC` 的匿名类实现**原样编译**。
- `CustomPacketPayload.Type` / `TypeAndCodec` / `CustomPacketPayload.codec(...)` 结构未变（只是 `Identifier` 换了 `ResourceLocation`）。
- **必须改**：`PayloadTypeRegistry.playS2C()` → `PayloadTypeRegistry.clientboundPlay()`（`playC2S()` → `serverboundPlay()`；configuration 阶段是 `clientboundConfiguration()` / `serverboundConfiguration()`）。
- 不必改：`ServerPlayNetworking.send / canSend`、`ClientPlayNetworking.registerGlobalReceiver`、`ServerPlayConnectionEvents.JOIN` 的用法。
- 建议核对：32 KiB 文本快照若撞上 Fabric 的分片阈值，用 `registerLarge(TYPE, CODEC, 32768)`；默认阈值**本次查不到具体数值**（只做了字节码层取证）。

### 3. 落地改动清单

| 文件 | 行 | 现在 | 26.2 应为 |
|---|---|---|---|
| `src/main/java/dev/yg/skyboxblock/network/SkyboxNetworking.java` | 23 | `PayloadTypeRegistry.playS2C()` | `PayloadTypeRegistry.clientboundPlay()` |
| `src/main/java/dev/yg/skyboxblock/network/SkyboxConfigPayload.java` | 24–32 | `StreamCodec` 匿名类 + `Identifier` | **不变**（已符合 26.2） |
| `src/main/java/dev/yg/skyboxblock/server/SkyboxServerCommands.java` | 101/115/294/310 | `EntityArgument.players()`、`StringArgumentType.greedyString()`、`IdentifierArgument.id()` | **不变**（均在白名单内） |
| `src/main/java/dev/yg/skyboxblock/server/SkyboxServerCommands.java` | 284–285（被 100/252/255/261/266/269/274/277/347/353/359/362 的 `.requires` 引用） | `source.hasPermission(MUTATION_LEVEL)` | `source.permissions().hasPermission(...)` / `Commands.LEVEL_GAMEMASTERS.check(source.permissions())`，或整处换成 `.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))` |

### 4. 本次没查到的（明确标注）

- `PayloadTypeRegistry` 的**注册时机约束**（是否必须在 mod 初始化阶段注册、注册后是否冻结）：字节码层未见 `frozen` 之类守卫，具体语义查不到。
- Fabric 自定义 payload 的**默认最大尺寸 / 分片阈值具体数值**：只确认存在 `minimalSplittableSize`、`getMaxPacketSizeForSplitting(Identifier)`、`registerLarge(...)`；数值未从常量池提取，查不到。
- `ServerPlayNetworking` 的 handler 在哪个线程执行：属于 mixin 实现细节，本次未展开，查不到。
