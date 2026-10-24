package dev.yg.skyboxblock.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.math.Axis;
import dev.yg.skyboxblock.mixin.CloudMeshAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/** Vanilla 1.21.1 sky geometry and blend ordering in a private, view-owned canvas. No world RGB input. */
public final class VanillaSkyScene {
    private static final ResourceLocation SUN=ResourceLocation.withDefaultNamespace("textures/environment/sun.png");
    private static final ResourceLocation MOON=ResourceLocation.withDefaultNamespace("textures/environment/moon_phases.png");
    private static final float[] UPPER=SkyVanillaGeometry.disc(16),LOWER=SkyVanillaGeometry.disc(-16);
    private static final SkyMesh MESH=new SkyMesh();
    private static TextureTarget target,displayTarget;
    private static ShaderInstance background,celestial,stars,dawn;

    private VanillaSkyScene() {}
    public static void setBackground(ShaderInstance shader) { background=shader; }
    public static void setCelestial(ShaderInstance shader) { celestial=shader; }
    public static void setStars(ShaderInstance shader) { stars=shader; }
    public static void setDawn(ShaderInstance shader) { dawn=shader; }
    public static void invalidate() { SkyVanillaClouds.invalidate(); }
    public static int render(SkyWorldRenderer.Frame frame,int width,int height) { return render(frame,width,height,false); }
    public static int renderDisplay(SkyWorldRenderer.Frame frame,int width,int height) { return render(frame,width,height,true); }

