#version 330
#extension GL_ARB_separate_shader_objects : require

// Adapted from Natural Motion Blur's accumulation shaders (LGPL-3.0; META-INF/licenses/LGPL-3.0-NaturalMotionBlur.txt).
// Max keeps the brighter of the new image and the faded trail; Mix blends them in linear light. The trail is stored in
// 8 bits and keeps most of itself every frame at high frame rates, so plain rounding would stop it a few steps short
// of the new image (ghosts and bands that never fade): the result is dithered by under half a step, a pattern that
// moves every frame, which rounds it toward the true value on average. An unchanged pixel stays exactly as it was.

uniform sampler2D InSampler;
uniform sampler2D PrevSampler;

layout(std140) uniform AccumulationConfig {
    float BlendFactor;
    int UseMax;
    float DitherOffset;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

// Interleaved gradient noise in [0, 1), shifted every frame.
float dither(vec2 pixel) {
    vec2 p = pixel + DitherOffset;
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

void main() {
    vec3 curr = texture(InSampler, texCoord).rgb;
    vec3 prev = texture(PrevSampler, texCoord).rgb;
    vec3 result;
    if (UseMax == 1) {
        result = max(curr, prev * BlendFactor);
    } else {
        result = sqrt(mix(curr * curr, prev * prev, BlendFactor));
    }
    // Uniform in (-0.49, 0.49) of one 8-bit step.
    float d = (dither(gl_FragCoord.xy) - 0.5) * (0.98 / 255.0);
    fragColor = vec4(clamp(result + d, 0.0, 1.0), 1.0);
}
