package dev.yg.skyboxblock;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
import java.util.Map;

/** Shared registration for the independent vanilla, solid and panoramic sky windows. */
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

    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(MOD_ID, path); }

    @Override
    public void onInitialize() {
        for (SkyStyle style : SkyStyle.values()) {
            SkyBlock block = Registry.register(BuiltInRegistries.BLOCK, id(style.blockId()), new SkyBlock(style,
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).strength(.3F)
                            .sound(SoundType.GLASS).noOcclusion()
                            .isViewBlocking((state, level, pos) -> false)
                            .isSuffocating((state, level, pos) -> false)));
            SKY_BLOCKS.put(style, block);
            Registry.register(BuiltInRegistries.ITEM, id(style.blockId()), new BlockItem(block, new Item.Properties()));
        }
        // Custom styles must exist before the registry freezes, so the folder is read here, not on reload.
        dev.yg.skyboxblock.custom.CustomSkyRegistry.load(
                net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir());
        for (var custom : dev.yg.skyboxblock.custom.CustomSkyRegistry.styles()) {
            var blockId = ResourceLocation.parse(custom.id());
            SkyBlock block = Registry.register(BuiltInRegistries.BLOCK, blockId, new SkyBlock(
                    dev.yg.skyboxblock.SkySkin.custom(custom.id(), custom.panorama()),
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).strength(.3F)
                            .sound(SoundType.GLASS).noOcclusion()
                            .isViewBlocking((state, level, pos) -> false)
                            .isSuffocating((state, level, pos) -> false)));
            CUSTOM_BLOCKS.add(block);
            Item item = new BlockItem(block, new Item.Properties()) {
                @Override public net.minecraft.network.chat.Component getName(ItemStack stack) {
                    return net.minecraft.network.chat.Component.literal(custom.displayName());
                }
            };
            Registry.register(BuiltInRegistries.ITEM, blockId, item);
            if (custom.creative()) CUSTOM_CREATIVE.add(item);
        }
        SKY_BLOCK = SKY_BLOCKS.get(SkyStyle.VANILLA);
        // Keep the old BE id: existing vanilla blocks/worlds retain their identity.
        java.util.List<SkyBlock> allBlocks = new java.util.ArrayList<>(SKY_BLOCKS.values());
        allBlocks.addAll(CUSTOM_BLOCKS);
        SKY_BLOCK_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("sky_block"),
                BlockEntityType.Builder.of(SkyBlockEntity::new, allBlocks.toArray(SkyBlock[]::new)).build(null));
        SKY_TAB = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id("sky"), FabricItemGroup.builder()
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
