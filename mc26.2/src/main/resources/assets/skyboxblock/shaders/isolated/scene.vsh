#version 330 core
layout(location=0) in vec3 Position;
layout(location=1) in vec2 UV;
layout(location=2) in vec4 Color;
uniform mat4 Mvp;
uniform vec4 PositionOffset;
out vec2 texCoord;
out vec4 vertexColor;
out float sphericalDistance;
out float cylindricalDistance;
void main() {
    vec3 pos=Position+PositionOffset.xyz;
    gl_Position=Mvp*vec4(pos,1.0);
    texCoord=UV; vertexColor=Color;
    sphericalDistance=length(pos);
    cylindricalDistance=max(length(pos.xz),abs(pos.y));
}
