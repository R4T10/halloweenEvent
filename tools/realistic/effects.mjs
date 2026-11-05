// Screen-space effect textures: mist, vignette, static, the clawed hand and the eyes.
// Sizes must match the blit() calls (validate_mod.mjs checks them):
//   mist 256, vignette 256, static 64, screen_hand 512, eye_sclera 256x128, eye_iris 128,
//   eye_lids 256x512 (four 256x128 frames: closed, 35%, 70%, open), eye_spiral 256.
import { Image, clamp, mix, mix3, mul3, add3, smoothstep, fbm, ridged, noise, hash2, norm3, dot3, gauss } from '../lib/gfx.mjs';

const TAU = Math.PI * 2;

// ---------------------------------------------------------------- mist (tileable)
function mist(file) {
  const N = 256, P = 4;
  const img = new Image(N, N);
  for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
    const x = i / N * P, y = j / N * P;
    const wx = fbm(x + 5.2, y + 1.3, { seed: 1, period: P, oct: 3 }) - 0.5;
    const wy = fbm(x + 8.1, y + 2.8, { seed: 2, period: P, oct: 3 }) - 0.5;
    const d = fbm(x + wx * 1.6, y + wy * 1.6, { seed: 3, period: P, oct: 5 });
    const fil = smoothstep(0.80, 1.0, ridged(x * 1.5 + wy, y * 1.5 + wx, { seed: 4, period: P * 1.5 | 0 || 6, oct: 3 }));
    const a = clamp(smoothstep(0.38, 0.80, d) * 0.88 + fil * 0.10);
    const c = mix3([0.60, 0.62, 0.67], [0.93, 0.94, 0.96], smoothstep(0.45, 0.9, d));
    const grain = 0.97 + 0.06 * noise(i * 0.5, j * 0.5, 5, N / 2);
    img.set(i, j, c[0] * grain, c[1] * grain, c[2] * grain, a);
  }
  img.save(file);
}

// ---------------------------------------------------------------- vignette
function vignette(file) {
  const N = 256;
  const img = new Image(N, N);
  for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
    const x = (i + 0.5) / N * 2 - 1, y = (j + 0.5) / N * 2 - 1;
    const wobble = (fbm(x * 2 + 4, y * 2, { seed: 6, oct: 3 }) - 0.5) * 0.18;
    const r = Math.hypot(x * 0.96, y) + wobble;
    img.set(i, j, 0, 0, 0, smoothstep(0.30, 1.18, r) ** 1.35);
  }
  img.save(file);
}

// ---------------------------------------------------------------- analog static (64x64, opaque grey)
function analogStatic(file) {
  const N = 64;
  const img = new Image(N, N);
  for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
    const v = 0.55 * hash2(i, j, 31) + 0.30 * hash2(i >> 2, j, 32) + 0.15 * hash2(i >> 4, j >> 1, 33);
    const line = 0.9 + 0.1 * Math.sin(j * 1.9);
    const c = clamp(v * line);
    img.set(i, j, c, c, c, 1);
  }
  img.save(file, { opaque: true });
}

// ---------------------------------------------------------------- the clawed hand (512, wrist on the left)
// Every part is one continuous tapered tube (no ball joints): the closest point on the polyline gives the
// surface normal, so lighting flows smoothly along each finger.
function tubeSample(pts, radii, x, y) {
  let best = null, cum = 0;
  for (let k = 0; k < pts.length - 1; k++) {
    const [ax, ay] = pts[k], [bx, by] = pts[k + 1];
    const dx = bx - ax, dy = by - ay, len = Math.hypot(dx, dy);
    const t = clamp(((x - ax) * dx + (y - ay) * dy) / (len * len));
    const px = ax + dx * t, py = ay + dy * t, r = mix(radii[k], radii[k + 1], t);
    const q = Math.hypot(x - px, y - py) / r;
    if (!best || q < best.q) best = { q, px, py, r, s: cum + t * len, t: (k + t) / (pts.length - 1) };
    cum += len;
  }
  return best;
}

