package dev.yg.skyboxblock.client;

import com.mojang.brigadier.arguments.FloatArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import java.io.IOException;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** Client-local appearance controls; never change the world's clouds or another sky block type. */
public final class SkyboxCommands {
    private SkyboxCommands() { }
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                literal("skyboxblock")
                    .executes(context -> report(context.getSource()))
                    .then(literal("cloudheight")
                        .executes(context -> report(context.getSource()))
                        .then(literal("reset").executes(context -> set(context.getSource(),SkyRenderMath.VANILLA_CLOUD_HEIGHT)))
                        .then(argument("height",FloatArgumentType.floatArg(SkyboxConfig.MIN_CLOUD_HEIGHT,SkyboxConfig.MAX_CLOUD_HEIGHT))
                            .executes(context -> set(context.getSource(),FloatArgumentType.getFloat(context,"height")))))
                    .then(literal("clouddistance")
                        .executes(context -> report(context.getSource()))
                        .then(literal("reset").executes(context -> setDistance(context.getSource(),SkyboxConfig.DEFAULT_CLOUD_FADE_END)))
                        .then(argument("distance",FloatArgumentType.floatArg(SkyboxConfig.MIN_CLOUD_DISTANCE,SkyboxConfig.MAX_CLOUD_DISTANCE))
                            .executes(context -> setDistance(context.getSource(),FloatArgumentType.getFloat(context,"distance")))))
                    .then(literal("cloudfade")
                        .executes(context -> report(context.getSource()))
                        .then(literal("reset").executes(context -> setFade(context.getSource(),SkyboxConfig.DEFAULT_CLOUD_FADE_START,SkyboxConfig.DEFAULT_CLOUD_FADE_END)))
                        .then(argument("start",FloatArgumentType.floatArg(0,SkyboxConfig.MAX_CLOUD_DISTANCE))
                            .then(argument("end",FloatArgumentType.floatArg(SkyboxConfig.MIN_CLOUD_DISTANCE,SkyboxConfig.MAX_CLOUD_DISTANCE))
                                .executes(context -> setFade(context.getSource(),FloatArgumentType.getFloat(context,"start"),FloatArgumentType.getFloat(context,"end"))))))
                    .then(literal("reload").executes(context -> { SkyboxConfig.reload(); return report(context.getSource()); }))));
    }
    private static int report(FabricClientCommandSource source) {
        SkyboxConfig config=SkyboxConfig.get();
        source.sendFeedback(Component.literal("天空方块内部云层：高度 Y="+config.vanillaCloudHeight()
                +"，淡出 "+config.vanillaCloudFadeStart()+" → "+config.vanillaCloudFadeEnd()+" 格"
                +"（仅本客户端；cloudheight / clouddistance / cloudfade；reset 恢复默认）"));
        if(System.getProperty("skyboxblock.vanillaCloudFadeStart")!=null || System.getProperty("skyboxblock.vanillaCloudFadeEnd")!=null)
            source.sendFeedback(Component.literal("JVM 云层距离参数优先于配置文件；指令仍会保存文件设置。"));
        return 1;
    }
    private static int setDistance(FabricClientCommandSource source,float end) {
        return setFade(source,end/3F,end);
    }
    private static int setFade(FabricClientCommandSource source,float start,float end) {
        try { SkyboxConfig.setVanillaCloudFade(start,end); return report(source); }
        catch(IOException | IllegalArgumentException exception) {
            source.sendError(Component.literal("云层距离设置失败："+exception.getMessage()));
            return 0;
        }
    }
    private static int set(FabricClientCommandSource source,float height) {
        try { SkyboxConfig.setVanillaCloudHeight(height); return report(source); }
        catch (IOException exception) {
            source.sendError(Component.literal("云高配置保存失败："+exception.getMessage()));
            return 0;
        }
    }
}
