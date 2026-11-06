#version 330
#extension GL_ARB_separate_shader_objects : require

// Flint Color Saturation: the finished world image (InSampler, a copy of the main target) with brightness,
// contrast, saturation and hue changed, or turned grey. Every setting at 0 is the image unchanged (Flint then
// skips this pass entirely). The GUI is drawn afterwards, so menus and HUD text keep their colours.

uniform sampler2D InSampler;

layout(std140) uniform ColorGradeConfig {
    float Hue;          // radians
    float Saturation;   // -1 (grey) .. 1 (twice as colourful)
    float Brightness;   // -1 .. 1, added
    float Contrast;     // -1 .. 1
    float Grayscale;    // 0 or 1
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

vec3 rotateHue(vec3 c, float angle) {
    // Rotation about the grey axis (keeps luminance close to the original).
    const vec3 k = vec3(0.57735);
    float cosA = cos(angle);
    return c * cosA + cross(k, c) * sin(angle) + k * dot(k, c) * (1.0 - cosA);
}

void main() {
    vec4 source = texture(InSampler, texCoord);
    vec3 c = source.rgb;
    c += Brightness * 0.5;
    c = (c - 0.5) * (1.0 + Contrast) + 0.5;
    float grey = dot(c, LUMA);
    c = mix(vec3(grey), c, 1.0 + Saturation);
    c = rotateHue(c, Hue);
    c = mix(c, vec3(dot(c, LUMA)), Grayscale);
    fragColor = vec4(clamp(c, 0.0, 1.0), source.a);
}
