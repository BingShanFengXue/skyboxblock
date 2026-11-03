#version 150
uniform sampler2D Sampler0;
uniform sampler2D WorldOpaqueDepth;
uniform sampler2D SkyCoverage;
uniform sampler2D ExposureData;
uniform sampler2D DistantDepth;
uniform int DistantDepthEnabled;
uniform mat4 WorldProjection;
uniform vec4 DistantProjectionDepth;
uniform vec2 DistantDepthNdc;
uniform vec4 SkyViewport;
uniform vec2 TextureExtent;
uniform float Gain;
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
    vec2 depthUV = gl_FragCoord.xy / TextureExtent;
    if (any(lessThan(depthUV, vec2(0.0))) || any(greaterThanEqual(depthUV, vec2(1.0)))) discard;
    vec2 provenance = texture(SkyCoverage, depthUV).rg;
    if (!(provenance.g >= 0.5)) discard;
    if (SkyMaterial >= 0 && abs(provenance.g - float(SkyMaterial+1)) > 0.25) discard;
    float opaqueDepth = texture(WorldOpaqueDepth, depthUV).r;
    if (!(abs(opaqueDepth - provenance.r) <= 1.2e-7)) discard;
    if (DistantDepthEnabled != 0) {
        float dd = texture(DistantDepth, depthUV).r;
        if (dd > 0.0 && dd < 1.0) {
            float dn = dd * DistantDepthNdc.x + DistantDepthNdc.y;
            float distantEye = -(DistantProjectionDepth.y - dn * DistantProjectionDepth.w)
                    / (dn * DistantProjectionDepth.z - DistantProjectionDepth.x);
            float wn = gl_FragCoord.z * 2.0 - 1.0;
            float skyEye = -(WorldProjection[3][2] - wn * WorldProjection[3][3])
                    / (wn * WorldProjection[2][3] - WorldProjection[2][2]);
            if (distantEye > 0.0 && distantEye + max(0.02, skyEye * 0.0001) < skyEye) discard;
        }
    }

    vec2 skyUV = (gl_FragCoord.xy - SkyViewport.xy) / SkyViewport.zw;
    if (any(lessThan(skyUV, vec2(0.0))) || any(greaterThanEqual(skyUV, vec2(1.0)))) discard;
    vec3 ownSky = skyColor(skyUV);
    // Exposure_CS stores (average scene luminance, actual exposure multiplier).
    // Final_FS and FSR apply the second channel. Divide display-linear sky by that
    // multiplier: using average luminance * 0.13 forms an amplifying metering loop
    // when sky windows fill the view (RG16F then saturates at 65504, bleaching hands).
    // No world RGB is sampled and neither exposure state nor bloom is disabled.
    float exposure = texelFetch(ExposureData, ivec2(0), 0).g;
    if (!(exposure > 0.0) || isnan(exposure) || isinf(exposure)) discard;
    fragColor = vec4(pow(max(ownSky, vec3(0.0)), vec3(2.2)) * (Gain / exposure), 0.0);
}
