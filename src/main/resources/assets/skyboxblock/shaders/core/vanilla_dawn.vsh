#version 150
in vec3 Position;
in vec4 Color;
uniform mat4 SkyMvp;
out vec4 vertexColor;
void main() {
    gl_Position = SkyMvp * vec4(Position, 1.0);
    vertexColor = Color;
}
