#version 330 core
in vec2 texCoord;
in vec4 vertexColor;
in float sphericalDistance;
in float cylindricalDistance;
uniform sampler2D Image;
uniform int Mode;
uniform vec4 Tint;
uniform vec4 FogColor;
uniform vec2 FogRange;
layout(location=0) out vec4 fragColor;
float fogValue(float distance, float start, float end) {
    if(distance<=start) return 0.0;
    if(distance>=end) return 1.0;
    return (distance-start)/(end-start);
}
void main() {
    vec4 color=Tint*vertexColor;
    if(Mode==2) color*=texture(Image,texCoord);
    if(Mode==0) {
        // Exact 26.2 sky.fsh: spherical 0..FogSkyEnd, cylindrical FogSkyEnd..FogSkyEnd.
        float fog=max(fogValue(sphericalDistance,0.0,FogRange.y),
                      fogValue(cylindricalDistance,FogRange.y,FogRange.y));
        color.rgb=mix(color.rgb,FogColor.rgb,fog*FogColor.a);
    }
    if(Mode==3) {
        // Legacy texture * vertex alpha was tested before the configurable CloudOpacity.
        if(vertexColor.a*0.8<0.1) discard;
        // 1.21.1 vanilla_cloud: spherical distance and smoothstep, fading only alpha.
        float fog=FogRange.y<=FogRange.x ? step(FogRange.y,sphericalDistance)
                : smoothstep(FogRange.x,FogRange.y,sphericalDistance);
        color.a*=1.0-fog;
    }
    if(color.a<0.001) discard;
    fragColor=color;
}
