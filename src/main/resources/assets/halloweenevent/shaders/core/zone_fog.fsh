#version 150

// Halloween zone fog. Rebuilds each pixel's camera-relative position from the depth buffer and mixes a dark,
// drifting, world-anchored fog into the scene colour (written whole; no GL blending).

uniform sampler2D DiffuseSampler;
uniform sampler2D DepthSampler;
uniform mat4 InvViewProj;
uniform vec4 Fog;          // start, end, max alpha, drift speed (blocks per tick)
uniform vec3 ZoneFogColor;
uniform vec3 CamMod;       // camera position modulo 4096, keeps the wisps fixed in the world
uniform float GameTime;    // filled in by vanilla: day fraction, 1/24000 per tick
uniform vec4 BeamPos;      // own flashlight origin (camera-relative), w = brightness (0 = off)
uniform vec4 BeamCone;     // beam direction, w = cos of the cone's outer edge
uniform float BeamRange;

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

// 0..0.55: how much of the fog the camera player's flashlight thins at this pixel. Only near you and only
// partly, so the beam shows the ground at your feet while the fog still swallows what is further out. Never the
// sky: the beam lights nothing there, and clearing the (lighter) fog in front of the night sky left a dark hole.
float beamClear(vec3 p, bool sky) {
    if (BeamPos.w <= 0.0 || sky) return 0.0;
    vec3 l = p - BeamPos.xyz;
    float d = max(length(l), 0.001);
    float c = dot(l / d, BeamCone.xyz);
    // Wide soft edge: on ground seen at a grazing angle a narrow one shows as a hard line.
    float cone = smoothstep(BeamCone.w - 0.08, mix(BeamCone.w, 1.0, 0.6), c);
    float reach = 1.0 - smoothstep(BeamRange * 0.35, BeamRange * 0.8, d);
    return 0.55 * cone * reach * BeamPos.w;
}

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    vec4 h = InvViewProj * vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 p = h.xyz / h.w;
    bool sky = depth >= 0.99999;

    // The original look: every pixel fogged as if it were sky, so the same thickness near and far, with the wisps
    // on a shell around the camera. Depth is only used for the flashlight's clearing below.
    float base = 1.0;
    vec3 w = normalize(p) * Fog.y + CamMod;
    float drift = GameTime * 24000.0 * Fog.w;
    // Two octaves of drifting noise: big rolling banks plus finer wisps.
    float banks = noise3(w * 0.09 + vec3(drift, drift * 0.2, drift * 0.5));
    float wisps = noise3(w * 0.27 + vec3(-drift * 1.4, drift * 0.4, drift));
    float density = 0.62 + 0.45 * banks + 0.2 * wisps;
    // Mist glows slightly where it is thickest.
    vec3 color = ZoneFogColor * (0.92 + 0.16 * banks);
    float alpha = clamp(base * Fog.z * density, 0.0, 1.0) * (1.0 - beamClear(p, sky));
    vec3 scene = texture(DiffuseSampler, texCoord).rgb;
    fragColor = vec4(mix(scene, color, alpha), 1.0);
}
