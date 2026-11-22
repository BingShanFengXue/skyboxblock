#version 150
// ============================================================================
// mega_sky.fsh — 程序化星空 / 星云背景（全屏三角形，GLSL 150，单 pass）
//
// 用途：替代有接缝的等距柱状贴图背景（母版 = skybox-test/shaders/back.frag：
//       暖色丝状星云 + 暗尘 + 细密方块星点）。
//
// 无缝性的两条数学保证
// ---------------------------------------------------------------------------
// 1) 星云 —— 所有噪声都是方向向量 dir 的纯 3D 函数。不采样任何贴图、不用屏幕
//    坐标做图案、不经过 atan/asin 之类的球面参数化，所以球面上每一点的颜色只由
//    dir 决定，根本不存在"贴图边界"这种可以出缝的东西。
//
// 2) 星点 —— 立方体面量化（原型 skyCell 的思路）会有一个必然的奇点：
//        q = d.yz / |d.x|，在 |d.x| → 0 处退化。
//    硬选最大分量面（if (a.x >= m) ...）会在立方体棱上把方块切断，留下沿棱的
//    方块碎片带 —— 这就是原先看得见的接缝。这里改成三轴加权混合：
//      · 对 x / y / z 三种面参数化各算一遍星点，得到三个候选 s_x, s_y, s_z；
//        每个候选在自身参数化里都是 q 的连续函数（方块中心固定在格内 0.28~0.72，
//        格边界上两侧取值都已经是 0，所以跨格不会跳变）；
//      · 权重 w = |d|^16 归一化（sum w = 1）：处处连续，且在自身退化面
//        |d.axis| → 0 处严格趋 0，于是"被拉伸/翻面的那一支"自动消失；
//      · 权重守恒 ⇒ 平均星点亮度与视角无关，棱附近不会出现亮带或暗带；
//      · 分母 max(|d.axis|, 0.35) 只是把零权重区域的 q 收进有限范围，
//        避免精度抖动（该区域权重 < 1e-7，取值不影响画面）。
//    三项连续函数 × 连续权重求和 ⇒ 整体仍是 dir 的连续函数，立方体棱/角上
//    没有硬切，也没有盘状、条形或沿棱的接缝。
//
// 常数全部静态（接口只暴露 RayInverse，不加时间/贴图 uniform）。
// 亮度整体压到母版的 ~0.8 倍：这是天空背景，不能盖过前景巨构。
// ============================================================================

uniform mat4 RayInverse;   // = inverse(projection * view)
uniform float Time;        // seconds; every beam animation is a pure function of it
in  vec2 vUv;
out vec4 fragColor;

// Glow of one finite beam segment seen from the origin. Measured in *angular* space by sampling
// the segment, so the result can never leak across the sky or seam like a texture would.
float beamGlow(vec3 dir, vec3 a, vec3 b, float radius, float phase) {
    float best = 1e9;
    float depth = 1.0;
    float along = 0.0;
    for (int i = 0; i <= 8; i++) {
        float f = float(i) / 8.0;
        vec3 p = mix(a, b, f);
        float len = length(p);
        if (len < 1e-3) continue;
        float ang = length(cross(dir, p / len));
        if (ang < best) { best = ang; depth = len; along = f; }
    }
    if (best > 1e8) return 0.0;
    float angularRadius = radius / max(depth, 1.0);
    float core = exp(-(best * best) / (2.0 * angularRadius * angularRadius));
    float ends = smoothstep(0.0, 0.10, along) * (1.0 - smoothstep(0.55, 1.0, along));
    float ripple = 0.86 + 0.14 * sin(phase * 2.0 + along * 28.0);
    return core * ends * ripple;
}

const float STAR_CELLS   = 48.0;   // 每个立方体面上的网格密度
const float STAR_DENSITY = 0.12;   // 有星格的占比
const float STAR_GAIN    = 0.55;   // 相对母版的压暗系数
const float NEB_GAIN     = 0.78;   // 母版 1.45 → 压低但不至于看不见

