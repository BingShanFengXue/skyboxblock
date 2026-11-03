#version 330 core
in float EyeDistance;
uniform sampler2D Image;
uniform sampler2D Coverage;
uniform sampler2D WorldOpaqueDepth;
uniform sampler2D ExposureData;
uniform sampler2D DistantDepth;
uniform int DistantEnabled;
uniform vec4 DistantProjection;
uniform vec2 DistantNdc;
uniform mat4 RayInverse;
uniform vec2 RayDepth;
uniform vec4 Viewport;
uniform int Style;
uniform int Material;
layout(location=0) out vec4 fragColor;

vec3 ray(vec2 uv) {
    vec2 ndc=uv*2.0-1.0;
    vec4 a=RayInverse*vec4(ndc,RayDepth.x,1.0);
    vec4 b=RayInverse*vec4(ndc,RayDepth.y,1.0);
    return normalize(b.xyz/b.w-a.xyz/a.w);
}
void main() {
    vec2 uv=(gl_FragCoord.xy-Viewport.xy)/Viewport.zw;
    vec2 owner=texture(Coverage,uv).rg;
    if(abs(owner.y-float(Material+1))>0.1) discard;
    // The actual pre-translucent opaque depth, not current glass/hand depth, owns scene RGB.
    // Same audited 1.21.1 background tolerance. Never compare this overlapping triangle's depth.
    if(abs(texture(WorldOpaqueDepth,uv).r-owner.x)>1.2e-7) discard;
    if(DistantEnabled!=0) {
        float d=texture(DistantDepth,uv).r;
        float ndc=d*DistantNdc.x+DistantNdc.y;
        float denominator=ndc*DistantProjection.z-DistantProjection.x;
        float z=(DistantProjection.y-ndc*DistantProjection.w)/denominator;
        if(abs(denominator)>1e-10 && -z>0.0 && -z<EyeDistance-0.02) discard;
    }
    vec3 color;
    if(Style==1)color=vec3(1.0);
    else if(Style==2)color=vec3(0.0);
    else if(Style==3) {
        vec3 d=ray(uv);
        float u=dot(d.xz,d.xz)<1e-12 ? 0.5 : atan(d.z,d.x)/6.283185307179586+0.5;
        color=textureLod(Image,vec2(u,acos(clamp(d.y,-1.0,1.0))/3.141592653589793),0.0).rgb;
    } else color=texture(Image,uv).rgb;
    // The hash-locked Exposure_CS writes the actual multiplier in pixelData2D[0,0].g.
    // Do not use its average-luminance channel, which produces an amplifying exposure loop.
    float exposure=texelFetch(ExposureData,ivec2(0),0).g;
    if(!(exposure>0.0) || isnan(exposure) || isinf(exposure))discard;
    fragColor=vec4(pow(max(color,vec3(0.0)),vec3(2.2))/exposure,0.0);
}
