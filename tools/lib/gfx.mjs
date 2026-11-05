// Small procedural-graphics toolkit for the realistic textures: seeded noise, helpers and a PNG writer.
// Everything is deterministic (no Math.random), so regenerating gives identical files.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

// ---------------------------------------------------------------- maths
export const clamp = (x, a = 0, b = 1) => (x < a ? a : x > b ? b : x);
export const mix = (a, b, t) => a + (b - a) * t;
export const smoothstep = (a, b, x) => { const t = clamp((x - a) / (b - a)); return t * t * (3 - 2 * t); };
export const fract = x => x - Math.floor(x);
export const mod = (a, n) => ((a % n) + n) % n;
export const gauss = (x, y, cx, cy, rx, ry) => Math.exp(-(((x - cx) / rx) ** 2) - (((y - cy) / ry) ** 2));
export const mix3 = (a, b, t) => [mix(a[0], b[0], t), mix(a[1], b[1], t), mix(a[2], b[2], t)];
export const mul3 = (c, k) => [c[0] * k, c[1] * k, c[2] * k];
export const add3 = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
export const norm3 = v => { const l = Math.hypot(v[0], v[1], v[2]) || 1; return [v[0] / l, v[1] / l, v[2] / l]; };
export const dot3 = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];

// ---------------------------------------------------------------- noise
export function hash2(x, y, seed = 0) {
  let h = Math.imul(x | 0, 374761393) ^ Math.imul(y | 0, 668265263) ^ Math.imul(seed | 0, 1442695041);
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  h ^= h >>> 16;
  return (h >>> 0) / 4294967296;
}
const quintic = t => t * t * t * (t * (t * 6 - 15) + 10);

/** Value noise in 0..1. With {@code period} the lattice wraps, so the result tiles every {@code period} cells. */
export function noise(x, y, seed = 0, period = 0) {
  const xi = Math.floor(x), yi = Math.floor(y);
  const u = quintic(x - xi), v = quintic(y - yi);
  let x0 = xi, x1 = xi + 1, y0 = yi, y1 = yi + 1;
  if (period) { x0 = mod(x0, period); x1 = mod(x1, period); y0 = mod(y0, period); y1 = mod(y1, period); }
  return mix(mix(hash2(x0, y0, seed), hash2(x1, y0, seed), u), mix(hash2(x0, y1, seed), hash2(x1, y1, seed), u), v);
}

export function fbm(x, y, { oct = 5, lac = 2, gain = 0.5, seed = 0, period = 0 } = {}) {
  let sum = 0, amp = 1, total = 0, f = 1;
  for (let i = 0; i < oct; i++) {
    sum += amp * noise(x * f, y * f, seed + i * 17, period ? period * f : 0);
    total += amp; amp *= gain; f *= lac;
  }
  return sum / total;
}
/** 0..1 sharp ridges, good for veins and cracks (1 on the ridge line). */
export function ridged(x, y, opts = {}) { return 1 - Math.abs(2 * fbm(x, y, opts) - 1); }

/** Distance to the nearest of a jittered grid of points; the difference of the two nearest gives crack lines. */
export function voronoi(x, y, seed = 0) {
  const xi = Math.floor(x), yi = Math.floor(y);
  let d1 = 9, d2 = 9;
  for (let j = -1; j <= 1; j++) for (let i = -1; i <= 1; i++) {
    const cx = xi + i + hash2(xi + i, yi + j, seed), cy = yi + j + hash2(xi + i, yi + j, seed + 91);
    const d = Math.hypot(x - cx, y - cy);
    if (d < d1) { d2 = d1; d1 = d; } else if (d < d2) d2 = d;
  }
  return [d1, d2];
}

