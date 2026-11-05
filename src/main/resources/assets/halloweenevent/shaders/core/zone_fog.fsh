#version 150

// Halloween zone fog: a solid wall of dark, drifting mist over the whole screen (the look of the first version,
// where a depth bug made it opaque; here on purpose). Only flashlight beams cut through it: where a beam reaches
// a surface the mist thins and the lit world shows, fading back into the wall towards the end of the beam.
// Written whole into the scene colour (no GL blending).

#define MAX_BEAMS 4

uniform sampler2D DiffuseSampler;
uniform sampler2D DepthSampler;
uniform mat4 InvViewProj;
uniform vec4 Fog;          // start, end (radius of the wisps' shell), opacity (fades in on entering), drift speed
uniform vec3 ZoneFogColor;
uniform vec3 CamMod;       // camera position modulo 4096, keeps the wisps fixed in the world
uniform float GameTime;    // filled in by vanilla: day fraction, 1/24000 per tick
uniform float BeamCount;
uniform vec2 BeamShape;    // cos of the cone's outer edge, range
// Set directly by ZoneFogRenderer from FlashlightRenderer; camera-relative.
uniform vec4 BeamPos[MAX_BEAMS];   // origin, w = brightness (flicker, brown-outs: the wall closes in with it)
uniform vec4 BeamDir[MAX_BEAMS];

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

// 0..0.95: how far beam i opens the wall at surface point p. Soft cone edge (a narrow one shows as a hard line on
// ground seen at a grazing angle), fully open near, closing towards the end of the beam's reach.
float beamOpen(int i, vec3 p) {
    vec3 l = p - BeamPos[i].xyz;
    float d = max(length(l), 0.001);
    float c = dot(l / d, BeamDir[i].xyz);
    float cone = smoothstep(BeamShape.x - 0.07, mix(BeamShape.x, 1.0, 0.5), c);
    float reach = 1.0 - smoothstep(BeamShape.y * 0.45, BeamShape.y * 0.95, d);
    return 0.95 * cone * reach * clamp(BeamPos[i].w, 0.0, 1.0);
}

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    vec4 h = InvViewProj * vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 p = h.xyz / h.w;
    bool sky = depth >= 0.99999;

    // Wisps on a shell around the camera, so the wall looks the same near and far.
    vec3 w = normalize(p) * Fog.y + CamMod;
    float drift = GameTime * 24000.0 * Fog.w;
    // Two octaves of drifting noise: big rolling banks plus finer wisps.
    float banks = noise3(w * 0.09 + vec3(drift, drift * 0.2, drift * 0.5));
    float wisps = noise3(w * 0.27 + vec3(-drift * 1.4, drift * 0.4, drift));
    // Mist glows slightly where it is thickest.
    vec3 color = ZoneFogColor * (0.92 + 0.16 * banks + 0.06 * (wisps - 0.5));

    float open = 0.0;
    if (!sky) {
        int count = int(BeamCount + 0.5);
        for (int i = 0; i < MAX_BEAMS; i++) {
            if (i >= count) break;
            open = max(open, beamOpen(i, p));
        }
    }
    float alpha = clamp(Fog.z, 0.0, 1.0) * (1.0 - open);
    vec3 scene = texture(DiffuseSampler, texCoord).rgb;
    fragColor = vec4(mix(scene, color, alpha), 1.0);
}
