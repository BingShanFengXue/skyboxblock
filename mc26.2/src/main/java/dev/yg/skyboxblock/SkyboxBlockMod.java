package dev.yg.skyboxblock;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shared registration for the independent vanilla, solid and panoramic sky windows.
 *
 * <p>26.2 的三处强制变化（javap 实测）：
 * <ul>
 *   <li>{@code BlockEntityType.Builder} 已删除 → 用公开构造器 {@code (BlockEntitySupplier, Set<Block>)}；</li>
 *   <li>{@code Item}/{@code Block} 的 {@code Properties} 必须先 {@code setId(ResourceKey)}，否则构造期抛
 *       {@code itemIdOrThrow()}；</li>
 *   <li>{@code fabric-api itemgroup.v1.FabricItemGroup} 已删除 → {@code creativetab.v1.FabricCreativeModeTab}。</li>
 * </ul>
 */
public final class SkyboxBlockMod implements ModInitializer {
    public static final String MOD_ID = "skyboxblock";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static SkyBlock SKY_BLOCK;
    public static final Map<SkyStyle, SkyBlock> SKY_BLOCKS = new EnumMap<>(SkyStyle.class);
    public static BlockEntityType<SkyBlockEntity> SKY_BLOCK_ENTITY;
    public static CreativeModeTab SKY_TAB;
    /** Blocks registered from the custom folder; kept apart so the built-in members stay invariant. */
    public static final java.util.List<SkyBlock> CUSTOM_BLOCKS = new java.util.ArrayList<>();
    public static final java.util.List<Item> CUSTOM_CREATIVE = new java.util.ArrayList<>();

    public static Identifier id(String path) { return Identifier.fromNamespaceAndPath(MOD_ID, path); }

    /** 26.2：方块属性必须带 id，否则 {@code Blocks} 之外的地方注册时会炸。 */
    private static BlockBehaviour.Properties blockProperties(Identifier blockId) {
        return BlockPropertyCompat.viewBlocking(BlockBehaviour.Properties.of()
                .setId(ResourceKey.create(Registries.BLOCK, blockId))
                .mapColor(MapColor.COLOR_LIGHT_BLUE).strength(.3F)
                .sound(SoundType.GLASS).noOcclusion()
                .isSuffocating((state, level, pos) -> false));
    }

    /**
     * 26.2：物品属性同样必须带 id。
     *
     * <p>⚠ 26.2 起 {@code Item.Properties} 构造器把 {@code descriptionId} 预置成
     * {@code ITEM_DESCRIPTION_ID}（{@code item.<ns>.<path>}），而 1.21.1 的 {@code BlockItem}
     * 是覆写 {@code getDescriptionId()} 让物品借用方块的键（{@code block.<ns>.<path>}）。
     * 26.2/26.3 的 {@code BlockItem} 已删掉这个覆写（javap 实测：类里只剩 block 字段与放置逻辑），
     * 名字改由 {@code Item} 构造器写进 {@code DataComponents.ITEM_NAME} 的
     * {@code Component.translatable(descriptionId)} 决定 ——
     * 所以不显式调 {@code useBlockDescriptionPrefix()} 时，物品会去查一个不存在的
     * {@code item.skyboxblock.*} 键，表现为创造栏/快捷栏显示原始键名或空白。
     *
     * <p>本模组的本地化资源只提供 {@code block.*} 键（与 1.21.1 完全一致），
     * 因此这里必须把前缀拨回 {@code block}，而不是再去补一批重复的 {@code item.*} 键。
     */
    private static Item.Properties itemProperties(Identifier itemId) {
        return new Item.Properties()
                .setId(ResourceKey.create(Registries.ITEM, itemId))
                .useBlockDescriptionPrefix();
    }

    /**
     * 自定义天空只注册一次、且发生在内置方块之后，所以它的名字必须自己算好。
     *
     * <p>26.2 的 {@code DataComponentInitializers.add} 只是往 List 里追加，
     * {@code runInitializers} 用 {@code computeIfAbsent} 取 Builder —— 也就是**后加的初始化器先跑、
     * 先加的覆盖后加的**。内置方块那一批用的是透明的 {@code block.skyboxblock.*} 键，
     * 若这里返回 {@code translatable(...)}，自定义方块会顶着一个不存在的键名显示。
     * 因此自定义样式沿用配置里已经解析好的显示名（{@code Component.literal}，与既有实现一致）。
     */
    private static Component customDisplayName(String displayName) {
        return Component.literal(displayName);
    }

    @Override
    public void onInitialize() {
        for (SkyStyle style : SkyStyle.values()) {
            Identifier blockId = id(style.blockId());
            SkyBlock block = Registry.register(BuiltInRegistries.BLOCK, blockId,
                    new SkyBlock(style, blockProperties(blockId)));
            SKY_BLOCKS.put(style, block);
            Registry.register(BuiltInRegistries.ITEM, blockId,
                    new BlockItem(block, itemProperties(blockId)));
        }
        // Custom styles must exist before the registry freezes, so the folder is read here, not on reload.
        dev.yg.skyboxblock.custom.CustomSkyRegistry.load(
                net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir());
        for (var custom : dev.yg.skyboxblock.custom.CustomSkyRegistry.styles()) {
            var blockId = Identifier.parse(custom.id());
            SkyBlock block = Registry.register(BuiltInRegistries.BLOCK, blockId, new SkyBlock(
                    dev.yg.skyboxblock.SkySkin.custom(custom.id(), custom.panorama()),
                    blockProperties(blockId)));
            CUSTOM_BLOCKS.add(block);
            Item item = new BlockItem(block, itemProperties(blockId)) {
                @Override public net.minecraft.network.chat.Component getName(ItemStack stack) {
                    return customDisplayName(custom.displayName());
                }
            };
            Registry.register(BuiltInRegistries.ITEM, blockId, item);
            if (custom.creative()) CUSTOM_CREATIVE.add(item);
        }
        SKY_BLOCK = SKY_BLOCKS.get(SkyStyle.VANILLA);
        // Keep the old BE id: existing vanilla blocks/worlds retain their identity.
        Set<SkyBlock> allBlocks = new LinkedHashSet<>(SKY_BLOCKS.values());
        allBlocks.addAll(CUSTOM_BLOCKS);
        // 26.1 的 BlockEntityType 构造器是 private，26.2 才公开；Fabric 的 builder 两版都在，统一走它。
        SKY_BLOCK_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("sky_block"),
                net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder
                        .create(SkyBlockEntity::new, allBlocks.toArray(SkyBlock[]::new)).build());
        SKY_TAB = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id("sky"), FabricCreativeModeTab.builder()
                .title(Component.translatable("itemGroup.skyboxblock.sky"))
                .icon(() -> new ItemStack(SKY_BLOCK))
                .displayItems((parameters, output) -> {
                    SKY_BLOCKS.values().forEach(output::accept);
                    CUSTOM_CREATIVE.forEach(output::accept);
                }).build());
        dev.yg.skyboxblock.network.SkyboxNetworking.register();
        dev.yg.skyboxblock.server.SkyboxServerCommands.register();
        LOGGER.info("[skyboxblock] initialized: {} sky window styles", SKY_BLOCKS.size());
    }
}
