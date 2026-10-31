#version 150
uniform vec4 DrawColor;
uniform vec3 FogColor;
uniform float FogStart;
uniform float FogEnd;
in float vertexDistance;
out vec4 fragColor;
void main() {
    // Match Minecraft 1.21.1 position.fsh / fog.glsl, including vertex-distance interpolation.
    float fogValue = FogEnd <= FogStart ? step(FogEnd, vertexDistance)
        : (vertexDistance <= FogStart ? 0.0
        : (vertexDistance < FogEnd ? smoothstep(FogStart, FogEnd, vertexDistance) : 1.0));
    fragColor = vec4(mix(DrawColor.rgb, FogColor, fogValue), DrawColor.a);
}
