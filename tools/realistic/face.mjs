// The jumpscare face (512x512, opaque). An original design: a gaunt, over-stretched face with deep hollow
// sockets, pin-prick pupils in wide bloodshot eyes, and a grin that runs almost cheek to cheek.
// Built as a height field, then lit from below like a torch held under the chin.
import { Image, clamp, mix, mix3, smoothstep, gauss, fbm, ridged, noise, hash2, norm3, dot3, mul3, add3, blurGrid } from '../lib/gfx.mjs';

const N = 512;
const SOCKET = [[-0.30, -0.165], [0.305, -0.135]];
const MOUTH_W = 0.50;

// mouth centre line: lowest in the middle, corners pulled up towards the cheeks
const mouthY = x => 0.30 - 0.13 * (Math.abs(x) / MOUTH_W) ** 2;
const mouthOpen = x => 0.165 * Math.max(0, 1 - (x / MOUTH_W) ** 2) ** 0.55;

// irregular teeth: widths and heights are seeded per tooth index
const teeth = [];
{
  let x = -MOUTH_W + 0.03;
  for (let i = 0; x < MOUTH_W - 0.03; i++) {
    const w = 0.03 + hash2(i, 1, 5) * 0.032;
    teeth.push({ x0: x, x1: x + w - 0.004, hu: 0.55 + hash2(i, 2, 5) * 0.4, hl: 0.4 + hash2(i, 3, 5) * 0.4, tint: hash2(i, 4, 5) });
    x += w;
  }
}
const toothAt = x => teeth.find(t => x >= t.x0 && x <= t.x1);

function heightField() {
  const H = new Float32Array(N * N);
  for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
    const x = (i + 0.5) / N * 2 - 1, y = (j + 0.5) / N * 2 - 1;
    const r2 = (x / 0.80) ** 2 + (y / 1.02) ** 2;
    let h = 0.55 * Math.sqrt(Math.max(0, 1 - r2));
    h += 0.12 * gauss(x, y, 0, -0.31, 0.55, 0.06);                       // heavy brow ridge
    for (const [sx, sy] of SOCKET) {
      h -= 0.34 * gauss(x, y, sx, sy, 0.185, 0.115);                      // deep sockets
      h += 0.05 * gauss(x, y, sx * 1.4, sy + 0.2, 0.15, 0.09);            // cheekbones
      h -= 0.13 * gauss(x, y, sx * 1.15, sy + 0.38, 0.15, 0.12);          // hollow cheeks
      h -= 0.04 * gauss(x, y, sx * 0.55, sy + 0.34, 0.03, 0.02);          // nostrils
    }
    h += 0.15 * gauss(x, y, 0, -0.02, 0.05, 0.22);                       // nose bridge
    h += 0.13 * gauss(x, y, 0, 0.115, 0.085, 0.065);                    // nose tip
    // smile creases: short grooves from the mouth corners up the cheeks
    for (const s of [-1, 1]) h -= 0.055 * gauss(x, y, s * (0.55 + 0.05 * Math.sin((y - 0.2) * 9)), 0.17, 0.022, 0.085);
    h += 0.012 * (fbm(x * 9, y * 9, { seed: 3 }) - 0.5);                // lumpy skin
    H[j * N + i] = h;
  }
  return H;
}

function eye(x, y, cx, cy, side) {
  // returns [r,g,b,mask] for the eyeball inside a socket
  const rx = 0.135, ry = 0.088;
  const e = ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2;
  const lens = 1 - Math.abs((y - cy) / ry) * 0.85;                       // almond: pointy at the corners
  const mask = smoothstep(1.0, 0.82, e + Math.max(0, 0.18 - lens) * 0.5);
  if (mask <= 0) return [0, 0, 0, 0];
  const dx = (x - cx) / rx, dy = (y - cy) / ry;
  // sclera: yellowish white, pinker towards the corners, bloodshot
  let c = mix3([0.90, 0.86, 0.78], [0.78, 0.45, 0.45], smoothstep(0.35, 1.0, Math.abs(dx)));
  const vein = smoothstep(0.74, 0.95, ridged(x * 38 + side * 7, y * 38, { seed: 31, oct: 4 })) * smoothstep(0.1, 0.75, Math.abs(dx));
  c = mix3(c, [0.62, 0.05, 0.07], vein * 0.8);
  // iris and a pin-prick pupil, looking straight into the camera, slightly off-centre
  const ix = x - cx - side * 0.006, iy = y - cy + 0.003, ir = Math.hypot(ix / 0.052, iy / 0.052);
  if (ir < 1) {
    const ang = Math.atan2(iy, ix);
    const fibres = fbm(Math.cos(ang) * 4 + 5, Math.sin(ang) * 4 + 5, { seed: 8, oct: 3 }) * 0.5 + 0.5 * noise(ang * 9, ir * 3, 4);
    let iris = mix3([0.05, 0.03, 0.03], [0.34, 0.20, 0.15], fibres * (0.4 + ir * 0.6));
    iris = mix3(iris, [0.02, 0.0, 0.0], smoothstep(0.78, 1.0, ir));                // dark limbal ring
    const pupil = smoothstep(0.26, 0.19, ir);
    iris = mix3(iris, [0, 0, 0], pupil);
    c = mix3(c, iris, smoothstep(1.0, 0.9, ir));
  }
  // the lids throw shadow over the top of the eye, the red rim shows below
  c = mul3(c, 0.45 + 0.55 * smoothstep(-1.0, 0.25, dy) * (1 - 0.35 * smoothstep(0.2, 1.0, Math.abs(dx))));
  c = mix3(c, [0.55, 0.08, 0.10], smoothstep(0.72, 0.95, dy) * 0.9);
  // wet highlights
  const spec = gauss(x, y, cx - 0.026 + side * 0.004, cy - 0.022, 0.012, 0.009) + 0.35 * gauss(x, y, cx + 0.03, cy + 0.02, 0.008, 0.006);
  c = add3(c, [spec, spec, spec]);
  return [c[0], c[1], c[2], mask];
}

