// The figure skin atlas. FigureModels declares a 256x256 UV space; this file paints it at 4 pixels per UV unit
// (1024x1024), so a unit-sized cube face gets 4x4 texels of real material instead of one flat colour.
//
// Regions (UV units) - keep in sync with the constants at the top of FigureModels.java:
//   DARK  (0,0)     128x128   wet, charred black skin with faint dark-red veins; also holds
//     VOID (96,32) black, RED (96,64) wet blood, WHITE (96,96) bloodshot bone-white (eyes, teeth)
//   FLESH (128,0)   128x64    raw red muscle with fibres, rib grooves, tendons
//   FUR   (128,64)  128x64    matted black fur with a cold sheen
//   MASK  (0,128)   64x64     pale cracked porcelain skin
//   GUM   (64,128)  32x32     wet red gums
//   GLOW  (96,128)  32x32     glowing red eye surface
//   CLOAK (128,128) 128x128   tattered black cloth
//   LIMB  (0,192)   128x64    red flesh fading to black down the limb
import { Image, clamp, mix, mix3, mul3, add3, smoothstep, fbm, ridged, noise, voronoi } from '../lib/gfx.mjs';

const S = 4;

const darkSkin = (u, v) => {
  const n1 = fbm(u * 0.09, v * 0.09, { seed: 1, oct: 5 });
  const n2 = fbm(u * 0.6, v * 0.6, { seed: 2, oct: 3 });
  const l = 0.012 + 0.034 * n1 + 0.018 * n2;
  const sheen = smoothstep(0.60, 0.88, fbm(u * 0.7, v * 0.045, { seed: 3, oct: 4 }));       // wet streaks running down
  const vein = smoothstep(0.93, 0.995, ridged(u * 0.14 + 2, v * 0.14, { seed: 4, oct: 4 }));
  let c = [l * 0.9, l * 0.9, l * 1.2];
  c = add3(c, mul3([0.10, 0.11, 0.16], sheen * 0.7));
  return mix3(c, [0.34, 0.03, 0.05], vein * 0.6);
};

const cloth = (u, v) => {
  const fold = fbm(u * 0.12, v * 0.02, { seed: 5, oct: 5 });                                  // long vertical folds
  const warp = fbm(u * 0.7, v * 0.7, { seed: 6, oct: 3 });
  const weave = 0.5 + 0.5 * Math.sin(u * 5.2 + warp * 3) * Math.sin(v * 5.2 + warp * 2);       // threads
  const l = 0.008 + 0.05 * fold * fold + 0.012 * weave + 0.008 * warp;
  const dust = smoothstep(0.68, 0.95, fbm(u * 0.5, v * 0.5, { seed: 7 })) * 0.05;
  const stain = smoothstep(0.76, 0.95, fbm(u * 0.08, v * 0.1, { seed: 8 }));
  let c = [l + dust * 0.8, l + dust * 0.8, l * 1.15 + dust];
  return mix3(c, [0.075, 0.0, 0.02], stain * 0.8);                                            // old blood
};

const flesh = (u, v) => {
  const fibre = fbm(u * 0.035, v * 1.1, { seed: 9, oct: 4 });                                 // runs along u (the ribs)
  const tone = fbm(u * 0.05, v * 0.12, { seed: 10, oct: 3 });
  let c = mix3([0.26, 0.015, 0.025], [0.72, 0.12, 0.10], clamp(fibre * 0.7 + tone * 0.5));
  const groove = Math.pow(Math.abs(Math.sin(v * Math.PI / 4)), 6);                            // a rib every 4 units
  c = mul3(c, 1 - 0.55 * groove);
  const tendon = smoothstep(0.95, 1.0, ridged(u * 0.06, v * 0.5, { seed: 11, oct: 3 }));
  c = mix3(c, [0.78, 0.66, 0.55], tendon * 0.7);
  const wet = smoothstep(0.78, 0.96, fbm(u * 0.35, v * 0.35, { seed: 12 }));
  return add3(c, mul3([0.55, 0.30, 0.30], wet * 0.45));
};

const fur = (u, v) => {
  const strand = smoothstep(0.58, 0.88, noise(u * 2.4, v * 0.2, 13) * 0.65 + noise(u * 5.1, v * 0.35, 14) * 0.35);
  const clump = fbm(u * 0.15, v * 0.1, { seed: 15, oct: 3 });
  const l = 0.008 + 0.022 * clump;
  let c = [l, l, l * 1.1];
  c = add3(c, mul3([0.12, 0.13, 0.18], strand * (0.3 + clump)));                              // cold rim light on the hairs
  return add3(c, mul3([0.18, 0.02, 0.02], smoothstep(0.93, 1, noise(u * 3, v * 0.4, 16)) * 0.6));
};

