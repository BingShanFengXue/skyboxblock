#version 330 core
layout(location=0) in vec3 Position;
uniform mat4 Projection;
uniform mat4 View;
uniform vec4 ClipPlane;
uniform int Clipping;
out float EyeDistance;
out vec3 SurfacePosition;
invariant gl_Position;
void main() {
    vec4 eye=View*vec4(Position,1.0);
    gl_Position=Projection*eye;
    gl_ClipDistance[0]=Clipping!=0 ? dot(ClipPlane,vec4(Position,1.0)) : 1.0;
    EyeDistance=-eye.z;
    SurfacePosition=Position;
}