// ---------------------------------------------------------------- image
export class Image {
  constructor(w, h) { this.w = w; this.h = h; this.d = new Float32Array(w * h * 4); }
  set(x, y, r, g, b, a = 1) {
    if (x < 0 || y < 0 || x >= this.w || y >= this.h) return;
    const i = (y * this.w + x) * 4;
    this.d[i] = r; this.d[i + 1] = g; this.d[i + 2] = b; this.d[i + 3] = a;
  }
  get(x, y) {
    x = clamp(x | 0, 0, this.w - 1); y = clamp(y | 0, 0, this.h - 1);
    const i = (y * this.w + x) * 4;
    return [this.d[i], this.d[i + 1], this.d[i + 2], this.d[i + 3]];
  }
  /** Alpha-composite a colour over the current pixel. */
  over(x, y, r, g, b, a) {
    if (x < 0 || y < 0 || x >= this.w || y >= this.h || a <= 0) return;
    const i = (y * this.w + x) * 4, A = this.d[i + 3];
    const oa = a + A * (1 - a);
    if (oa <= 0) return;
    this.d[i] = (r * a + this.d[i] * A * (1 - a)) / oa;
    this.d[i + 1] = (g * a + this.d[i + 1] * A * (1 - a)) / oa;
    this.d[i + 2] = (b * a + this.d[i + 2] * A * (1 - a)) / oa;
    this.d[i + 3] = oa;
  }
  /** fn(x, y) returns [r, g, b] or [r, g, b, a]. */
  fill(fn, x0 = 0, y0 = 0, w = this.w, h = this.h) {
    for (let y = y0; y < y0 + h; y++) for (let x = x0; x < x0 + w; x++) {
      const c = fn(x, y);
      this.set(x, y, c[0], c[1], c[2], c.length > 3 ? c[3] : 1);
    }
  }

  save(file, { opaque = false } = {}) {
    const bpp = opaque ? 3 : 4, stride = this.w * bpp;
    const raw = Buffer.alloc((stride + 1) * this.h);
    const prev = new Uint8Array(stride), cur = new Uint8Array(stride);
    const filtered = [new Uint8Array(stride), new Uint8Array(stride), new Uint8Array(stride), new Uint8Array(stride)];
    for (let y = 0; y < this.h; y++) {
      for (let x = 0; x < this.w; x++) {
        const i = (y * this.w + x) * 4;
        // a little blue-noise dither keeps smooth gradients (mist, vignette) from banding
        const dn = (hash2(x, y, 7) - 0.5) / 255;
        for (let c = 0; c < 3; c++) cur[x * bpp + c] = Math.round(clamp(this.d[i + c] + dn) * 255);
        if (!opaque) cur[x * bpp + 3] = Math.round(clamp(this.d[i + 3]) * 255);
      }
      // pick the PNG row filter (none, sub, up, paeth) with the smallest sum of absolute residuals
      let best = 0, bestSum = Infinity;
      for (const type of [0, 1, 2, 4]) {
        const out = filtered[type === 4 ? 3 : type];
        let sum = 0;
        for (let i = 0; i < stride; i++) {
          const a = i >= bpp ? cur[i - bpp] : 0, b = prev[i], c = i >= bpp ? prev[i - bpp] : 0;
          let pred = 0;
          if (type === 1) pred = a;
          else if (type === 2) pred = b;
          else if (type === 4) {
            const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
            pred = pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
          }
          const r = (cur[i] - pred) & 255;
          out[i] = r;
          sum += r < 128 ? r : 256 - r;
        }
        if (sum < bestSum) { bestSum = sum; best = type; }
      }
      raw[y * (stride + 1)] = best;
      Buffer.from(filtered[best === 4 ? 3 : best]).copy(raw, y * (stride + 1) + 1);
      prev.set(cur);
    }
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(this.w, 0); ihdr.writeUInt32BE(this.h, 4);
    ihdr[8] = 8; ihdr[9] = opaque ? 2 : 6;
    const png = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
      chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, png);
    console.log(`wrote ${file} ${this.w}x${this.h} ${(png.length / 1024).toFixed(0)} KB`);
  }
}

const CRC = new Uint32Array(256).map((_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
function crc32(buf) { let c = 0xffffffff; for (const b of buf) c = CRC[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; }
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}

/** Separable box blur of a Float32Array grid (used for ambient occlusion). */
export function blurGrid(src, w, h, radius, passes = 2) {
  let a = Float32Array.from(src), b = new Float32Array(src.length);
  for (let p = 0; p < passes; p++) {
    for (let y = 0; y < h; y++) {
      let sum = 0;
      for (let x = -radius; x <= radius; x++) sum += a[y * w + clamp(x, 0, w - 1)];
      for (let x = 0; x < w; x++) {
        b[y * w + x] = sum / (2 * radius + 1);
        sum += a[y * w + clamp(x + radius + 1, 0, w - 1)] - a[y * w + clamp(x - radius, 0, w - 1)];
      }
    }
    for (let x = 0; x < w; x++) {
      let sum = 0;
      for (let y = -radius; y <= radius; y++) sum += b[clamp(y, 0, h - 1) * w + x];
      for (let y = 0; y < h; y++) {
        a[y * w + x] = sum / (2 * radius + 1);
        sum += b[clamp(y + radius + 1, 0, h - 1) * w + x] - b[clamp(y - radius, 0, h - 1) * w + x];
      }
    }
  }
  return a;
}