const mask = (u, v) => {
  const blot = fbm(u * 0.12, v * 0.12, { seed: 17, oct: 4 });
  let c = mix3([0.84, 0.81, 0.75], [0.70, 0.66, 0.58], blot);
  c = mul3(c, 0.93 + 0.14 * noise(u * 3.2, v * 3.2, 18));                                    // pores
  const [d1, d2] = voronoi(u * 0.16, v * 0.16, 19);
  const crack = smoothstep(0.05, 0.0, d2 - d1) * smoothstep(0.35, 0.6, fbm(u * 0.1, v * 0.1, { seed: 20 }));
  c = mix3(c, [0.20, 0.15, 0.13], crack * 0.85);
  c = mix3(c, [0.42, 0.38, 0.32], smoothstep(0.45, 1.0, v / 64) * 0.45);                     // grime towards the chin
  return mix3(c, [0.5, 0.2, 0.2], smoothstep(0.82, 0.96, fbm(u * 0.2 + 5, v * 0.05, { seed: 21 })) * 0.35); // stains
};

const gum = (u, v) => {
  const n = fbm(u * 0.4, v * 0.4, { seed: 22 });
  let c = mix3([0.48, 0.05, 0.08], [0.72, 0.16, 0.18], n);
  return add3(c, mul3([0.6, 0.4, 0.4], smoothstep(0.82, 0.97, noise(u * 1.3, v * 1.3, 23)) * 0.4));
};

const glow = (u, v) => {
  const n = fbm(u * 0.5, v * 0.5, { seed: 24, oct: 3 });
  const vein = smoothstep(0.85, 0.98, ridged(u * 0.5, v * 0.5, { seed: 25, oct: 3 }));
  return mix3(mix3([1.0, 0.18, 0.12], [1.0, 0.45, 0.25], n), [0.55, 0.0, 0.02], vein * 0.5);
};

const boneWhite = (u, v) => {
  const vein = smoothstep(0.86, 0.98, ridged(u * 0.7, v * 0.7, { seed: 26, oct: 3 }));
  const n = fbm(u * 0.3, v * 0.3, { seed: 27 });
  return mix3(mix3([0.94, 0.91, 0.84], [0.82, 0.76, 0.62], n), [0.6, 0.08, 0.1], vein * 0.5);
};

const blood = (u, v) => {
  const n = fbm(u * 0.25, v * 0.25, { seed: 28, oct: 4 });
  let c = mix3([0.20, 0.0, 0.015], [0.5, 0.03, 0.05], n);
  return add3(c, mul3([0.8, 0.4, 0.4], smoothstep(0.86, 0.98, noise(u * 1.1, v * 1.1, 29)) * 0.4));
};

const limb = (u, v) => {
  const t = clamp(v / 40 + (fbm(u * 0.2, v * 0.2, { seed: 30 }) - 0.5) * 0.35);              // red at the top, black at the end
  const base = flesh(u * 0.8 + 11, v * 0.8 + 3);
  return mix3(mul3(base, 1.0), [0.015, 0.006, 0.008], smoothstep(0.1, 0.9, t));
};

export function makeFigureAtlas(outFile) {
  const size = 256 * S;
  const img = new Image(size, size);
  const paint = (x0, y0, w, h, fn) => {
    for (let py = y0 * S; py < (y0 + h) * S; py++) for (let px = x0 * S; px < (x0 + w) * S; px++) {
      const c = fn((px + 0.5) / S - x0, (py + 0.5) / S - y0);
      img.set(px, py, c[0], c[1], c[2], 1);
    }
  };
  paint(0, 0, 256, 256, darkSkin);              // nothing may stay transparent: every unpainted corner is dark skin
  paint(0, 0, 128, 128, darkSkin);
  paint(96, 32, 32, 32, () => [0, 0, 0]);
  paint(96, 64, 32, 32, blood);
  paint(96, 96, 32, 32, boneWhite);
  paint(128, 0, 128, 64, flesh);
  paint(128, 64, 128, 64, fur);
  paint(0, 128, 64, 64, mask);
  paint(64, 128, 32, 32, gum);
  paint(96, 128, 32, 32, glow);
  paint(128, 128, 128, 128, cloth);
  paint(0, 192, 128, 64, limb);
  if (outFile) img.save(outFile, { opaque: true });
  return img;
}
