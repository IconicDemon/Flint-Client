#version 120

// A screen-covering quad given in clip space (Motion Blur's passes on Minecraft 1.8.9): no matrices involved, and the texture
// coordinates come from the position itself (immediate-mode texture coordinates follow texture-unit state that differs from
// one point of 1.8.9's frame to another on some drivers).

varying vec2 texCoord;

void main() {
    texCoord = gl_Vertex.xy * 0.5 + 0.5;
    gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0);
}
