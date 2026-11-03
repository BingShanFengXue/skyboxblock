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
            source.sendError(Component.literal("尚未初始化自定义天空盒目录"));
            return 0;
        }
        // 26.3 移除了 Util.getPlatform().openFile(...)；用 AWT Desktop 打开，失败也不影响命令本身。
        try {
            java.awt.Desktop.getDesktop().open(dir.resolve(".").toFile());
        } catch (Exception error) {
            source.sendFeedback(Component.literal("无法自动打开文件夹，请手动前往上方的路径")
                    .withStyle(ChatFormatting.GRAY));
        }
        source.sendFeedback(Component.literal("已打开：").append(Component.literal(dir.toString())
                .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                        // 26.2：ClickEvent/HoverEvent 变成 record 子类，构造器不再接受 Action 常量。
                        .withClickEvent(new ClickEvent.OpenFile(dir.toString()))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击打开文件夹"))))));
        source.sendFeedback(Component.literal("修改配置后需要重启游戏才会生效").withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static int list(FabricClientCommandSource source) {
        List<CustomSkyRegistry.Style> styles = CustomSkyRegistry.styles();
        if (styles.isEmpty()) {
            source.sendFeedback(Component.literal("当前没有启用的自定义天空盒").withStyle(ChatFormatting.GRAY));
        } else {
            source.sendFeedback(Component.literal("已启用 " + styles.size() + " 种自定义天空盒："));
            for (CustomSkyRegistry.Style style : styles)
                source.sendFeedback(Component.literal("  " + style.id() + " → " + style.picture())
                        .withStyle(ChatFormatting.AQUA));
        }
        List<String> warnings = CustomSkyRegistry.warnings();
        if (!warnings.isEmpty()) {
            source.sendFeedback(Component.literal("被跳过/警告 " + warnings.size() + " 条：").withStyle(ChatFormatting.YELLOW));
            for (String warning : warnings.stream().limit(10).toList())
                source.sendFeedback(Component.literal("  " + warning).withStyle(ChatFormatting.GRAY));
        }
        return styles.size();
    }
}
