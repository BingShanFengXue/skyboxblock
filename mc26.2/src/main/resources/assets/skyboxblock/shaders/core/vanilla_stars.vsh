#version 150
in vec3 Position;
uniform mat4 SkyMvp;
void main() {
    gl_Position = SkyMvp * vec4(Position, 1.0);
}
