#version 150

// Adapted from Natural Motion Blur 1.4.5's velocity shaders by Pasi, with IMS212, Romain H and What42Pizza (LGPL-3.0;
// META-INF/licenses/LGPL-3.0-NaturalMotionBlur.txt): each pixel's motion since the last frame from the depth buffer and both
// frames' camera matrices, then the image sampled along it in linear light, jittered per pixel.
// Mode 0: the whole motion (velocity_f5). Mode 1: the camera's turn only (velocity_post). Mode 2: what the turn doesn't
// explain, moving through the world (velocity_pre). Flint's additions: the sample count follows the blur's length on screen
// (SampleDensity, MaxSamples: the Quality setting), a blur shorter than half a pixel draws the pixel as it is, and the depth
// convention (reversed or not, -1..1 or 0..1) comes from the version. Minecraft 1.21.1 runs it as a plain OpenGL program.

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

uniform mat4 MvInverse;
uniform mat4 ProjInverse;
uniform mat4 PrevModelView;
uniform mat4 PrevProjection;
uniform vec3 CameraDelta;
uniform float BlendFactor;
uniform vec2 ViewRes;
uniform float SampleDensity;
uniform int MaxSamples;
uniform int BlurProfile;
uniform int Mode;
uniform float FarDepth;
uniform float Reversed;
uniform vec2 DepthToNdc;
uniform float HandDistance;

in vec2 texCoord;
out vec4 fragColor;

float nearerDepth(float a, float b) {
    return Reversed > 0.5 ? max(a, b) : min(a, b);
}

vec4 viewPosition(vec2 uv, float depth) {
    return ProjInverse * vec4(uv * 2.0 - 1.0, depth * DepthToNdc.x + DepthToNdc.y, 1.0);
}

vec2 reproject(vec2 uv, float depth) {
    vec4 viewPos = viewPosition(uv, depth);
    vec3 worldPos = (MvInverse * vec4(viewPos.xyz / viewPos.w, 1.0)).xyz + CameraDelta;
    vec4 prevClip = PrevProjection * (PrevModelView * vec4(worldPos, 1.0));
    return (prevClip.xy / prevClip.w) * 0.5 + 0.5;
}

vec2 clampLength(vec2 velocity) {
    float lenSq = dot(velocity, velocity);
    return (lenSq > 0.16) ? velocity * (0.4 * inversesqrt(lenSq)) : velocity;
}

float noise(vec2 pos) {
    return fract(52.9829189 * fract(0.06711056 * pos.x + 0.00583715 * pos.y));
}

float blackmanSincWeight(float u) {
    float z = 1.89 * u;
    float sinc = abs(z) < 0.0001 ? 1.0 : sin(3.141592653589793 * z) / (3.141592653589793 * z);
    return sinc * (0.42 + 0.5 * cos(3.141592653589793 * u) + 0.08 * cos(6.28318530718 * u));
}

void main() {
    ivec2 size = textureSize(DepthSampler, 0);
    ivec2 texel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), size - 1);
    vec4 center = texelFetch(InSampler, texel, 0);
    float depth = texelFetch(DepthSampler, texel, 0).x;

    // A hand a shader pack drew into the world (Natural Motion Blur's "Iris hand fix"), or anything as close to the eye.
    vec4 here = viewPosition(texCoord, depth);
    float eyeDistance = -here.z / here.w;
    if (eyeDistance > 0.0 && eyeDistance < HandDistance) {
        fragColor = center;
        return;
    }

    // Edges: the nearest depth of the pixel and its four neighbours.
    float dilated = depth;
    dilated = nearerDepth(dilated, texelFetch(DepthSampler, clamp(texel + ivec2(1, 0), ivec2(0), size - 1), 0).x);
    dilated = nearerDepth(dilated, texelFetch(DepthSampler, clamp(texel + ivec2(-1, 0), ivec2(0), size - 1), 0).x);
    dilated = nearerDepth(dilated, texelFetch(DepthSampler, clamp(texel + ivec2(0, 1), ivec2(0), size - 1), 0).x);
    dilated = nearerDepth(dilated, texelFetch(DepthSampler, clamp(texel + ivec2(0, -1), ivec2(0), size - 1), 0).x);

    vec2 velFull = texCoord - reproject(texCoord, dilated);
    vec2 velocity;
    if (Mode == 0) {
        velocity = velFull;
    } else {
        vec2 velCamera = texCoord - reproject(texCoord, FarDepth);
        float camMag = dot(velCamera, velCamera);
        vec2 cameraComponent = camMag > 1e-12 ? velCamera * (clamp(dot(velFull, velCamera), 0.0, camMag) / camMag) : vec2(0.0);
        velocity = Mode == 1 ? cameraComponent : velFull - cameraComponent;
    }
    velocity = clampLength(velocity);
    if (any(isnan(velocity)) || any(isinf(velocity))) {
        velocity = vec2(0.0);
    }

    vec2 blur = BlendFactor * velocity;
    vec2 blurPixels = blur * ViewRes;
    if (dot(blurPixels, blurPixels) < 0.25) {
        fragColor = center;
        return;
    }
    int baseSamples = clamp(int(ceil(length(blur) * SampleDensity)), 4, MaxSamples);
    bool weighted = BlurProfile == 1;
    int samples = weighted ? clamp(int(ceil(float(baseSamples) * 1.35)), 6, max(6, int(ceil(float(MaxSamples) * 1.35)))) : baseSamples;

    vec2 step = blur * (weighted ? 3.64 * 0.96 : 1.0) / float(samples);
    float centerOffset = -float(samples) * 0.5;
    vec2 seed = texCoord * ViewRes;
    vec3 sum = vec3(0.0);
    float totalWeight = 0.0;
    // A fixed bound (the most samples there can be: High, Weighted) with an early exit: some OpenGL drivers fail on a loop
    // bound that differs from pixel to pixel.
    for (int i = 0; i < 173; i++) {
        if (i >= samples) {
            break;
        }
        float fi = float(i);
        float jitter = noise(seed + vec2(fi, fi * 1.4));
        float offset = fi + centerOffset + jitter;
        vec3 c = textureLod(InSampler, texCoord + offset * step, 0.0).rgb;
        if (weighted) {
            float weight = blackmanSincWeight(offset * 2.0 / float(samples));
            sum += pow(max(c, vec3(0.0)), vec3(2.2)) * weight;
            totalWeight += weight;
        } else {
            sum += c * c;
            totalWeight += 1.0;
        }
    }
    if (totalWeight <= 0.0001) {
        fragColor = center;
    } else if (weighted) {
        fragColor = vec4(pow(clamp(sum / totalWeight, vec3(0.0), vec3(1.0)), vec3(1.0 / 2.2)), 1.0);
    } else {
        fragColor = vec4(sqrt(sum / totalWeight), 1.0);
    }
}
