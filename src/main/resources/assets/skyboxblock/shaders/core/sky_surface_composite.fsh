#version 150
uniform sampler2D SurfaceColor;
uniform sampler2D NativeDepth;
uniform sampler2D NativeColor;
uniform vec4 SkyViewport;
uniform int FullCoverageOnly;
uniform int NativeBackgroundEnabled;
out vec4 fragColor;
void main() {
    vec2 uv=(gl_FragCoord.xy-SkyViewport.xy)/SkyViewport.zw;
    fragColor=texture(SurfaceColor,uv);
    if(fragColor.a<=0.0) discard;
    if(FullCoverageOnly != 0 && fragColor.a<0.99999) {
        if(NativeBackgroundEnabled==0) discard;
        float depth=texture(NativeDepth,uv).r;
        // Both conventional and reverse-Z clear values mean DH did not replace this background.
        if(!(depth>0.0 && depth<1.0)) discard;
        // DH 3.3.3's native apply pass replaces MC color with its unpolluted source, with blend/depth off.
        // Rebuild ONCE from generated premultiplied sky and that backdrop, never from already composed MC color.
        vec3 background=clamp(texture(NativeColor,uv).rgb,0.0,1.0);
        fragColor=vec4(fragColor.rgb+(1.0-fragColor.a)*background,1.0);
    }
}