    private static int render(SkyWorldRenderer.Frame frame,int width,int height,boolean display) {
        if (background==null || width<=0 || height<=0) return -1;
        Matrix4f projection=display ? frame.projection : frame.skyProjection;
        String stage=display ? "display-" : "";
        try (SkyGlState state=new SkyGlState(6)) {
            frame.privateSkyClipBefore=GL11.glIsEnabled(GL30.GL_CLIP_DISTANCE0);
            if (frame.sky==null) frame.sky=SkyDisplayState.sample(frame.level,frame.camera,frame.partialTick);
            SkyDisplayState sky=frame.sky;
            TextureTarget canvas=display ? displayTarget : target;
            if (canvas==null || canvas.width!=width || canvas.height!=height) {
                if (canvas!=null) canvas.destroyBuffers();
                SkyGlState.active(GL13.GL_TEXTURE0);
                canvas=new TextureTarget(width,height,true,Minecraft.ON_OSX);
                if (display) displayTarget=canvas; else target=canvas;
            }
            // Native sky starts over the actual FogRenderer clear color, not a hand-painted gradient.
            canvas.setClearColor((float)sky.fogColor.x,(float)sky.fogColor.y,(float)sky.fogColor.z,1);
            canvas.clear(Minecraft.ON_OSX); canvas.bindWrite(true);
            GL11.glDepthRange(0,1); SkyGlState.opaqueState(); state.clearSamplers(); RenderFlags.sky();
            frame.privateSkyClipDuring=GL11.glIsEnabled(GL30.GL_CLIP_DISTANCE0);
            float fogStart=frame.nativeSkyFogCaptured ? frame.nativeSkyFogStart : 0F;
            float fogEnd=frame.nativeSkyFogCaptured ? frame.nativeSkyFogEnd
                    : Minecraft.getInstance().options.getEffectiveRenderDistance()*16F;
            background.getUniform("FogStart").set(fogStart);
            background.getUniform("FogEnd").set(fogEnd);
            background.getUniform("FogShape").set(frame.nativeSkyFogCaptured ? frame.nativeSkyFogShape : 0);
            background.getUniform("FogColor").set((float)sky.fogColor.x,(float)sky.fogColor.y,(float)sky.fogColor.z);
            Matrix4f skyMvp=new Matrix4f(projection).mul(frame.view);
            background.getUniform("SkyMvp").set(skyMvp);
            background.getUniform("DrawColor").set((float)sky.skyColor.x,(float)sky.skyColor.y,(float)sky.skyColor.z,1F);
            MESH.draw(background,UPPER,UPPER.length,false);

            RenderSystem.enableBlend(); GL11.glEnable(GL11.GL_BLEND);
            blend(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ZERO);
            if(dawn!=null && sky.sunrise!=null) {
                float flip=Mth.sin(sky.phase*((float)Math.PI*2))<0 ? 180F : 0F;
                Matrix4f dawnView=new Matrix4f(frame.view).rotate(Axis.XP.rotationDegrees(90))
                        .rotate(Axis.ZP.rotationDegrees(flip)).rotate(Axis.ZP.rotationDegrees(90));
                dawn.getUniform("SkyMvp").set(new Matrix4f(projection).mul(dawnView));
                MESH.drawColored(dawn,SkyVanillaGeometry.dawn(sky.sunrise));
            }
            blend(GL11.GL_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE,GL11.GL_ZERO);
            float weatherAlpha=1-sky.rain;
            Matrix4f solarView=new Matrix4f(frame.view).rotate(Axis.YP.rotationDegrees(-90))
                    .rotate(Axis.XP.rotationDegrees(sky.phase*360F));
            Matrix4f solarMvp=new Matrix4f(projection).mul(solarView);
            if (celestial!=null) {
                celestial.getUniform("SkyMvp").set(solarMvp);
                celestial.getUniform("DrawColor").set(1F,1F,1F,weatherAlpha);
                celestial.setSampler("Sampler0",texture(SUN));
                quad(celestial,30,100,false,0,0,1,1);
                int phase=sky.moonPhase;
                float u=(phase%4)/4F,v=(phase/4)/2F;
                celestial.setSampler("Sampler0",texture(MOON));
                quad(celestial,20,-100,true,u,v,u+.25F,v+.5F);
            }
            // Vanilla draws stars after the discs and uses brightness in RGB AND alpha.
            float brightness=sky.starBrightness*weatherAlpha;
            VertexBuffer starBuffer=((CloudMeshAccess)frame.renderer).skyboxblock$getStarBuffer();
            if (stars!=null && starBuffer!=null && brightness>0) {
                stars.getUniform("SkyMvp").set(solarMvp);
                stars.getUniform("DrawColor").set(brightness,brightness,brightness,brightness);
                RenderSystem.setShader(() -> stars);
                starBuffer.bind(); stars.apply(); starBuffer.draw(); stars.clear(); VertexBuffer.unbind();
            }
            RenderSystem.disableBlend(); GL11.glDisable(GL11.GL_BLEND);
            if (frame.level.effects().skyType()==net.minecraft.client.renderer.DimensionSpecialEffects.SkyType.NORMAL
                    && frame.camera.y<frame.level.getLevelData().getHorizonHeight(frame.level)) {
                background.getUniform("SkyMvp").set(new Matrix4f(projection).mul(new Matrix4f(frame.view).translate(0,12,0)));
                background.getUniform("DrawColor").set(0F,0F,0F,1F);
                MESH.draw(background,LOWER,LOWER.length,false);
            }
            RenderRegression.sceneSnapshot(canvas,stage+"base");
            SkyVanillaClouds.render(frame.renderer,frame.level,frame.camera,frame.view,projection,frame.partialTick,sky);
            RenderRegression.sceneSnapshot(canvas,stage+"clouds");
            PortalViewCapture.privateScene(frame,canvas,display);
            return canvas.getColorTextureId();
        }
    }
    private static void blend(int src,int dst,int srcAlpha,int dstAlpha) {
        com.mojang.blaze3d.platform.GlStateManager._blendFuncSeparate(src,dst,srcAlpha,dstAlpha);
        GL14.glBlendFuncSeparate(src,dst,srcAlpha,dstAlpha);
    }
    private static void quad(ShaderInstance shader,float size,float y,boolean moon,float u0,float v0,float u1,float v1) {
        float[] vertices=SkyVanillaGeometry.celestial(size,y,moon,u0,v0,u1,v1);
        MESH.draw(shader,vertices,vertices.length,true);
    }
    private static int texture(ResourceLocation location) {
        SkyGlState.active(GL13.GL_TEXTURE0);
        int id=Minecraft.getInstance().getTextureManager().getTexture(location).getId();
        SkyGlState.bindTexture(id); return id;
    }
    public static void release() {
        MESH.close();
        if (target!=null) target.destroyBuffers();
        if (displayTarget!=null) displayTarget.destroyBuffers();
        target=displayTarget=null;
    }
    private static final class RenderFlags {
        static void sky() {
            if (!(Boolean.getBoolean("skyboxblock.regression")
                    && Boolean.getBoolean("skyboxblock.regression.negativePrivateSkyClip")))
                GL11.glDisable(GL30.GL_CLIP_DISTANCE0);
            RenderSystem.disableDepthTest(); GL11.glDisable(GL11.GL_DEPTH_TEST);
            RenderSystem.depthMask(false); GL11.glDepthMask(false);
            RenderSystem.disableCull(); GL11.glDisable(GL11.GL_CULL_FACE);
        }
    }
}
