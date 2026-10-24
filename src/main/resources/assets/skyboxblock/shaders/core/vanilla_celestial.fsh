#version 150
uniform sampler2D Sampler0;
uniform vec4 DrawColor;
in vec2 celestialUv;
out vec4 fragColor;
void main() {
    fragColor = texture(Sampler0, celestialUv) * DrawColor;
}
