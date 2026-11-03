package dev.yg.skyboxblock.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4fc;

/**
 * 26.2 host facade. Rendering belongs to the view-scoped opaque/final mixins, not START_MAIN.
 * In particular this class never constructs or calls SkyRenderer: Iris instruments those methods
 * with global phase changes, so invoking them for a private canvas corrupts the real world sky.
 */
public final class SkyRendererHost implements SkyHost {
    public SkyRendererHost() { }
    /** No entity-lighted RenderType participates in the sky-window path. */
    @Override public RenderType faceRenderType() {return null;}
    @Override public Matrix4fc viewProjection() {return null;}
    @Override public void onStartMain() { }
    @Override public void maybeDumpMainFrame() { }
    @Override public void close() {SkyWindowRenderer.close();}
    /** Kept as a binary/source-compatible no-op for the retired CloudTargetMixin. */
    public static RenderTarget cloudTargetForPass() {return null;}
    public static Identifier textureId() {return null;}
}