function hand(file) {
  const N = 512;
  const img = new Image(N, N);
  const tubes = [];
  tubes.push({ kind: 'arm', pts: [[-60, 396], [80, 334], [190, 286], [262, 250]], radii: [70, 58, 50, 60] });
  tubes.push({ kind: 'arm', pts: [[214, 296], [268, 236]], radii: [56, 54] });         // back of the hand
  const fingers = [
    [[262, 196], [330, 146], [394, 134], [446, 168]],
    [[276, 232], [356, 198], [424, 198], [474, 238]],
    [[274, 268], [354, 258], [418, 276], [458, 316]],
    [[258, 302], [326, 322], [374, 352], [402, 392]],
    [[206, 336], [244, 392], [296, 416], [338, 406]],                                  // thumb
  ];
  for (const p of fingers) {
    tubes.push({ kind: 'finger', pts: p, radii: [17, 16, 12.5, 8.5] });
    // a curved claw continues the last bone
    const [x2, y2] = p[2], [x3, y3] = p[3];
    const len = Math.hypot(x3 - x2, y3 - y2), dx = (x3 - x2) / len, dy = (y3 - y2) / len;
    const px = -dy, py = dx;                                                            // perpendicular, towards +y
    const sgn = py >= 0 ? 1 : -1;
    tubes.push({ kind: 'claw', pts: [[x3, y3], [x3 + dx * 18 + px * sgn * 3, y3 + dy * 18 + py * sgn * 3],
      [x3 + dx * 40 + px * sgn * 14, y3 + dy * 40 + py * sgn * 14], [x3 + dx * 52 + px * sgn * 26, y3 + dy * 52 + py * sgn * 26]],
      radii: [8.5, 6.5, 3.2, 0.4] });
  }
  const L = norm3([-0.5, -0.62, 0.6]);
  const skinA = [0.13, 0.11, 0.115], skinB = [0.055, 0.045, 0.055];

  for (const tube of tubes) {
    for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
      const sm = tubeSample(tube.pts, tube.radii, i, j);
      if (sm.q > 1.04) continue;
      const cover = smoothstep(1.04, 0.97, sm.q);
      const nx = (i - sm.px) / sm.r, ny = (j - sm.py) / sm.r, nz = Math.sqrt(Math.max(0, 1 - nx * nx - ny * ny));
      const n = norm3([nx, ny, nz]);
      const diff = clamp(dot3(n, L) * 0.85 + 0.15);
      const hv = norm3(add3(L, [0, 0, 1]));
      let lit;
      if (tube.kind === 'claw') {
        // dark horn, red at the point, with a long glossy highlight
        let c = mix3([0.30, 0.22, 0.14], [0.26, 0.02, 0.03], smoothstep(0.35, 0.95, sm.t));
        c = mul3(c, 0.9 + 0.2 * noise(sm.s * 0.4, nx * 3, 55));
        lit = mul3(c, 0.2 + 1.5 * diff);
        const spec = Math.pow(clamp(dot3(n, hv)), 18) * 0.55;
        lit = add3(lit, [spec, spec, spec * 0.9]);
      } else {
        let c = mix3(skinA, skinB, fbm(i * 0.05, j * 0.05, { seed: 41, oct: 4 }));
        // two wrinkled knuckles per finger, pores everywhere, veins on the arm and the back of the hand
        if (tube.kind === 'finger') {
          for (const kt of [0.34, 0.68]) c = mul3(c, 1 - 0.55 * Math.exp(-(((sm.t - kt) / 0.028) ** 2)) * (0.7 + 0.6 * noise(nx * 6 + kt * 9, 0, 56)));
        }
        c = mul3(c, 0.88 + 0.24 * noise(i * 0.4, j * 0.4, 42));
        const vein = smoothstep(0.90, 0.985, ridged(i * 0.016 + 3, j * 0.016, { seed: 43, oct: 4 })) * (tube.kind === 'arm' ? 1 : 0.25);
        c = mix3(c, [0.14, 0.09, 0.24], vein * 0.7);
        lit = mul3(c, 0.28 + 1.55 * diff);
        const spec = Math.pow(clamp(dot3(n, hv)), 28) * 0.15;
        lit = add3(lit, [spec, spec, spec]);
      }
      lit = add3(lit, mul3([0.10, 0.12, 0.18], Math.pow(1 - nz, 3) * 0.9));             // cold rim light
      img.over(i, j, lit[0], lit[1], lit[2], cover);
    }
  }
  // blood drying on the claws
  for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
    const [r, g, b2, a] = img.get(i, j);
    if (a < 0.6 || i < 360) continue;
    const m = smoothstep(0.6, 0.9, fbm(i * 0.05, j * 0.05, { seed: 57, oct: 3 })) * smoothstep(360, 440, i);
    if (m > 0) img.set(i, j, mix(r, 0.26, m * 0.7), mix(g, 0.0, m * 0.8), mix(b2, 0.01, m * 0.8), a);
  }
  img.save(file);
}

