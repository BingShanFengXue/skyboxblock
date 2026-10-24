#version 150
uniform sampler2D Sampler0;
uniform sampler2D WorldDepth;
uniform sampler2D SkyCoverage;
uniform sampler2D DistantDepth;
uniform sampler2D ForegroundHistory;
uniform int ForegroundHistoryEnabled;
uniform int CoverageEnabled;
uniform int SurfaceLayer;
uniform int DistantDepthEnabled;
uniform int ForegroundEdgeGuard;
uniform mat4 WorldProjection;
uniform vec4 DistantProjectionDepth;
uniform vec2 DistantDepthNdc;
uniform vec4 SkyViewport;
uniform vec4 DepthUvTransform;
out vec4 fragColor;
in vec3 SkyWorldPosition;
uniform int SkyStyle;
uniform mat4 PanoramaRayInverse;
uniform int PanoramaLegacyRays;
uniform int SkyMaterial;
vec3 skyColor(vec2 uv) {
    if (SkyStyle == 1) return vec3(1.0);
    if (SkyStyle == 2) return vec3(0.0);
    if (SkyStyle == 3) {
        // One direction per pixel; subtract points to cancel bob in EITHER projection or view.
        vec4 rayNear = PanoramaRayInverse * vec4(uv * 2.0 - 1.0, -1.0, 1.0);
        vec4 rayFar = PanoramaRayInverse * vec4(uv * 2.0 - 1.0, 0.0, 1.0);
        vec3 ray = PanoramaLegacyRays != 0 ? normalize(SkyWorldPosition)
                : normalize(rayFar.xyz / rayFar.w - rayNear.xyz / rayNear.w);
        float u = dot(ray.xz, ray.xz) < 1e-12 ? 0.5 : atan(ray.z, ray.x) / 6.28318530718 + 0.5;
        float v = acos(clamp(ray.y, -1.0, 1.0)) / 3.14159265359;
        return textureLod(Sampler0, vec2(u, v), 0.0).rgb;
    }
    return texture(Sampler0, uv).rgb;
}
void main() {
    vec2 uv = (gl_FragCoord.xy - SkyViewport.xy) / SkyViewport.zw;
    vec2 depthUv = uv * DepthUvTransform.xy + DepthUvTransform.zw;
    float sceneDepth = texture(WorldDepth, depthUv).r;
    vec2 materialOwner = texture(SkyCoverage, depthUv).rg;
    // Different sky styles can overlap in depth. A rear batch must never repaint the front material.
    // Private display-resolution depth resolves front/rear ownership per sample.
    // The jittered low-resolution ID remains provenance, never a display boundary.
    if (SurfaceLayer == 0 && CoverageEnabled != 0 && materialOwner.g >= 0.5 && SkyMaterial >= 0
            && abs(materialOwner.g - float(SkyMaterial+1)) > 0.25) discard;
    // A low-resolution depth texel can lie partway along the same sloping cube face.
    // Cover its quantization error, not the macroscopic separation from a foreground pillar/hand.
    vec2 zGradient = vec2(dFdx(gl_FragCoord.z), dFdy(gl_FragCoord.z));
    float slope = abs(zGradient.x) + abs(zGradient.y);
    float tolerance = 0.000002 + slope / max(min(DepthUvTransform.x, DepthUvTransform.y), 0.01);
    if (gl_FragCoord.z > sceneDepth + tolerance) {
        // At a folded sky surface the low-res sample may belong to ANOTHER sky face.
        // Accept only its exact early sky-depth fingerprint; any later foreground depth still wins.
        vec2 ownSky = texture(SkyCoverage, depthUv).rg;
        if (CoverageEnabled == 0 || ownSky.y < 0.5 || abs(sceneDepth - ownSky.x) > 0.00000012) discard;
    }
    if (ForegroundHistoryEnabled != 0 && texture(ForegroundHistory, uv).g >= 0.5) discard;
    if (ForegroundEdgeGuard != 0 && ForegroundHistoryEnabled == 0) {
        // FSR/TAA has already stabilized the final glass/hand edge. A single raw
        // jittered low-res depth sample must not overwrite that reconstructed fringe.
        vec2 stepUv = 1.0 / vec2(textureSize(WorldDepth, 0));
        for (int gy = -1; gy <= 1; ++gy) for (int gx = -1; gx <= 1; ++gx) {
            vec2 tap = depthUv + vec2(gx, gy) * stepUv;
            if (any(lessThan(tap, DepthUvTransform.zw)) || any(greaterThanEqual(tap, DepthUvTransform.zw + DepthUvTransform.xy))) continue;
            float tapDepth = texture(WorldDepth, tap).r;
            float faceDepth = gl_FragCoord.z + dot(zGradient, vec2(gx, gy) / max(DepthUvTransform.xy, vec2(0.01)));
            if (faceDepth > tapDepth + tolerance) {
                vec2 ownSky = texture(SkyCoverage, tap).rg;
                if (CoverageEnabled == 0 || ownSky.y < 0.5 || abs(tapDepth - ownSky.x) > 0.00000012) discard;
            }
        }
    }
    if (DistantDepthEnabled != 0) {
        float distantDepth = texture(DistantDepth, depthUv).r;
        if (distantDepth > 0.0 && distantDepth < 1.0) {
            // DH may use reverse-Z and a different near/far plane. Compare eye-space
            // distances, never raw values from two unrelated projection matrices.
            float dn = distantDepth * DistantDepthNdc.x + DistantDepthNdc.y;
            float distantEye = -(DistantProjectionDepth.y - dn * DistantProjectionDepth.w)
                    / (dn * DistantProjectionDepth.z - DistantProjectionDepth.x);
            float wn = gl_FragCoord.z * 2.0 - 1.0;
            float skyEye = -(WorldProjection[3][2] - wn * WorldProjection[3][3])
                    / (wn * WorldProjection[2][3] - WorldProjection[2][2]);
            float eyeTolerance = max(0.02, skyEye * 0.0001);
            if (distantEye > 0.0 && distantEye + eyeTolerance < skyEye) discard;
        }
    }
    fragColor = vec4(skyColor(uv), 1.0);
}
