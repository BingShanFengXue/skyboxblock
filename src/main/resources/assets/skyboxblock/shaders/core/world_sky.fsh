#version 150
uniform sampler2D Sampler0;
uniform sampler2D DistantDepth;
uniform int DistantDepthEnabled;
uniform mat4 WorldProjection;
uniform vec4 DistantProjectionDepth;
uniform vec2 DistantDepthNdc;
uniform vec2 TextureExtent;
uniform vec4 SkyViewport;
uniform int PackMode;
uniform float NormalSign;
in vec3 SkyWorldPosition;
out vec4 fragColor;
out vec4 SkySolidData;
out vec4 SkyNormalData;
uniform int SkyStyle;
uniform mat4 PanoramaRayInverse;
uniform int PanoramaLegacyRays;
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

// Octahedral encoding used by the hash-locked ITRP adapter. Canonical positive zero is valid at the pole.
vec2 skyOctNormal(vec3 n) {
    n.xy /= max(abs(n.x)+abs(n.y)+abs(n.z),0.00000001);
    if (n.z < 0.0) {
        vec2 signs=vec2(n.x>=0.0 ? 1.0 : -1.0,n.y>=0.0 ? 1.0 : -1.0);
        n.xy=(1.0-abs(n.yx))*signs;
    }
    return n.xy*0.5+0.5;
}
void main() {
    vec2 uv=(gl_FragCoord.xy-SkyViewport.xy)/SkyViewport.zw;
    if (any(lessThan(uv,vec2(0.0))) || any(greaterThanEqual(uv,vec2(1.0)))) discard;
    if (DistantDepthEnabled != 0) {
        float dd = texture(DistantDepth, gl_FragCoord.xy / TextureExtent).r;
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
    fragColor=vec4(skyColor(uv),1.0);
    SkySolidData=vec4(0.0);
    SkyNormalData=vec4(0.0);
    if (PackMode==1) {
        vec3 n=cross(dFdx(SkyWorldPosition),dFdy(SkyWorldPosition));
        n=dot(n,n)>1e-30 ? normalize(n)*NormalSign : vec3(0.0,1.0,0.0);
        vec2 oct=skyOctNormal(n);
        // Pack2xU8: spec/emission=0; parallax=1 and official MATID_LAND=1; blocklight=0/skylight=1.
        // There is no invented/reserved material ID and no old behind-world normal/lightmap left here.
        SkySolidData=vec4(0.0,0.0,65281.0/65535.0,255.0/65535.0);
        SkyNormalData=vec4(oct,oct);
    }
}
