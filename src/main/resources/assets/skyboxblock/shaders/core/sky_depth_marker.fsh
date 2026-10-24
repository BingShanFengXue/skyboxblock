#version 150
uniform vec4 SkyViewport;
uniform int SkyMaterial;
out vec4 fragColor;
void main() {
    vec2 uv=(gl_FragCoord.xy-SkyViewport.xy)/SkyViewport.zw;
    if (any(lessThan(uv,vec2(0.0))) || any(greaterThanEqual(uv,vec2(1.0)))) discard;
    fragColor=vec4(gl_FragCoord.z,float(SkyMaterial+1),0.0,1.0);
}
