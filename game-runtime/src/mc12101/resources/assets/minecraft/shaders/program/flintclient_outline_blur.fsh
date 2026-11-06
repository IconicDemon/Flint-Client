#version 150
// Flint (Mob Overlay's outline thickness): Minecraft 1.21.1's outline blur with the radius read from a uniform (Minecraft's
// own copy fixes it at 2).

uniform sampler2D DiffuseSampler;
uniform float Radius;

in vec2 texCoord;
in vec2 sampleStep;

out vec4 fragColor;

void main() {
    vec4 blurred = vec4(0.0);
    float radius = Radius;
    for (float a = -radius + 0.5; a <= radius; a += 2.0) {
        blurred += texture(DiffuseSampler, texCoord + sampleStep * a);
    }
    blurred += texture(DiffuseSampler, texCoord + sampleStep * radius) / 2.0;
    fragColor = vec4((blurred / (radius + 0.5)).rgb, blurred.a);
}