// ---------------------------------------------------------------- eyes
const lens = (dx, dy, rx, ry) => {   // 0..1 inside an almond, 1 on the long axis
  const e = Math.abs(dx) / rx;
  if (e >= 1) return -1;
  const h = ry * Math.pow(1 - e * e, 0.8);
  return h - Math.abs(dy);
};

function sclera(file) {
  const W = 256, H = 128, img = new Image(W, H);
  const L = norm3([-0.4, -0.55, 0.7]);
  for (let j = 0; j < H; j++) for (let i = 0; i < W; i++) {
    const dx = i + 0.5 - W / 2, dy = j + 0.5 - H / 2;
    const m = lens(dx, dy, 126, 62);
    if (m < 0) continue;
    const nxn = dx / 126, nyn = dy / 62;
    const nz = Math.sqrt(Math.max(0.05, 1 - nxn * nxn * 0.5 - nyn * nyn * 0.8));
    const n = norm3([nxn * 0.55, nyn * 0.8, nz]);
    let c = mix3([0.90, 0.84, 0.80], [0.72, 0.36, 0.40], smoothstep(0.45, 1.0, Math.abs(nxn)));
    const vein = smoothstep(0.84, 0.97, ridged(i * 0.07 + 2, j * 0.07, { seed: 61, oct: 5 })) * smoothstep(0.1, 0.8, Math.abs(nxn));
    c = mix3(c, [0.55, 0.04, 0.06], vein * 0.8);
    let lit = mul3(c, 0.35 + 0.8 * clamp(dot3(n, L)));
    lit = mul3(lit, 0.45 + 0.55 * smoothstep(0, 14, m));                                // lids shade the rim
    const spec = Math.pow(clamp(dot3(n, norm3(add3(L, [0, 0, 1])))), 60) * 0.5 + 0.45 * gauss(i, j, 92, 38, 8, 4);
    lit = add3(lit, [spec, spec, spec]);
    img.set(i, j, lit[0], lit[1], lit[2], smoothstep(0, 2.5, m));
  }
  img.save(file);
}

function iris(file) {
  const N = 128, img = new Image(N, N);
  for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
    const dx = (i + 0.5 - N / 2) / (N / 2), dy = (j + 0.5 - N / 2) / (N / 2);
    const r = Math.hypot(dx, dy);
    if (r > 1) continue;
    const ang = Math.atan2(dy, dx);
    const fibres = noise(ang * 16 / TAU * 6, r * 4, 71) * 0.6 + noise(ang * 40 / TAU * 6, r * 9, 72) * 0.4;
    let c = mix3([0.34, 0.07, 0.05], [0.04, 0.0, 0.0], smoothstep(0.25, 1.0, r));
    c = mul3(c, 0.5 + fibres);
    c = mix3(c, [0.01, 0.0, 0.0], smoothstep(0.80, 0.96, r));                           // dark limbal ring
    c = mix3(c, [0, 0, 0], smoothstep(0.34, 0.26, r));                                  // pupil
    const spec = gauss(dx, dy, -0.30, -0.32, 0.13, 0.09) + 0.4 * gauss(dx, dy, 0.28, 0.30, 0.07, 0.05);
    c = add3(c, [spec, spec, spec]);
    img.set(i, j, c[0], c[1], c[2], smoothstep(1.0, 0.93, r));
  }
  img.save(file);
}

