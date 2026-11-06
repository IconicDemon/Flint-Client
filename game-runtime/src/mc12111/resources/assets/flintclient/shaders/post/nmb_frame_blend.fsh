#version 330

// Adapted from Natural Motion Blur's frame blending shader (LGPL-3.0; META-INF/licenses/LGPL-3.0-NaturalMotionBlur.txt):
// the frames rendered within the exposure time, each weighted by how much of it falls inside, averaged in linear light.

uniform sampler2D Sample0Sampler;
uniform sampler2D Sample1Sampler;
uniform sampler2D Sample2Sampler;
uniform sampler2D Sample3Sampler;
uniform sampler2D Sample4Sampler;
uniform sampler2D Sample5Sampler;
uniform sampler2D Sample6Sampler;
uniform sampler2D Sample7Sampler;
uniform sampler2D Sample8Sampler;
uniform sampler2D Sample9Sampler;
uniform sampler2D Sample10Sampler;
uniform sampler2D Sample11Sampler;

layout(std140) uniform FrameBlendConfig {
    vec4 Weights0;
    vec4 Weights1;
    vec4 Weights2;
    float InvTotalWeight;
    int ActiveCount;
};

in vec2 texCoord;
out vec4 fragColor;

vec3 lin(sampler2D s) {
    vec3 c = texture(s, texCoord).rgb;
    return c * c;
}

void main() {
    vec3 sum = lin(Sample0Sampler) * Weights0.x;
    if (ActiveCount > 1) sum += lin(Sample1Sampler) * Weights0.y;
    if (ActiveCount > 2) sum += lin(Sample2Sampler) * Weights0.z;
    if (ActiveCount > 3) sum += lin(Sample3Sampler) * Weights0.w;
    if (ActiveCount > 4) sum += lin(Sample4Sampler) * Weights1.x;
    if (ActiveCount > 5) sum += lin(Sample5Sampler) * Weights1.y;
    if (ActiveCount > 6) sum += lin(Sample6Sampler) * Weights1.z;
    if (ActiveCount > 7) sum += lin(Sample7Sampler) * Weights1.w;
    if (ActiveCount > 8) sum += lin(Sample8Sampler) * Weights2.x;
    if (ActiveCount > 9) sum += lin(Sample9Sampler) * Weights2.y;
    if (ActiveCount > 10) sum += lin(Sample10Sampler) * Weights2.z;
    if (ActiveCount > 11) sum += lin(Sample11Sampler) * Weights2.w;
    fragColor = vec4(sqrt(max(sum * InvTotalWeight, vec3(0.0))), 1.0);
}
