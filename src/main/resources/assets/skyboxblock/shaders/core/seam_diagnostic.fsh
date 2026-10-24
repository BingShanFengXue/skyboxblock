#version 150
uniform sampler2D WorldDepth;
uniform vec4 SkyViewport;
uniform vec4 DepthUvTransform;
uniform int Mode;
out vec4 fragColor;
void main() {
    vec2 uv = (gl_FragCoord.xy - SkyViewport.xy) / SkyViewport.zw;
    float sceneDepth = texture(WorldDepth, uv * DepthUvTransform.xy + DepthUvTransform.zw).r;
    float hand = sceneDepth < 0.7 ? 1.0 : 0.0;
    if (Mode == 0) {
        fragColor = vec4(0.0, 0.0, hand, 1.0);
        return;
    }
    // Exact LEGACY resolve predicate. Diagnostic only: production tolerance is untouched.
    float slope = abs(dFdx(gl_FragCoord.z)) + abs(dFdy(gl_FragCoord.z));
    float tolerance = 0.000002 + slope / max(min(DepthUvTransform.x, DepthUvTransform.y), 0.01);
    float accepted = gl_FragCoord.z > sceneDepth + tolerance ? 0.0 : 1.0;
    // Caller uses MAX blending: overlapping fold faces union their accepted coverage.
    fragColor = vec4(1.0, accepted, hand, 1.0);
}
