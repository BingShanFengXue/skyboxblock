package dev.yg.skyboxblock.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;

/** Owned VAO/VBO: positions are not preprocessed by third-party Minecraft buffer optimizers. */
final class SkyMesh implements AutoCloseable {
    private int vao;
    private int vbo;
    private FloatBuffer upload;

    void draw(ShaderInstance shader, float[] data, int floats, boolean textured) {
        draw(shader,data,floats,textured ? 5 : 3);
    }

    void drawColored(ShaderInstance shader, float[] data) { draw(shader,data,data.length,7); }

    private void draw(ShaderInstance shader,float[] data,int floats,int stride) {
        if (floats == 0) return;
        if (vao == 0) {
            vao = GL30.glGenVertexArrays();
            vbo = GL15.glGenBuffers();
        }
        if (upload == null || upload.capacity() < floats) {
            upload = BufferUtils.createFloatBuffer(Math.max(1024, Integer.highestOneBit(floats - 1) << 1));
        }
        upload.clear();
        upload.put(data, 0, floats).flip();
        RenderSystem.setShader(() -> shader);
        shader.apply();
        try {
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, upload, GL15.GL_STREAM_DRAW);
            int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int position = GL20.glGetAttribLocation(program, "Position");
            int uv = stride==5 ? GL20.glGetAttribLocation(program, "UV0") : -1;
            int color = stride==7 ? GL20.glGetAttribLocation(program, "Color") : -1;
            if (position < 0) throw new IllegalStateException("Sky shader has no Position attribute");
            GL20.glEnableVertexAttribArray(position);
            GL20.glVertexAttribPointer(position, 3, GL11.GL_FLOAT, false, stride * Float.BYTES, 0L);
            if (uv >= 0) {
                GL20.glEnableVertexAttribArray(uv);
                GL20.glVertexAttribPointer(uv, 2, GL11.GL_FLOAT, false, stride * Float.BYTES, 3L * Float.BYTES);
            }
            if (color >= 0) {
                GL20.glEnableVertexAttribArray(color);
                GL20.glVertexAttribPointer(color, 4, GL11.GL_FLOAT, false, stride * Float.BYTES, 3L * Float.BYTES);
            }
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, floats / stride);
            GL20.glDisableVertexAttribArray(position);
            if (uv >= 0) GL20.glDisableVertexAttribArray(uv);
            if (color >= 0) GL20.glDisableVertexAttribArray(color);
        } finally {
            shader.clear();
        }
    }

    @Override public void close() {
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        if (vbo != 0) GL15.glDeleteBuffers(vbo);
        vao = vbo = 0;
        upload = null;
    }
}