function lids(file) {
  const W = 256, FH = 128, opens = [0, 0.35, 0.7, 1];
  const img = new Image(W, FH * 4);
  opens.forEach((open, f) => {
    for (let j = 0; j < FH; j++) for (let i = 0; i < W; i++) {
      const dx = i + 0.5 - W / 2, dy = j + 0.5 - FH / 2;
      const outer = ((dx / 126) ** 2 + (dy / 63) ** 2);
      if (outer >= 1) continue;
      // the opening matches the sclera, which is drawn at 80% of this frame
      const hole = open > 0 ? lens(dx, dy, 101, 50 * open) : -1;
      let a = smoothstep(1.0, 0.55, outer);
      if (hole > 0) a *= smoothstep(0, 3.5, -hole + 3.5) * 0 + smoothstep(3.5, 0, hole) ;
      if (a <= 0.003) continue;
      const crease = Math.pow(0.5 + 0.5 * Math.cos(Math.hypot(dx / 1.0, dy * 1.9) * 0.35 + fbm(i * 0.04, j * 0.04, { seed: 81 }) * 3), 6);
      let c = mix3([0.10, 0.06, 0.08], [0.20, 0.12, 0.15], fbm(i * 0.05, j * 0.05, { seed: 82, oct: 4 }));
      c = mul3(c, 1 - 0.5 * crease);
      const edge = hole > 0 ? smoothstep(10, 0, hole) : 0;
      c = mix3(c, [0.42, 0.04, 0.07], edge * 0.8);                                    // inflamed rim around the eye
      if (open === 0) c = mix3(c, [0.25, 0.02, 0.04], smoothstep(2.5, 0, Math.abs(dy)) * smoothstep(110, 60, Math.abs(dx)));
      const lash = hole > 0 && hole < 6 && noise(i * 0.7, 0, 83) > 0.62 ? 0.9 : 0;    // a few lashes
      c = mul3(c, 1 - lash * 0.6);
      img.set(i, f * FH + j, c[0], c[1], c[2], a);
    }
  });
  img.save(file);
}

function spiral(file) {
  const N = 256, img = new Image(N, N);
  for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
    const dx = (i + 0.5 - N / 2) / (N / 2), dy = (j + 0.5 - N / 2) / (N / 2);
    const r = Math.hypot(dx, dy);
    if (r > 1) continue;
    const ang = Math.atan2(dy, dx);
    // Archimedean spiral with ~3 turns; t is the signed distance to the nearest arm (0..0.5 of a turn)
    const turns = 3.2;
    const t = (r * turns - ang / TAU) % 1;
    const dist = Math.abs(t < 0 ? t + 1 : t > 0.5 ? t - 1 : t);                         // 0 on the arm
    const arm = smoothstep(0.30, 0.10, dist);
    const ridge = Math.sqrt(Math.max(0, 1 - (dist / 0.30) ** 2));                        // rounded arm profile
    let c = mix3([0.03, 0.0, 0.01], [0.85, 0.07, 0.06], arm);
    c = mul3(c, 0.6 + 0.6 * (1 - r * 0.5));
    const sh = clamp(dot3(norm3([-dx * 0.2 + 0.1, -dy * 0.2 - 0.3, 0.8]), norm3([-0.4, -0.5, 0.7])));
    c = mul3(c, 0.55 + 0.7 * sh * (0.5 + 0.5 * ridge * arm + 0.5 * (1 - arm)));
    c = mix3(c, [1.0, 0.55, 0.35], smoothstep(0.12, 0.0, r) * 0.9);                      // hot centre
    c = mix3(c, [0.02, 0.0, 0.0], smoothstep(0.86, 0.99, r));                            // dark rim
    const spec = gauss(dx, dy, -0.38, -0.40, 0.2, 0.12);
    c = add3(c, [spec * 0.8, spec * 0.7, spec * 0.7]);
    img.set(i, j, c[0], c[1], c[2], smoothstep(1.0, 0.95, r));
  }
  img.save(file);
}

export function makeEffects(out) {
  mist(`${out}/misc/mist.png`);
  vignette(`${out}/misc/vignette.png`);
  analogStatic(`${out}/misc/static.png`);
  hand(`${out}/misc/screen_hand.png`);
  sclera(`${out}/misc/eye_sclera.png`);
  iris(`${out}/misc/eye_iris.png`);
  lids(`${out}/misc/eye_lids.png`);
  spiral(`${out}/misc/eye_spiral.png`);
}
