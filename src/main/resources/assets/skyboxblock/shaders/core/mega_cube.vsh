#version 150

in vec3 Position;
in vec3 Normal;
in vec3 Center;
in vec4 Color;

uniform mat4 Mvp;
uniform float Time;
uniform float RingAngle;
uniform int Anim;
// Centre of the Dyson ring, in the same world space as Position. The original port rotated about a
// line through the world origin instead, and because the ring sits ~490 blocks away from that line
// the whole swarm swung around in a big circle instead of turning in its own plane.
uniform vec3 Pivot;

out vec3 vColor;
out vec3 vNormal;
out vec3 vWorld;
out float vAdditive;
flat out int vAnim;

// 引擎轴（不是恒星方向）：normalize(0.70,-0.36,-0.62) 经 Ry(180) 得到。
// 戴森云的自转轴与喷流方向都必须用它，否则环会绕错误的轴转。
const vec3 AXIS = vec3(-0.6990, -0.5208, -0.4907);

vec3 spin(vec3 p, float a) {
    float c = cos(a), s = sin(a);
    return p * c + cross(AXIS, p) * s + AXIS * dot(AXIS, p) * (1.0 - c);
}

void main() {
    vec3 p = Position;
    if (Anim == 1) {
        // Dyson swarm: orbital spin about the structure axis, taken through the ring centre so the
        // ring turns in its own plane instead of orbiting the world origin.
        p = Pivot + spin(p - Pivot, RingAngle);
    } else if (Anim == 2) {
        // Plume: each particle carries its own phase and travels outward along the axis, so
        // nothing appears to stream out of the star and through the engine.
        float h = fract(sin(dot(Center, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
        float phase = fract(Time * 0.16 + h);
        p += AXIS * (phase - 0.5) * 300.0;
    } else if (Anim == 3) {
        // Corona / halo breathes around the star, also anchored on the ring centre.
        p = Pivot + (p - Pivot) * (1.0 + 0.035 * sin(Time * 1.7 + Center.x * 0.01));
    }
    gl_Position = Mvp * vec4(p, 1.0);
    vNormal = Anim == 1 ? spin(Normal, RingAngle) : Normal;
    vWorld = p;
    vColor = Color.rgb;
    vAdditive = Color.a;
    vAnim = Anim;
}
