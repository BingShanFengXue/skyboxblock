#version 150
in vec3 Position;
uniform mat4 WorldProjection;
uniform mat4 WorldView;
uniform vec4 ClipPlane;
uniform int ClippingEnabled;
uniform vec2 RasterScale;
uniform vec2 RasterJitter;
invariant gl_Position;
out vec3 SkyWorldPosition;
void main() {
    SkyWorldPosition = Position;
    gl_Position = WorldProjection * WorldView * vec4(Position, 1.0);
    gl_Position.xy = gl_Position.xy * RasterScale + (RasterScale - vec2(1.0) + RasterJitter) * gl_Position.w;
    gl_ClipDistance[0] = ClippingEnabled != 0 ? dot(ClipPlane, vec4(Position, 1.0)) : 1.0;
}
