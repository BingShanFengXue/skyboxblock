#version 150
uniform sampler2D WorldDepth;
uniform sampler2D SkyCoverage;
uniform sampler2D HistoryMask;
uniform int CoverageEnabled;
uniform int FrameStamp;
uniform float HistoryFrames;
uniform vec4 SkyViewport;
uniform vec4 DepthUvTransform;
out vec2 fragGuard;

void main() {
    vec2 uv = (gl_FragCoord.xy - SkyViewport.xy) / SkyViewport.zw;
    vec2 depthUv = uv * DepthUvTransform.xy + DepthUvTransform.zw;
    vec2 stepUv = 1.0 / vec2(textureSize(WorldDepth, 0));
    vec2 dz = vec2(dFdx(gl_FragCoord.z), dFdy(gl_FragCoord.z));
    float tolerance = 0.000002 + (abs(dz.x) + abs(dz.y)) / max(min(DepthUvTransform.x, DepthUvTransform.y), 0.01);
    bool foreground = false;
    for (int gy = -1; gy <= 1; ++gy) for (int gx = -1; gx <= 1; ++gx) {
        vec2 tap = depthUv + vec2(gx, gy) * stepUv;
        if (any(lessThan(tap, DepthUvTransform.zw)) || any(greaterThanEqual(tap, DepthUvTransform.zw + DepthUvTransform.xy))) continue;
        float scene = texture(WorldDepth, tap).r;
        float face = gl_FragCoord.z + dot(dz, vec2(gx, gy) / max(DepthUvTransform.xy, vec2(0.01)));
        if (face > scene + tolerance) {
            vec2 ownSky = texture(SkyCoverage, tap).rg;
            if (CoverageEnabled == 0 || ownSky.y < 0.5 || abs(scene - ownSky.x) > 0.00000012) foreground = true;
        }
    }
    vec2 old = texture(HistoryMask, uv).rg;
    float age = mod(float(FrameStamp) - old.x + 65536.0, 65536.0);
    if (foreground) fragGuard = vec2(float(FrameStamp), 1.0);
    else fragGuard = vec2(old.x, old.y >= 0.5 && age <= HistoryFrames ? 1.0 : 0.0);
}
