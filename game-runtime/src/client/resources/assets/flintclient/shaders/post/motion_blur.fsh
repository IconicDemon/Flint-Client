#version 330
#extension GL_ARB_separate_shader_objects : require

// Flint motion blur: draws the accumulated image (InSampler) over the newly rendered world with the
// pipeline's blend (SRC_ALPHA, ONE_MINUS_SRC_ALPHA on colour; the target's alpha is left alone), so
// the result is Retention * previous + (1 - Retention) * current.

uniform sampler2D InSampler;

layout(std140) uniform MotionBlurConfig {
    float Retention;
    float Seed;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    vec3 previous = texture(InSampler, texCoord).rgb;
    // The blend result is rounded to 8 bits. With a high Retention a small difference between the
    // previous and current image then rounds back to itself every frame and a faint ghost never fades.
    // Up to half a step of noise (after the blend weight) makes the rounding unbiased, so the image
    // always converges; what's left is at most one 8-bit step of flicker.
    float noise = (hash12(gl_FragCoord.xy + vec2(Seed * 1.618, Seed * 2.718)) - 0.5) / (255.0 * max(Retention, 0.01));
    fragColor = vec4(previous + noise, Retention);
}
