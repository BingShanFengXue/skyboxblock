#version 150
in vec3 Position;
uniform mat4 SkyMvp;
uniform int FogShape;
out float vertexDistance;
void main() {
    gl_Position = SkyMvp * vec4(Position, 1.0);
    vertexDistance = FogShape == 0 ? length(Position) : max(length(Position.xz), abs(Position.y));
}
