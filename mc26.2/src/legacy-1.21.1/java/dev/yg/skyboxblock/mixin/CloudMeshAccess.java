package dev.yg.skyboxblock.mixin;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reuse the game's cloud mesh builder, without re-entering Iris/Sodium cloud rendering. */
@Mixin(LevelRenderer.class)
public interface CloudMeshAccess {
    @Invoker("buildClouds")
    MeshData skyboxblock$buildClouds(Tesselator tessellator, double x, double y, double z, Vec3 color);

    @Accessor("prevCloudsType")
    CloudStatus skyboxblock$getCloudType();

    @Accessor("prevCloudsType")
    void skyboxblock$setCloudType(CloudStatus status);

    @Accessor("ticks")
    int skyboxblock$getTicks();

    @Accessor("level")
    net.minecraft.client.multiplayer.ClientLevel skyboxblock$getLevel();

    @Accessor("starBuffer")
    com.mojang.blaze3d.vertex.VertexBuffer skyboxblock$getStarBuffer();
}