export function makeFace(outFile) {
  const H = heightField();
  const blurSmall = blurGrid(H, N, N, 6, 2), blurBig = blurGrid(H, N, N, 22, 2);
  const img = new Image(N, N);
  const lightKey = norm3([-0.42, 0.68, 0.60]);                           // from below left, like a torch under the chin
  const lightFill = norm3([0.55, -0.55, 0.62]);
  const view = [0, 0, 1];

  for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
    const x = (i + 0.5) / N * 2 - 1, y = (j + 0.5) / N * 2 - 1;
    const r2 = (x / 0.80) ** 2 + (y / 1.02) ** 2;
    // ---- background: black with a dull red glow behind the head
    const glow = Math.exp(-r2 * 1.3) * 0.16;
    let col = [glow * 0.9 + 0.01, glow * 0.04, glow * 0.04];

    const head = smoothstep(1.01, 0.965, r2);
    if (head > 0) {
      const at = (xx, yy) => H[clamp(yy, 0, N - 1) * N + clamp(xx, 0, N - 1)];
      const nx = (at(i - 1, j) - at(i + 1, j)) * 52, ny = (at(i, j - 1) - at(i, j + 1)) * 52;
      const n = norm3([nx, ny, 1]);
      const ao = clamp(1 + (H[j * N + i] - blurSmall[j * N + i]) * 16 + (H[j * N + i] - blurBig[j * N + i]) * 7, 0.05, 1);

      // ---- skin albedo
      let skin = [0.80, 0.77, 0.70];
      const blot = fbm(x * 3.2, y * 3.2, { seed: 11 });
      skin = mix3(skin, [0.64, 0.68, 0.62], smoothstep(0.45, 0.75, blot));          // grey-green patches
      skin = mix3(skin, [0.72, 0.62, 0.62], smoothstep(0.55, 0.9, fbm(x * 4 + 9, y * 4, { seed: 12 })) * 0.5);
      for (const [sx, sy] of SOCKET) {
        skin = mix3(skin, [0.40, 0.33, 0.42], gauss(x, y, sx, sy + 0.05, 0.22, 0.15) * 0.75);   // bruised rings
        skin = mix3(skin, [0.55, 0.40, 0.42], gauss(x, y, sx, sy + 0.17, 0.2, 0.05) * 0.5);     // eye bags
      }
      skin = mul3(skin, 0.93 + 0.14 * (fbm(x * 28, y * 28, { seed: 13, oct: 3 }) - 0.5) * 2); // pores
      const wrinkle = Math.pow(0.5 + 0.5 * Math.cos((y * 15 + 0.9 * (fbm(x * 3, y * 2, { seed: 14 }) - 0.5)) * Math.PI * 2), 14);
      skin = mul3(skin, 1 - 0.16 * wrinkle * (0.4 + fbm(x * 12, y * 12, { seed: 17 })) * smoothstep(-0.30, -0.5, y) * smoothstep(0.75, 0.2, Math.abs(x)));  // forehead lines
      const veins = smoothstep(0.90, 0.985, ridged(x * 7 + 3, y * 7, { seed: 15, oct: 5 })) * smoothstep(0.1, 0.5, Math.abs(x)) * smoothstep(-0.05, -0.55, y + 0.2);
      skin = mix3(skin, [0.40, 0.30, 0.50], veins * 0.55);

      // ---- lighting
      const diff = clamp(dot3(n, lightKey));
      const fill = clamp(dot3(n, lightFill));
      const hv = norm3(add3(lightKey, view));
      const spec = Math.pow(clamp(dot3(n, hv)), 40) * 0.18 * (0.5 + blot);
      let lit = mul3([skin[0], skin[1] * 0.97, skin[2] * 0.9], (0.06 + 1.05 * diff) * ao);
      lit = add3(lit, mul3([0.45, 0.55, 0.75], fill * 0.07 * ao));      // cold fill
      lit = add3(lit, [spec, spec, spec]);

      // ---- eyes
      for (let k = 0; k < 2; k++) {
        const e = eye(x, y, SOCKET[k][0], SOCKET[k][1], k === 0 ? -1 : 1);
        if (e[3] > 0) lit = mix3(lit, mul3([e[0], e[1], e[2]], 0.9 + 0.1 * ao), e[3]);
      }

      // ---- the grin
      const my = mouthY(x), open = mouthOpen(x);
      const top = my - open * 0.45, bottom = my + open * 0.55;
      if (Math.abs(x) < MOUTH_W && y > top - 0.03 && y < bottom + 0.03) {
        const inside = smoothstep(top - 0.004, top + 0.004, y) * smoothstep(bottom + 0.004, bottom - 0.004, y);
        // thin dry lips, darker than the skin
        const lip = smoothstep(0.03, 0.0, Math.min(Math.abs(y - top), Math.abs(y - bottom)));
        lit = mix3(lit, mul3([0.45, 0.30, 0.30], 0.4 + 0.6 * diff), lip * 0.6 * (1 - inside));
        if (inside > 0) {
          let mouth = mix3([0.30, 0.02, 0.03], [0.0, 0.0, 0.0], smoothstep(top, bottom, y) * 0.5 + 0.35);
          const t = toothAt(x);
          if (t) {
            const tx = (x - t.x0) / (t.x1 - t.x0);
            const upper = y < my;
            const base = upper ? top : bottom;
            const len = open * (upper ? t.hu : t.hl) * 0.85;
            const depth = upper ? (y - base) / len : (base - y) / len;
            // gum above the upper teeth and below the lower ones, then the tooth itself
            const gum = upper ? smoothstep(0.35, 0.0, depth) : smoothstep(0.35, 0.0, depth);
            const tooth = smoothstep(1.02, 0.86, depth) * smoothstep(-0.04, 0.10, depth) * smoothstep(0.015, 0.075, Math.min(tx, 1 - tx));
            let ivory = mix3([0.86, 0.80, 0.63], [0.58, 0.47, 0.30], t.tint * 0.6);
            ivory = mul3(ivory, (0.55 + 0.45 * (1 - 0.8 * clamp(depth))) * (0.78 + 0.22 * Math.sin(tx * Math.PI)));
            ivory = mix3(ivory, [0.40, 0.30, 0.08], smoothstep(0.85, 1.05, depth) * 0.3);
            mouth = mix3(mouth, [0.62, 0.12, 0.16], gum * 0.85);
            mouth = mix3(mouth, ivory, tooth);
            const wet = Math.pow(clamp(1 - Math.abs(tx - 0.3) * 4), 3) * tooth * 0.25;
            mouth = add3(mouth, [wet, wet, wet]);
          }
          lit = mix3(lit, mouth, inside);
        }
      }

      // ---- finish: fall-off to the edge, then composite on the background
      lit = mul3(lit, 0.45 + 0.55 * smoothstep(1.0, 0.5, r2));
      col = mix3(col, lit, head);
    }
    img.set(i, j, col[0], col[1], col[2]);
  }

  // ---- camera: chromatic aberration, a few torn scanlines, film grain, hard vignette
  const out = new Image(N, N);
  for (let j = 0; j < N; j++) {
    const tear = hash2(Math.floor(j / 9), 3, 77) > 0.965 ? Math.round((hash2(j >> 3, 5, 77) - 0.5) * 14) : 0;
    for (let i = 0; i < N; i++) {
      const sx = i - tear;
      const r = img.get(sx + 1, j)[0], g = img.get(sx, j)[1], b = img.get(sx - 1, j)[2];
      const vx = (i / N - 0.5) * 2, vy = (j / N - 0.5) * 2;
      const vig = 1 - 0.55 * smoothstep(0.4, 1.5, Math.hypot(vx, vy));
      const grain = (hash2(i, j, 99) - 0.5) * 0.07;
      const scan = j % 2 ? 0.965 : 1;
      const t = (v) => clamp(Math.pow(clamp(v * vig * scan), 0.92) + grain * (0.4 + v));
      out.set(i, j, t(r), t(g), t(b));
    }
  }
  out.save(outFile, { opaque: true });
}
