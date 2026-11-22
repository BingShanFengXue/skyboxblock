#version 150

uniform sampler2D Sampler0;
uniform mat4 RayInverse;
in vec2 vUv;
out vec4 fragColor;

void main() {
    // Rebuild one direction per pixel from the two-plane unprojection, then sample the equirect.
    vec4 near = RayInverse * vec4(vUv * 2.0 - 1.0, -1.0, 1.0);
    vec4 far = RayInverse * vec4(vUv * 2.0 - 1.0, 0.0, 1.0);
    vec3 ray = normalize(far.xyz / far.w - near.xyz / near.w);
    float u = dot(ray.xz, ray.xz) < 1e-12 ? 0.5 : atan(ray.z, ray.x) / 6.28318530718 + 0.5;
    float v = acos(clamp(ray.y, -1.0, 1.0)) / 3.14159265359;
    fragColor = vec4(textureLod(Sampler0, vec2(u, v), 0.0).rgb, 1.0);
}
