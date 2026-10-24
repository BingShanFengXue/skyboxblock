#version 150

uniform sampler2D Sampler0;
uniform float CloudFogStart;
uniform float CloudFogEnd;

in vec2 cloudUv;
in vec4 cloudColor;
in float cloudDistance;
out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, cloudUv) * cloudColor;
    if (color.a < 0.1) discard;
    color.a *= 1.0 - smoothstep(CloudFogStart, CloudFogEnd, cloudDistance);
    if (color.a < 0.001) discard;
    fragColor = color;
}
