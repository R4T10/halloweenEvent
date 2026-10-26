#version 150

// Halloween zone fog. Rebuilds each pixel's camera-relative position from a copy of the depth buffer and
// blends a dark, drifting, world-anchored fog over it (alpha blend, see zone_fog.json).

uniform sampler2D DepthSampler;
uniform mat4 InvViewProj;
uniform vec4 Fog;          // start, end, max alpha, drift speed (blocks per tick)
uniform vec3 ZoneFogColor;
uniform vec3 CamMod;       // camera position modulo 4096, keeps the wisps fixed in the world
uniform float GameTime;    // filled in by vanilla: day fraction, 1/24000 per tick

in vec2 texCoord;

out vec4 fragColor;

float hash3(vec3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float noise3(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash3(i), hash3(i + vec3(1, 0, 0)), f.x),
                   mix(hash3(i + vec3(0, 1, 0)), hash3(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(hash3(i + vec3(0, 0, 1)), hash3(i + vec3(1, 0, 1)), f.x),
                   mix(hash3(i + vec3(0, 1, 1)), hash3(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    vec4 h = InvViewProj * vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 p = h.xyz / h.w;
    bool sky = depth >= 0.99999;

    float base = sky ? 1.0 : smoothstep(Fog.x, Fog.y, length(p));
    vec3 w = (sky ? normalize(p) * Fog.y : p) + CamMod;
    float drift = GameTime * 24000.0 * Fog.w;
    // Two octaves of drifting noise: big rolling banks plus finer wisps.
    float banks = noise3(w * 0.09 + vec3(drift, drift * 0.2, drift * 0.5));
    float wisps = noise3(w * 0.27 + vec3(-drift * 1.4, drift * 0.4, drift));
    float density = 0.62 + 0.45 * banks + 0.2 * wisps;
    // Mist glows slightly where it is thickest.
    vec3 color = ZoneFogColor * (0.92 + 0.16 * banks);
    fragColor = vec4(color, clamp(base * Fog.z * density, 0.0, 1.0));
}
