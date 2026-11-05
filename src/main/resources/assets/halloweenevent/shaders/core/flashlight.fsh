#version 150

// Flashlight beams. Rebuilds each pixel's camera-relative position from a depth copy, lights it from up to
// four spot lights and writes the lit scene (no blending: DiffuseSampler is a copy of the scene).

#define MAX_LIGHTS 4

uniform sampler2D DiffuseSampler;
uniform sampler2D DepthSampler;
uniform mat4 InvViewProj;
uniform vec4 Beam;          // range, cos inner, cos outer, (unused)
uniform vec3 BeamColor;
uniform vec3 Strength;      // gain (times surface colour), lift (black surfaces), (unused)
uniform float Ambient;      // world brightness at the camera, 0.03..1; shaded colour / Ambient ~ surface colour
uniform float Spill;        // cos of the faint outer spill angle
uniform float DebugView;    // 0 off, 1 block grid, 2 light amount, 3 distance
uniform vec3 CamMod;        // camera position modulo 4096 (debug grid only)
uniform float LightCount;
// Set directly by FlashlightRenderer; camera-relative.
uniform vec4 LightPos[MAX_LIGHTS];  // xyz origin, w intensity (flicker)
uniform vec4 LightDir[MAX_LIGHTS];  // xyz direction

in vec2 texCoord;

out vec4 fragColor;

vec3 viewPos(vec2 uv, float depth) {
    vec4 p = InvViewProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

// Cone and distance falloff of light i at point p, without surface orientation.
float spot(int i, vec3 p, out vec3 toPoint) {
    vec3 l = p - LightPos[i].xyz;
    float d = max(length(l), 0.001);
    toPoint = l / d;
    float c = dot(toPoint, LightDir[i].xyz);
    float cone = smoothstep(Beam.z, Beam.y, c);
    // Brighter core, soft dim ring towards the edge, then a faint wide spill around the cone.
    cone *= 0.7 + 0.3 * smoothstep(Beam.y, 0.999, c);
    cone += 0.1 * smoothstep(Spill, Beam.z, c) * (1.0 - cone);
    float fall = clamp(1.0 - d / Beam.x, 0.0, 1.0);
    return cone * fall * fall * LightPos[i].w;
}

void main() {
    vec4 scene = texture(DiffuseSampler, texCoord);
    float depth = texture(DepthSampler, texCoord).r;
    vec3 p = viewPos(texCoord, depth);
    bool sky = depth >= 1.0;
    int count = int(LightCount + 0.5);

    // Flat-shaded normal from screen-space derivatives, facing the camera.
    vec3 n = normalize(cross(dFdx(p), dFdy(p)));
    if (dot(n, p) > 0.0) n = -n;

    float surface = 0.0;
    if (!sky) {
        for (int i = 0; i < MAX_LIGHTS; i++) {
            if (i >= count) break;
            vec3 toPoint;
            float s = spot(i, p, toPoint);
            if (s <= 0.0) continue;
            float facing = max(dot(n, -toPoint), 0.0);
            // Mild facing term: walls the beam hits head-on should not pop out as hard bright rectangles.
            surface += s * (0.5 + 0.5 * facing);
        }
    }

    // No light scattered in the air: its edge (where view rays leave the beam's volume) showed as hard lines
    // fanning out from the hand, and it cost 40 extra light evaluations per pixel.

    // Light the surface colour, not the (at night nearly black) shaded colour.
    vec3 base = scene.rgb;
    vec3 albedo = min(base / max(Ambient, 0.03), vec3(1.0));
    vec3 lit = base + BeamColor * surface * (Strength.x * albedo + Strength.y);
    fragColor = vec4(min(lit, vec3(1.0)), 1.0);

    // Debug views (flashlight debug key), to see what the pass computes at every pixel.
    int view = int(DebugView + 0.5);
    if (view == 1) {
        // Rebuilt world position: grid lines must sit exactly on block edges and stay put while turning.
        vec3 w = p + CamMod;
        // A block edge has two coordinates on a whole number (one of them is the face itself).
        vec3 e = step(vec3(0.46), abs(fract(w) - 0.5));
        float line = sky ? 0.0 : step(2.0, e.x + e.y + e.z);
        fragColor = vec4(mix(base * 0.4 + fract(floor(w) * 0.37) * 0.15, vec3(1.0, 0.25, 0.2), line), 1.0);
    } else if (view == 2) {
        float inCone = (!sky && count > 0 && dot(normalize(p - LightPos[0].xyz), LightDir[0].xyz) > Beam.z) ? 1.0 : 0.0;
        fragColor = vec4(vec3(surface) + vec3(0.25, 0.0, 0.0) * inCone, 1.0);
    } else if (view == 3) {
        fragColor = sky ? vec4(0.1, 0.2, 0.5, 1.0) : vec4(vec3(clamp(length(p) / Beam.x, 0.0, 1.0)), 1.0);
    }
}
