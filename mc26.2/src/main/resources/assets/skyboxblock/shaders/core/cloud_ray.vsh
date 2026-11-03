#version 330

out vec2 screenUv;

void main() {
    // 全屏三角形：不做任何顶点输入，位置由 gl_VertexID 生成（与原版 core/screenquad 一致）
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    screenUv = uv;
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
}
