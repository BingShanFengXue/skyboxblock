#version 330

// 视线-云层求交：不需要任何顶点几何，纯片元计算。
// InvViewProj 由 Java 端每帧传入（相机 view-projection 的逆矩阵），
// Params = (云层高度, 一格云的方块数, 时间秒, 整体 alpha)

layout(std140) uniform CloudParams {
    mat4 InvViewProj;
    vec4 Params;
};

uniform sampler2D Sampler0;

in vec2 screenUv;
out vec4 fragColor;

void main() {
    vec2 ndc = screenUv * 2.0 - 1.0;

    vec4 nearPoint = InvViewProj * vec4(ndc, -1.0, 1.0);
    vec4 farPoint = InvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 origin = nearPoint.xyz / nearPoint.w;
    vec3 direction = normalize(farPoint.xyz / farPoint.w - origin);

    // 与 y = cloudHeight 的平面求交；视线朝上时才可能命中
    if (abs(direction.y) < 1.0e-4) discard;
    float t = (Params.x - origin.y) / direction.y;
    if (t <= 0.0) discard;

    vec2 world = origin.xz + direction.xz * t;
    // 云图是 256×256 的"格子图"，一格 = cellSize 个方块
    vec2 cellUv = world / (Params.y * 256.0);
    vec2 uv = fract(cellUv + vec2(Params.z * 0.0015, 0.0));

    vec4 cloud = texture(Sampler0, uv);
    if (cloud.a < 0.05) discard;

    // 越远越淡（原版也有类似的随距离淡出）
    float distanceFade = clamp(1.0 - t / 512.0, 0.0, 1.0);
    fragColor = vec4(cloud.rgb, cloud.a * Params.w * distanceFade);
}
