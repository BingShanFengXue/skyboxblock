#version 150

uniform vec3 StarDir;
uniform float Additive;
uniform float Emissive;
uniform float Flicker;

in vec3 vColor;
in vec3 vNormal;
in vec3 vWorld;
in float vAdditive;
flat in int vAnim;
out vec4 fragColor;

const vec3 AXIS = vec3(0.0, 0.28734746, 0.95782629);

void main() {
    vec3 n = normalize(vNormal);
    vec3 light = normalize(vec3(0.0, 0.30, -1.0));
    float face = 0.78 + 0.16 * abs(n.x) + 0.08 * abs(n.z) + 0.12 * max(n.y, 0.0);
    float lit = 0.74 + 0.30 * max(dot(n, light), 0.0) + 0.16 * max(dot(-n, light), 0.0);
    vec3 c = vColor * face * lit;
    if (Emissive > 0.5) {
        // Star body and lamps are light sources: no face shading, slight limb brightening so the
        // sphere does not read as a faceted cube ball.
        //
        // Measured against the web preview the star core read ~25-35% too large, too white and too
        // saturated, with a hard-edged halo. The size comes from the authored geometry, but the
        // glare comes from here: the core gain drops (1.10 -> 1.00), the limb term halves
        // (0.35 -> 0.18) so the falloff is gentler, and the flicker contribution is halved too.
        float toCentre = dot(n, -normalize(vWorld));
        float rim = max(toCentre, 0.0);
        c = vColor * (1.00 + 0.18 * rim) * (1.0 + Flicker * 0.06);
        // Pull the very top of the range down so the centre stops clipping to pure white; the
        // surrounding glow still reads as bright because it sits on the additive halo pass.
        c = c / (1.0 + 0.28 * max(max(c.r, c.g), c.b));
    }
    if (Additive > 0.5) {
        float alpha = vAdditive;
        if (vAnim == 2) {
            // Plume: radial falloff turns the authored slab stack into a soft, thin beam.
            float radial = length(vWorld - AXIS * dot(vWorld, AXIS));
            alpha *= exp(-radial * radial / (2.0 * 55.0 * 55.0));
        } else {
            alpha *= 1.7;
        }
        fragColor = vec4(c * alpha * 1.7, min(alpha, 0.92));
    } else {
        fragColor = vec4(c, 1.0);
    }
}