// ---------------------------------------------------------------- 噪声基元
float hash13(vec3 p3) {
    p3 = fract(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return fract((p3.x + p3.y) * p3.z);
}

vec3 hash33(vec3 p3) {
    p3 = fract(p3 * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yxz + 33.33);
    return fract((p3.xxy + p3.yzz) * p3.zyx);
}

float vnoise(vec3 x) {
    vec3 i = floor(x), f = x - i;
    vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float a = mix(hash13(i), hash13(i + vec3(1, 0, 0)), u.x);
    float b = mix(hash13(i + vec3(0, 1, 0)), hash13(i + vec3(1, 1, 0)), u.x);
    float c = mix(hash13(i + vec3(0, 0, 1)), hash13(i + vec3(1, 0, 1)), u.x);
    float d = mix(hash13(i + vec3(0, 1, 1)), hash13(i + vec3(1, 1, 1)), u.x);
    return mix(mix(a, b, u.y), mix(c, d, u.y), u.z);
}

// 三层 fbm —— 全片元着色器最多这么多层，循环体内只有乘加
float fbm(vec3 p) {
    float s = 0.0, amp = 0.5;
    for (int i = 0; i < 3; i++) {
        s += amp * vnoise(p);
        p = p * 2.07 + 17.1;
        amp *= 0.5;
    }
    return s;
}

// ---------------------------------------------------------------- 星点
// 单个立方体面参数化下的方块星点。q ∈ [-1,1] 是该面的完整范围。
// q 连续 ⇒ 本函数在面内（含越界到邻面）连续：方块永不贴格边界，
// 因此跨格、跨面都不会出现"半个方块"。
vec3 starBox(vec2 q, float face) {
    vec2 g = (q * 0.5 + 0.5) * STAR_CELLS;
    vec2 uv = fract(g);
    vec3 cell = vec3(floor(g), face);

    float exist = hash13(cell * 1.7);
    if (exist < 1.0 - STAR_DENSITY) return vec3(0.0);

    vec3 rnd = hash33(cell + 0.37);
    vec2 c = vec2(0.28 + 0.44 * rnd.x, 0.28 + 0.44 * rnd.y);
    float size = mix(0.035, 0.095, rnd.z * rnd.z);
    float dd = max(abs(uv.x - c.x), abs(uv.y - c.y));
    float on = 1.0 - smoothstep(size - 0.03, size + 0.03, dd);
    if (on <= 0.001) return vec3(0.0);

    float temp = hash13(cell + 71.0);
    vec3 tint = temp < 0.10 ? vec3(0.80, 0.88, 1.00)
              : temp < 0.22 ? vec3(1.00, 0.87, 0.72)
                            : vec3(1.00, 0.98, 0.94);
    return tint * on * mix(0.22, 0.92, pow(exist, 4.0));
}

// 三轴加权混合：|d|^16 归一化权重把"面归属"从硬切变成平滑过渡。
vec3 starField(vec3 d) {
    vec3 a = abs(d);
    vec3 w = a * a;                 // a^2
    w *= w;                         // a^4
    w *= w;                         // a^8
    w *= w;                         // a^16
    w /= (w.x + w.y + w.z);         // 归一化：sum = 1，亮度守恒

    // 分母下限 0.35 < 1/sqrt(3)：自身权重已在 1e-8 量级，只为把 q 收进有限范围
    vec3 sx = starBox(d.yz / max(a.x, 0.35), d.x > 0.0 ? 0.0 : 1.0);
    vec3 sy = starBox(d.xz / max(a.y, 0.35), d.y > 0.0 ? 2.0 : 3.0);
    vec3 sz = starBox(d.xy / max(a.z, 0.35), d.z > 0.0 ? 4.0 : 5.0);

    return sx * w.x + sy * w.y + sz * w.z;
}

// ---------------------------------------------------------------- 主函数
void main() {
    // 与 mega_backdrop.fsh 完全一致的方向重建：近/远两点相减，抵消 bob 与投影差异
    vec4 n = RayInverse * vec4(vUv * 2.0 - 1.0, -1.0, 1.0);
    vec4 f = RayInverse * vec4(vUv * 2.0 - 1.0,  0.0, 1.0);
    vec3 dir = normalize(f.xyz / f.w - n.xyz / n.w);

    // 底色：比母版再压一档的深空
    vec3 col = vec3(0.007, 0.006, 0.011);

    // ------------------------------------------------------------ 星云
    // 大团块 / 中尺度色块 / 细纤维，全部是 dir 的纯函数
    float n1 = fbm(dir * 1.65);
    float n2 = fbm(dir * 3.90 + vec3(7.3, 1.9, 0.0));
    float n3 = fbm(dir * 8.20 + vec3(13.9, 0.0, 5.7));

    // ridged 变换：把噪声对折，拉出细长丝状结构
    float ridgeA = 1.0 - abs(n1 * 2.0 - 1.0);
    float ridgeB = 1.0 - abs(n3 * 2.0 - 1.0);
    float filament = ridgeA * 0.62 + ridgeB * 0.38;

    float dens = n1 * 0.55 + n2 * 0.30 + n3 * 0.15;
    dens = smoothstep(0.26, 0.78, dens);
    dens *= 0.45 + 0.85 * filament;

    // 暗尘：单层 value noise 就够（省一层 fbm），制造明暗对比
    float dust = smoothstep(0.40, 0.74, vnoise(dir * 2.45 + vec3(21.0, 3.0, 0.0)));
    dens *= mix(1.0, 0.14, dust);

    // 暖色梯度：暗红 → 橙 → 亮橙
    vec3 deep = vec3(0.145, 0.040, 0.030);
    vec3 mid  = vec3(0.470, 0.150, 0.048);
    vec3 hot  = vec3(0.900, 0.430, 0.120);
    vec3 neb = mix(deep, mid, smoothstep(0.18, 0.62, n2));
    neb = mix(neb, hot, pow(smoothstep(0.52, 0.94, n1), 2.0));

    col += neb * dens * NEB_GAIN;

    // 最亮的几处"烧红"核心，加一点自发光
    col += vec3(0.55, 0.20, 0.07) * pow(smoothstep(0.70, 1.0, filament * dens), 2.0) * 0.72;

    // ------------------------------------------------------------ 星点
    col += starField(dir) * STAR_GAIN;

    // ------------------------------------------------------------ 恒星日冕（解析辉光）
    // 方块的恒星本体在 §mega_cube.fsh 里发光；这里补一层连续的外晕，让它不像"方块球"。
    // SUN_AXIS = 恒星方向（作者坐标 -Z，经 build() 的 Ry(180) 后为 +Z）。
    const vec3 SUN_AXIS = vec3(0.0, 0.28735, 0.95783);
    float sunAngle = acos(clamp(dot(dir, SUN_AXIS), -1.0, 1.0));
    float corona = exp(-pow(sunAngle / 0.155, 2.0)) * (0.72 + 0.10 * sin(Time * 1.9));
    float bulge  = exp(-pow(sunAngle / 0.055, 2.0));
    col += vec3(1.00, 0.58, 0.24) * corona * 0.42;
    col += vec3(1.00, 0.82, 0.55) * bulge * 0.55;

    // ------------------------------------------------------------ 光束（解析几何，不用方块）
    // 三条束都沿**引擎轴**，不是恒星方向：
    //   THRUST_AXIS = 实测自 sky.bin：jet 质心 - 恒星质心 = (0.700,-0.526,0.482)，经 Ry(180) 取负
    // 轴向必须与 MegaGeometry 的 AXIS 一致，否则光束会画到别处（曾因此整片消失）。
    // 沿轴的深度（格，R=512）：碗口 289、喷口 446、光束末端 1419、恒星表面 72。
    const vec3 THRUST_AXIS = vec3(-0.6990, -0.5208, -0.4907);
    // 深度以**恒星中心**为原点；下方 STAR_POS 取自 sky.bin 的 star 质心（MC 坐标）。
    // 直接用 THRUST_AXIS*深度 当前置会把整束偏掉约 317 格。
    const vec3 STAR_POS = vec3(0.0, 147.1, 490.4);
    vec3 beamOrigin = STAR_POS;
    // 主推进：喷口(446) → 深空(1419)
    col += vec3(0.72, 0.90, 1.00) * beamGlow(dir, beamOrigin + THRUST_AXIS * 446.0, beamOrigin + THRUST_AXIS * 1419.0, 40.0, Time) * 1.40;
    // 能量束：碗口(289) → 恒星表面(72)，细且无湍流
    col += vec3(0.88, 0.96, 1.00) * beamGlow(dir, beamOrigin + THRUST_AXIS * 289.0, beamOrigin + THRUST_AXIS * 72.0, 9.0, Time * 0.7) * 0.85;
    // 反推束：碗口(289) → 反向(223)
    col += vec3(0.34, 0.90, 1.00) * beamGlow(dir, beamOrigin + THRUST_AXIS * 289.0, beamOrigin + THRUST_AXIS * 223.0, 8.0, Time * 1.3) * 0.38;

    fragColor = vec4(col, 1.0);
}
