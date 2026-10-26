#version 150

// Horror glitch: row tearing, block corruption, RGB split, scanlines, noise and red flashes.
// Time is PostChain's 0..1 cycle (one second); everything steps 24 times per second.

uniform sampler2D DiffuseSampler;
uniform vec2 OutSize;
uniform float Time;

in vec2 texCoord;

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    float t = floor(Time * 24.0);
    vec2 uv = texCoord;

    float row = floor(uv.y * 28.0);
    if (hash(vec2(row, t)) > 0.8) {
        uv.x += (hash(vec2(row, t + 1.0)) - 0.5) * 0.15;
    }

    vec2 block = floor(uv * vec2(16.0, 9.0));
    if (hash(block + t * 1.37) > 0.965) {
        uv = fract(uv + vec2(hash(block), hash(block.yx + t)) * 0.25);
    }

    float split = 0.004 + 0.016 * hash(vec2(t, 3.0));
    float r = texture(DiffuseSampler, fract(uv + vec2(split, 0.0))).r;
    float g = texture(DiffuseSampler, uv).g;
    float b = texture(DiffuseSampler, fract(uv - vec2(split, split * 0.3))).b;
    vec3 col = vec3(r, g, b);

    col *= 0.82 + 0.18 * sin(texCoord.y * OutSize.y * 3.14159);
    col += (hash(texCoord * OutSize + t) - 0.5) * 0.18;

    if (hash(vec2(t, 7.0)) > 0.88) {
        col = vec3(col.r * 1.5, col.g * 0.35, col.b * 0.35);
    }
    if (hash(vec2(t, 11.0)) > 0.95) {
        col = vec3(1.0) - col;
    }

    fragColor = vec4(col, 1.0);
}
