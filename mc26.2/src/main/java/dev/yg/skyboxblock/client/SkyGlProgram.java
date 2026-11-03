package dev.yg.skyboxblock.client;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.*;

/** Project-owned GL program and interleaved mesh. Does not enter Iris's RenderPipeline override map. */
final class SkyGlProgram implements AutoCloseable {
    private final int id;
    private final java.util.Map<String,Integer> uniforms=new java.util.HashMap<>();
    private int vao,vbo;
    private java.nio.FloatBuffer upload;

    SkyGlProgram(String name) {
        int vertex=shader(GL20.GL_VERTEX_SHADER,name+".vsh"),fragment=0,program=0;
        try {
            fragment=shader(GL20.GL_FRAGMENT_SHADER,name+".fsh"); program=GL20.glCreateProgram();
            GL20.glAttachShader(program,vertex); GL20.glAttachShader(program,fragment); GL20.glLinkProgram(program);
            if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)
                throw new IllegalStateException(name+": "+GL20.glGetProgramInfoLog(program));
            id=program;
        } catch(RuntimeException error) { if(program!=0) GL20.glDeleteProgram(program); throw error; }
        finally { GL20.glDeleteShader(vertex); if(fragment!=0) GL20.glDeleteShader(fragment); }
    }
    private static int shader(int type,String name) {
        int shader=GL20.glCreateShader(type);
        try (var in=Minecraft.getInstance().getResourceManager().open(
                Identifier.fromNamespaceAndPath("skyboxblock","shaders/isolated/"+name))) {
            GL20.glShaderSource(shader,new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            GL20.glCompileShader(shader);
            if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)
                throw new IllegalStateException(name+": "+GL20.glGetShaderInfoLog(shader));
            return shader;
        } catch(java.io.IOException|RuntimeException error) { GL20.glDeleteShader(shader); throw new IllegalStateException(name,error); }
    }
    void use() { GL20.glUseProgram(id); }
    private int loc(String name) { return uniforms.computeIfAbsent(name,n -> GL20.glGetUniformLocation(id,n)); }
    void integer(String name,int v) { GL20.glUniform1i(loc(name),v); }
    void scalar(String name,float v) { GL20.glUniform1f(loc(name),v); }
    void vector2(String name,float x,float y) { GL20.glUniform2f(loc(name),x,y); }
    void vector4(String name,float x,float y,float z,float w) { GL20.glUniform4f(loc(name),x,y,z,w); }
    void matrix(String name,Matrix4fc value) { GL20.glUniformMatrix4fv(loc(name),false,value.get(new float[16])); }
    void texture(String name,int unit,int texture,int sampler) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0+unit); GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
        GL33.glBindSampler(unit,sampler); integer(name,unit);
    }
    /** Position(3), UV(2), color(4), all float. Quads are triangulated by the producer. */
    void draw(float[] vertices,int count) {
        if(count==0) return;
        if(vao==0) { vao=GL30.glGenVertexArrays(); vbo=GL15.glGenBuffers(); }
        GL30.glBindVertexArray(vao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);
        if(upload==null||upload.capacity()<count)upload=org.lwjgl.BufferUtils.createFloatBuffer(Integer.highestOneBit(Math.max(1024,count)-1)<<1);
        upload.clear();upload.put(vertices,0,count).flip();GL15.glBufferData(GL15.GL_ARRAY_BUFFER,upload,GL15.GL_STREAM_DRAW);
        GL20.glEnableVertexAttribArray(0); GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,36,0L);
        GL20.glEnableVertexAttribArray(1); GL20.glVertexAttribPointer(1,2,GL11.GL_FLOAT,false,36,12L);
        GL20.glEnableVertexAttribArray(2); GL20.glVertexAttribPointer(2,4,GL11.GL_FLOAT,false,36,20L);
        GL11.glDrawArrays(GL11.GL_TRIANGLES,0,count/9);
    }
    void draw(float[] vertices) { draw(vertices,vertices.length); }
    @Override public void close() {
        if(vao!=0) GL30.glDeleteVertexArrays(vao); if(vbo!=0) GL15.glDeleteBuffers(vbo); GL20.glDeleteProgram(id);
    }
}
