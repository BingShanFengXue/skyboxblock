#version 150
in vec3 Position;
in vec2 UV0;
uniform mat4 SkyMvp;
out vec2 celestialUv;
void main() {
    gl_Position = SkyMvp * vec4(Position, 1.0);
    celestialUv = UV0;
}
