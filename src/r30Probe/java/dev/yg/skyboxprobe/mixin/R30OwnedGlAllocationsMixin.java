package dev.yg.skyboxprobe.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.yg.skyboxprobe.R30GpuLifetime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Exact direct owned GL gen/delete sites only. Missing sites are detected by inventory reconciliation. */
@Mixin(targets={"dev.yg.skyboxblock.client.SkyWorldRenderer","dev.yg.skyboxblock.client.SkyMesh",
        "dev.yg.skyboxblock.client.SkyCloudMesh","dev.yg.skyboxblock.client.SkyCoverage$Slot",
        "dev.yg.skyboxblock.client.ItrpSkyBridge","dev.yg.skyboxblock.client.SkyTemporalEdgeMask"},remap=false)
public abstract class R30OwnedGlAllocationsMixin {
    @WrapOperation(method="*",at=@At(value="INVOKE",target="Lorg/lwjgl/opengl/GL11;glGenTextures()I",remap=false),require=0,remap=false)
    private static int skyboxprobe$texture(Operation<Integer> original) { int id=original.call(); R30GpuLifetime.created("texture",id); return id; }
    @WrapOperation(method="*",at=@At(value="INVOKE",target="Lorg/lwjgl/opengl/GL11;glDeleteTextures(I)V",remap=false),require=0,remap=false)
    private static void skyboxprobe$deleteTexture(int id,Operation<Void> original) { original.call(id); R30GpuLifetime.deleted("texture",id); }
    @WrapOperation(method="*",at=@At(value="INVOKE",target="Lorg/lwjgl/opengl/GL30;glGenFramebuffers()I",remap=false),require=0,remap=false)
    private static int skyboxprobe$fbo(Operation<Integer> original) { int id=original.call(); R30GpuLifetime.created("framebuffer",id); return id; }
    @WrapOperation(method="*",at=@At(value="INVOKE",target="Lorg/lwjgl/opengl/GL30;glDeleteFramebuffers(I)V",remap=false),require=0,remap=false)
    private static void skyboxprobe$deleteFbo(int id,Operation<Void> original) { original.call(id); R30GpuLifetime.deleted("framebuffer",id); }
    @WrapOperation(method="*",at=@At(value="INVOKE",target="Lorg/lwjgl/opengl/GL15;glGenBuffers()I",remap=false),require=0,remap=false)
    private static int skyboxprobe$buffer(Operation<Integer> original) { int id=original.call(); R30GpuLifetime.created("buffer",id); return id; }
    @WrapOperation(method="*",at=@At(value="INVOKE",target="Lorg/lwjgl/opengl/GL15;glDeleteBuffers(I)V",remap=false),require=0,remap=false)
    private static void skyboxprobe$deleteBuffer(int id,Operation<Void> original) { original.call(id); R30GpuLifetime.deleted("buffer",id); }
    @WrapOperation(method="*",at=@At(value="INVOKE",target="Lorg/lwjgl/opengl/GL30;glGenVertexArrays()I",remap=false),require=0,remap=false)
    private static int skyboxprobe$vao(Operation<Integer> original) { int id=original.call(); R30GpuLifetime.created("vertexArray",id); return id; }
    @WrapOperation(method="*",at=@At(value="INVOKE",target="Lorg/lwjgl/opengl/GL30;glDeleteVertexArrays(I)V",remap=false),require=0,remap=false)
    private static void skyboxprobe$deleteVao(int id,Operation<Void> original) { original.call(id); R30GpuLifetime.deleted("vertexArray",id); }
}
