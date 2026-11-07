package dev.yg.skyboxblock.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.yg.skyboxblock.custom.CustomSkyRegistry;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Util;

import java.nio.file.Path;
import java.util.List;

/**
 * Client-only helper: opens the studio folder and reports what was actually loaded.
 *
 * <p>Kept separate from the server-side {@code /skyboxblock} tree on purpose — that one is a
 * permission-2 server command, and mixing a client action into it would shadow it unpredictably.
 */
public final class CustomSkyCommand {
    private CustomSkyCommand() { }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> root = LiteralArgumentBuilder.literal("skyboxcustom");
            root.executes(context -> open(context.getSource()));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("open")
                    .executes(context -> open(context.getSource())));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list")
                    .executes(context -> list(context.getSource())));
            dispatcher.register(root);
        });
    }

    private static int open(FabricClientCommandSource source) {
        Path dir = CustomSkyRegistry.baseDir();
        if (dir == null) {
            source.sendError(Component.translatable("command.skyboxblock.custom.not_ready"));
            return 0;
        }
        Path folder = dir.toAbsolutePath().normalize();
        Component path = Component.literal(folder.toString())
                .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent.OpenFile(folder.toString()))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.translatable("command.skyboxblock.custom.open_folder"))));
        if (!java.nio.file.Files.isDirectory(folder)) {
            source.sendError(Component.translatable("command.skyboxblock.custom.not_ready"));
            return 0;
        }
        try {
            Util.getPlatform().openFile(folder.toFile());
        } catch (RuntimeException error) {
            source.sendError(Component.translatable("command.skyboxblock.custom.open_failed", path));
            return 0;
        }
        source.sendFeedback(Component.translatable("command.skyboxblock.custom.opened", path));
        source.sendFeedback(Component.translatable("command.skyboxblock.custom.restart_required")
                .withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static int list(FabricClientCommandSource source) {
        List<CustomSkyRegistry.Style> styles = CustomSkyRegistry.styles();
        if (styles.isEmpty()) {
            source.sendFeedback(Component.translatable("command.skyboxblock.custom.list_empty")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            source.sendFeedback(Component.translatable("command.skyboxblock.custom.list_count", styles.size()));
            for (CustomSkyRegistry.Style style : styles)
                source.sendFeedback(Component.translatable("command.skyboxblock.custom.list_entry",
                                style.id(), style.picture())
                        .withStyle(ChatFormatting.AQUA));
        }
        List<String> warnings = CustomSkyRegistry.warnings();
        if (!warnings.isEmpty()) {
            source.sendFeedback(Component.translatable("command.skyboxblock.custom.warning_count", warnings.size())
                    .withStyle(ChatFormatting.YELLOW));
            for (String warning : warnings.stream().limit(10).toList())
                source.sendFeedback(Component.translatable("command.skyboxblock.custom.warning_entry", warning)
                        .withStyle(ChatFormatting.GRAY));
        }
        return styles.size();
    }
}
