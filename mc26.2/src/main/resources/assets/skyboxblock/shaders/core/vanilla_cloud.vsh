#version 150

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 cloudUv;
out vec4 cloudColor;
out float cloudDistance;

void main() {
    vec4 viewPosition = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPosition;
    cloudUv = UV0;
    cloudColor = Color;
    cloudDistance = length(viewPosition.xyz);
}
