#version 120

// Adapted from Natural Motion Blur 1.4.5's velocity shaders by Pasi, with IMS212, Romain H and What42Pizza (LGPL-3.0;
// META-INF/licenses/LGPL-3.0-NaturalMotionBlur.txt): each pixel's motion since the last frame from the depth buffer and both
// frames' camera matrices, then the image sampled along it in linear light, jittered per pixel.
// Mode 0: the whole motion (velocity_f5). Mode 1: the camera's turn only (velocity_post). Mode 2: what the turn doesn't
// explain, moving through the world (velocity_pre). The same shader as on the newer lines, in GLSL 1.20 for Minecraft 1.8.9's
// OpenGL 2.1: texels are read at their centres instead of texelFetch, and NaN is caught by comparing a value with itself.

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

varying vec2 texCoord;

float nearerDepth(float a, float b) {
    return Reversed > 0.5 ? max(a, b) : min(a, b);
}

float depthAt(vec2 pixel) {
    return texture2D(DepthSampler, (clamp(pixel, vec2(0.0), ViewRes - 1.0) + 0.5) / ViewRes).x;
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
    vec2 pixel = floor(gl_FragCoord.xy);
    vec4 center = texture2D(InSampler, (pixel + 0.5) / ViewRes);
    float depth = depthAt(pixel);

    // Anything this close to the eye stays sharp (a hand drawn into the world).
    vec4 here = viewPosition(texCoord, depth);
    float eyeDistance = -here.z / here.w;
    if (eyeDistance > 0.0 && eyeDistance < HandDistance) {
        gl_FragColor = center;
        return;
    }

    // Edges: the nearest depth of the pixel and its four neighbours.
    float dilated = depth;
    dilated = nearerDepth(dilated, depthAt(pixel + vec2(1.0, 0.0)));
    dilated = nearerDepth(dilated, depthAt(pixel + vec2(-1.0, 0.0)));
    dilated = nearerDepth(dilated, depthAt(pixel + vec2(0.0, 1.0)));
    dilated = nearerDepth(dilated, depthAt(pixel + vec2(0.0, -1.0)));

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
    if (velocity.x != velocity.x || velocity.y != velocity.y || abs(velocity.x) > 1e20 || abs(velocity.y) > 1e20) {
        velocity = vec2(0.0);
    }

    vec2 blur = BlendFactor * velocity;
    vec2 blurPixels = blur * ViewRes;
    if (dot(blurPixels, blurPixels) < 0.25) {
        gl_FragColor = center;
        return;
    }
    float baseSamples = clamp(ceil(length(blur) * SampleDensity), 4.0, float(MaxSamples));
    bool weighted = BlurProfile == 1;
    int samples = int(weighted ? clamp(ceil(baseSamples * 1.35), 6.0, max(6.0, ceil(float(MaxSamples) * 1.35))) : baseSamples);

    vec2 step = blur * (weighted ? 3.64 * 0.96 : 1.0) / float(samples);
    float centerOffset = -float(samples) * 0.5;
    vec2 seed = texCoord * ViewRes;
    vec3 sum = vec3(0.0);
    float totalWeight = 0.0;
    for (int i = 0; i < 173; i++) {
        if (i >= samples) {
            break;
        }
        float fi = float(i);
        float jitter = noise(seed + vec2(fi, fi * 1.4));
        float offset = fi + centerOffset + jitter;
        vec3 c = texture2D(InSampler, texCoord + offset * step).rgb;
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
        gl_FragColor = center;
    } else if (weighted) {
        gl_FragColor = vec4(pow(clamp(sum / totalWeight, vec3(0.0), vec3(1.0)), vec3(1.0 / 2.2)), 1.0);
    } else {
        gl_FragColor = vec4(sqrt(sum / totalWeight), 1.0);
    }
}
