#version 330 core
uniform sampler2D MeterAtlas;
uniform sampler2D GeneratedSky;
uniform sampler2D SkyCoverage;
uniform sampler2D WorldOpaqueDepth;
uniform sampler2D ExposureData;
uniform vec2 TextureExtent;
uniform vec2 MeterOffset;
uniform vec4 CoverageTransform;
layout(location=0) out vec4 fragColor;
void main() {
    vec2 uv=gl_FragCoord.xy/TextureExtent;
    vec3 original=textureLod(MeterAtlas,uv,0.0).rgb;
    vec2 tile=(uv-MeterOffset)*64.0;
    float exposure=texelFetch(ExposureData,ivec2(0),0).g;
    if(any(lessThan(tile,vec2(0))) || any(greaterThan(tile,vec2(1)))
            || !(exposure>0.0) || isnan(exposure) || isinf(exposure)) {
        fragColor=vec4(original,1);return;
    }
    float reference=0.0;
    // Same 1.21.1 8x8 approximation of the real 32x16 exposure cell.
    for(int y=0;y<8;y++) for(int x=0;x<8;x++) {
        vec2 q=clamp(tile+(vec2(x,y)-vec2(3.5))/vec2(256,128),vec2(0),vec2(1));
        vec2 dq=q*CoverageTransform.xy+CoverageTransform.zw;
        if(any(lessThan(dq,vec2(0))) || any(greaterThan(dq,vec2(1))))continue;
        vec2 own=texture(SkyCoverage,dq).rg;
        // Coverage is material+1; black / galaxy / apocalypse, not other panoramas or custom skins.
        bool dark=abs(own.g-3.0)<0.25 || abs(own.g-7.0)<0.25 || abs(own.g-9.0)<0.25;
        if(!dark || abs(texture(WorldOpaqueDepth,dq).r-own.r)>1.2e-7)continue;
        // 26.2's private generated canvas uses rasterProjection, so native q maps to dq here too.
        // It is NEVER the artistic black/star/dusk display texture.
        vec3 physicalSky=pow(max(texture(GeneratedSky,dq).rgb,vec3(0)),vec3(2.2));
        float luminance=max(dot(physicalSky,vec3(.2126,.7152,.0722)),.18);
        reference+=min(luminance/exposure,2048.0)/64.0;
    }
    float actual=dot(original,vec3(.2126,.7152,.0722));
    // Meter-only 18% reference; scene/bloom RGB and the original exposure image stay untouched.
    fragColor=vec4(original+vec3(max(reference-actual,0.0)),1.0);
}
