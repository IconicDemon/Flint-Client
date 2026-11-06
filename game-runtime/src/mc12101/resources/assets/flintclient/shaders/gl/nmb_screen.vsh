#version 150

// One triangle that covers the screen (no vertex buffer: positions from the vertex index), for Motion Blur's passes.

out vec2 texCoord;

void main() {
    vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    texCoord = corner;
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
}
