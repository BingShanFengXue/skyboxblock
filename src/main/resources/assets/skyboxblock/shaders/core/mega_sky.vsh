#version 150
// mega_sky.vsh — 全屏三角形：Position.xy 直接当 NDC 输出，vUv 交给片元做射线重建。
in vec3 Position;
out vec2 vUv;

void main() {
    vUv = Position.xy * 0.5 + 0.5;
    gl_Position = vec4(Position.xy, 0.0, 1.0);
}
