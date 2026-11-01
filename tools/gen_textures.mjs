// Generates all mod textures as hard-edged pixel art (no anti-aliasing).
// Usage: node tools/gen_textures.mjs   (run from the project root)
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

const OUT = 'src/main/resources/assets/halloweenevent/textures';

// ---------- PNG writer ----------
const CRC = new Uint32Array(256).map((_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
function crc32(buf) {
  let c = 0xffffffff;
  for (const b of buf) c = CRC[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}
class Img {
  constructor(w, h) { this.w = w; this.h = h; this.d = new Uint8Array(w * h * 4); }
  set(x, y, c) {
    if (x < 0 || y < 0 || x >= this.w || y >= this.h || c == null) return;
    const i = (y * this.w + x) * 4;
    this.d[i] = (c >> 16) & 255; this.d[i + 1] = (c >> 8) & 255; this.d[i + 2] = c & 255;
    this.d[i + 3] = c > 0xffffff ? (c >>> 24) & 255 : 255;
  }
  /** Explicit alpha (set() treats a 0 alpha byte as opaque). */
  setA(x, y, rgb, a) {
    this.set(x, y, rgb);
    this.d[(y * this.w + x) * 4 + 3] = a;
  }
  rect(x, y, w, h, c) { for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) this.set(x + i, y + j, c); }
  grid(x0, y0, rows, pal) {
    rows.forEach((row, y) => [...row].forEach((ch, x) => { if (ch !== '.') this.set(x0 + x, y0 + y, pal[ch]); }));
  }
  scaled(f) {
    const o = new Img(this.w * f, this.h * f);
    for (let y = 0; y < o.h; y++) for (let x = 0; x < o.w; x++) {
      const si = (Math.floor(y / f) * this.w + Math.floor(x / f)) * 4, di = (y * o.w + x) * 4;
      for (let k = 0; k < 4; k++) o.d[di + k] = this.d[si + k];
    }
    return o;
  }
  save(rel) {
    const raw = Buffer.alloc((this.w * 4 + 1) * this.h);
    for (let y = 0; y < this.h; y++) {
      raw[y * (this.w * 4 + 1)] = 0;
      Buffer.from(this.d.buffer, y * this.w * 4, this.w * 4).copy(raw, y * (this.w * 4 + 1) + 1);
    }
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(this.w, 0); ihdr.writeUInt32BE(this.h, 4);
    ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
    const png = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
      chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
    const file = path.join(OUT, rel);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, png);
    console.log('wrote', file, `${this.w}x${this.h}`);
  }
}

// Deterministic noise so regenerating gives identical files.
let seed = 1337;
const rnd = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);

// ---------- vanilla-style bevels ----------
const V = { white: 0xffffff, light: 0xc6c6c6, mid: 0x8b8b8b, dark: 0x555555, slot: 0x373737, black: 0x000000 };

/** Raised panel: black outline with notched corners, 2px white top/left, 2px #555 bottom/right. */
function panel(img, x, y, w, h, fill, hi, lo) {
  img.rect(x + 1, y, w - 2, 1, V.black); img.rect(x + 1, y + h - 1, w - 2, 1, V.black);
  img.rect(x, y + 1, 1, h - 2, V.black); img.rect(x + w - 1, y + 1, 1, h - 2, V.black);
  img.rect(x + 1, y + 1, w - 2, h - 2, fill);
  img.rect(x + 1, y + 1, w - 3, 2, hi); img.rect(x + 1, y + 1, 2, h - 3, hi);
  img.rect(x + 2, y + h - 3, w - 3, 2, lo); img.rect(x + w - 3, y + 2, 2, h - 3, lo);
  img.set(x + w - 2, y + 1, fill); img.set(x + 1, y + h - 2, fill);
}
/** Inset slot: #373737 top/left, white bottom/right, #8B8B8B fill. */
function slot(img, x, y, w, h) {
  img.rect(x, y, w, h, V.mid);
  img.rect(x, y, w - 1, 1, V.slot); img.rect(x, y, 1, h - 1, V.slot);
  img.rect(x + 1, y + h - 1, w - 1, 1, V.white); img.rect(x + w - 1, y + 1, 1, h - 1, V.white);
}

const PUMPKIN = {
  k: 0x3a1c00, o: 0xe07b00, O: 0xff9a1f, d: 0xb85a00, g: 0x4a6b1a, G: 0x6b8f2a, y: 0xffd23a,
};
const PUMPKIN_ICON = [
  '....gG....',
  '....Gg....',
  '.kkkkkkkk.',
  'kdoOoooodk',
  'kdOyoooydk',
  'kdoooooodk',
  'kdoyyyyodk',
  'kdooyyoodk',
  '.kdddddk..',
  '..kkkkkk..',
];

// ---------- GUI: admin panel (248x200) + slot sprite at (0,204) ----------
{
  const img = new Img(256, 256);
  panel(img, 0, 0, 248, 200, V.light, V.white, V.dark);
  // Separator under the tab row.
  img.rect(7, 41, 234, 1, V.dark); img.rect(7, 42, 234, 1, V.white);
  img.grid(232, 4, PUMPKIN_ICON.map(r => r.slice(0, 10)), PUMPKIN);
  slot(img, 0, 204, 18, 18);
  img.save('gui/admin.png');
}

// ---------- GUI: HUD frame (104x26), dark pumpkin ramp ----------
{
  const img = new Img(128, 32);
  panel(img, 0, 0, 104, 26, 0x241a12, 0x8a4a12, 0x120c07);
  slot(img, 4, 4, 18, 18);
  img.rect(5, 5, 16, 16, 0x3a2a1c);
  img.save('gui/hud.png');
}

// ---------- items (16x16) ----------
{
  const img = new Img(16, 16);
  img.grid(0, 0, [
    '................',
    '.......kk.......',
    '......kWWk......',
    '......kWwk......',
    '.....kWWWwk.....',
    '.....kOOOok.....',
    '....kOOOOOok....',
    '....kOOOOOok....',
    '...kOOOOOOOok...',
    '...kYYYYYYYyk...',
    '..kYYYYYYYYYyk..',
    '..kYYYYYYYYYyk..',
    '.kYYYYYYYYYYYyk.',
    '.kyyyyyyyyyyyyk.',
    '..kkkkkkkkkkkk..',
    '................',
  ], { k: 0x4a2a0a, W: 0xfff8e8, w: 0xd8cfbf, O: 0xf08a1c, o: 0xc86a10, Y: 0xffd23a, y: 0xd8a820 });
  img.save('item/candy_corn.png');
}
{
  const img = new Img(16, 16);
  img.grid(0, 0, [
    '................',
    '.....kkkkkk.....',
    '....kRRWWRRk....',
    '...kRWWRRWWrk...',
    '...kWRRWWRRrk...',
    '...kRWWRRWWrk...',
    '...kWRRWWRrrk...',
    '....krrWWrrk....',
    '.....kkkkkk.....',
    '.......sS.......',
    '.......sS.......',
    '.......sS.......',
    '.......sS.......',
    '.......sS.......',
    '.......kk.......',
    '................',
  ], { k: 0x3a0a0a, R: 0xd8202a, r: 0x9a1018, W: 0xffffff, s: 0xf0f0f0, S: 0xb0b0b0 });
  img.save('item/lollipop.png');
}
{
  const img = new Img(16, 16);
  img.grid(0, 0, [
    '................',
    '................',
    '................',
    '................',
    '.kk..........kk.',
    'kPPk.kkkkkk.kPPk',
    'kPpPkMLMMMMkPpPk',
    '.kPPkMLMMMMkPPk.',
    '..kPkMMMMMmkPk..',
    '.kPPkMMMMmmkPPk.',
    'kPpPkMMMmmmkPpPk',
    'kPPk.kkkkkk.kPPk',
    '.kk..........kk.',
    '................',
    '................',
    '................',
  ], { k: 0x2a1040, M: 0x8a3ac8, L: 0xc890f0, m: 0x5a2090, P: 0xf08a1c, p: 0xc86a10 });
  img.save('item/wrapped_candy.png');
}


// ---------- horror face (64x64 art, saved at 256x256) ----------
{
  const N = 64, img = new Img(N, N);
  const bg = 0xff000000, skin = 0xffc8c2b8, skinD = 0xff8e877c, skinDD = 0xff5a544c;
  const blood = 0xff6a0000, bloodD = 0xff3a0000, white = 0xffffffff;
  img.rect(0, 0, N, N, bg);
  const cx = 31.5, cy = 31;
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    const v = ((x - cx) / 25) ** 2 + ((y - cy) / 30) ** 2;
    if (v > 1) continue;
    let c = skin;
    if (v > 0.78 || x > cx + 14) c = skinD;           // light from the top-left
    if (v > 0.93) c = skinDD;
    if (rnd() < 0.06) c = skinD;
    img.set(x, y, c);
  }
  // hollow eye sockets
  const socket = (ex, ey) => {
    for (let y = -8; y <= 8; y++) for (let x = -7; x <= 7; x++) {
      const v = (x / 7) ** 2 + (y / 8) ** 2;
      if (v <= 1) img.set(ex + x, ey + y, v > 0.75 ? skinDD : bg);
    }
    img.rect(ex - 1, ey, 2, 2, white);                // tiny pinprick pupils
  };
  socket(21, 24); socket(42, 24);
  // drips from the eyes
  for (const [x, len] of [[18, 14], [22, 9], [40, 12], [45, 17]]) {
    for (let y = 31; y < 31 + len; y++) img.set(x, y, y > 31 + len - 3 ? bloodD : blood);
  }
  // nose slits
  img.rect(29, 34, 1, 4, bg); img.rect(34, 34, 1, 4, bg);
  // huge grin: dark mouth with jagged teeth
  for (let x = 12; x <= 51; x++) {
    const t = (x - 31.5) / 20;
    const top = Math.round(42 + 3 * (1 - t * t) - 9 * t * t);
    const bottom = Math.round(top + 2 + 8 * (1 - t * t));
    for (let y = top; y <= bottom; y++) img.set(x, y, bg);
    if (x % 3 !== 0) {
      img.set(x, top, white); img.set(x, top + 1, x % 3 === 1 ? white : bg);
      img.set(x, bottom, white); img.set(x, bottom - 1, x % 3 === 2 ? white : bg);
    }
  }
  // cracks
  for (const [sx, sy, dx] of [[10, 12, 1], [52, 16, -1], [48, 50, -1]]) {
    let x = sx;
    for (let y = sy; y < sy + 10; y++) { img.set(x, y, skinDD); if (rnd() < 0.5) x += dx; }
  }
  img.scaled(4).save('misc/horror_face.png');
}

// ---------- figure skin (128x128) for the cuboid shadow figures ----------
// Dark mottled skin everywhere, plus solid material blocks used by eyes/teeth/mouth:
// void black (96..127, 32..63), red (96..127, 64..95), glowing white (96..127, 96..127).
{
  const img = new Img(128, 128);
  const skin = [0xff060608, 0xff0b0a0d, 0xff100e13, 0xff08070a];
  for (let y = 0; y < 128; y++) for (let x = 0; x < 128; x++) {
    let c = skin[Math.floor(rnd() * skin.length)];
    if (rnd() < 0.03) c = 0xff1c1622;
    img.set(x, y, c);
  }
  // thin dark-red veins
  for (let v = 0; v < 14; v++) {
    let x = Math.floor(rnd() * 96), y = Math.floor(rnd() * 128);
    for (let i = 0; i < 12; i++) {
      img.set(x, y, 0xff240a10);
      x += Math.floor(rnd() * 3) - 1; y += 1;
    }
  }
  img.rect(96, 32, 32, 32, 0xff000000);
  for (let y = 64; y < 96; y++) for (let x = 96; x < 128; x++) img.set(x, y, rnd() < 0.2 ? 0xff5a0000 : 0xff8a0000);
  for (let y = 96; y < 128; y++) for (let x = 96; x < 128; x++) img.set(x, y, rnd() < 0.15 ? 0xffe8e6e0 : 0xfffcfcf8);
  img.save('entity/figure_skin.png');
}

// ---------- screen effects (soft gradients are intended here: fog, not GUI art) ----------
function tileNoise(size, cells, seedOffset) {
  const g = [];
  for (let i = 0; i < cells * cells; i++) g.push(rnd());
  const at = (x, y) => g[((y % cells + cells) % cells) * cells + ((x % cells + cells) % cells)];
  const sm = t => t * t * (3 - 2 * t);
  return (x, y) => {
    const fx = x / size * cells, fy = y / size * cells;
    const x0 = Math.floor(fx), y0 = Math.floor(fy), tx = sm(fx - x0), ty = sm(fy - y0);
    const a = at(x0, y0) * (1 - tx) + at(x0 + 1, y0) * tx;
    const b = at(x0, y0 + 1) * (1 - tx) + at(x0 + 1, y0 + 1) * tx;
    return a * (1 - ty) + b * ty;
  };
}
{
  const N = 128, img = new Img(N, N);
  const n1 = tileNoise(N, 4), n2 = tileNoise(N, 8), n3 = tileNoise(N, 16);
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    const v = n1(x, y) * 0.55 + n2(x, y) * 0.3 + n3(x, y) * 0.15;
    const a = Math.max(0, Math.min(255, Math.round((v - 0.3) * 1.6 * 255)));
    img.setA(x, y, 0xc8ccd0, a);
  }
  img.save('misc/mist.png');
}
{
  const N = 256, img = new Img(N, N);
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    const dx = (x + 0.5) / N * 2 - 1, dy = (y + 0.5) / N * 2 - 1;
    const d = Math.sqrt(dx * dx + dy * dy) / Math.SQRT2;
    const t = Math.max(0, Math.min(1, (d - 0.3) / 0.65));
    const a = Math.round(t * t * (3 - 2 * t) * 245);
    img.setA(x, y, 0xffffff, a);
  }
  img.save('misc/vignette.png');
}
{
  const img = new Img(64, 64);
  for (let y = 0; y < 64; y++) for (let x = 0; x < 64; x++) {
    const v = Math.floor(rnd() * 256);
    img.set(x, y, 0xff000000 + v * 0x010101);
  }
  img.save('misc/static.png');
}

// ---------- screen hand (victim overlay, 128 art saved at 256): wrist at the left edge, claws reaching right ----------
{
  const N = 128, img = new Img(N, N);
  const segs = [];
  const add = (pts, r1, r2, nail) => {
    for (let k = 0; k < pts.length - 1; k++) {
      const t0 = k / (pts.length - 1), t1 = (k + 1) / (pts.length - 1);
      segs.push({ a: pts[k], b: pts[k + 1], r0: r1 + (r2 - r1) * t0, r1: r1 + (r2 - r1) * t1, nail: nail && k === pts.length - 2 });
    }
  };
  add([[-14, 98], [18, 84], [44, 71]], 16, 13);               // forearm
  add([[40, 72], [60, 62]], 16, 15);                          // palm
  [
    [[62, 46], [80, 34], [98, 32], [111, 41]],
    [[66, 55], [88, 47], [106, 48], [119, 58]],
    [[66, 64], [88, 62], [105, 67], [115, 77]],
    [[62, 73], [80, 78], [93, 86], [100, 96]],
  ].forEach(f => add(f, 4.4, 2.0, true));                     // long bony fingers, curling down
  add([[48, 82], [58, 97], [72, 103], [83, 100]], 4.6, 2.2, true); // thumb
  const hit = (x, y) => {
    let best = null;
    for (const s of segs) {
      const [ax, ay] = s.a, [bx, by] = s.b;
      const dx = bx - ax, dy = by - ay;
      const t = Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)));
      const d = Math.hypot(x - (ax + dx * t), y - (ay + dy * t));
      if (d <= s.r0 + (s.r1 - s.r0) * t) {
        if (s.nail && t > 0.45) return { nail: true, tip: t > 0.82 };
        best = { nail: false };
      }
    }
    return best;
  };
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    const h = hit(x + 0.5, y + 0.5);
    if (!h) continue;
    let c = 0xff060608;
    if (h.nail) c = h.tip ? 0xff6a0000 : 0xffd8cfc0;
    else if (!hit(x + 0.5, y - 0.5)) c = 0xff2c2a32;            // light from above: thin grey rim
    else if (rnd() < 0.05) c = 0xff121016;
    img.set(x, y, c);
  }
  img.scaled(2).save('misc/screen_hand.png');
}

// ---------- item: flashlight (16x16), off and lit ----------
// A diagonal torch: grip bottom-left, head and lens top-right. Built along an axis so the 45° edges stay clean.
{
  const ax = 2.5, ay = 13.5, bx = 12.6, by = 3.4;
  const dx = bx - ax, dy = by - ay, len = Math.hypot(dx, dy);
  const ux = dx / len, uy = dy / len;
  const shape = (x, y) => {
    const t = ((x - ax) * ux + (y - ay) * uy) / len;
    const s = (x - ax) * -uy + (y - ay) * ux;          // signed side distance (negative = upper-left)
    if (t < -0.02 || t > 1.02) return null;
    const head = t > 0.68;
    const r = head ? 2.7 : t > 0.6 ? 1.6 + (t - 0.6) / 0.08 * 1.1 : 1.6;
    if (Math.abs(s) > r) return null;
    return { t, s, head, lens: t > 0.9 };
  };
  for (const lit of [false, true]) {
    const img = new Img(16, 16);
    const P = { k: 0x101014, body: 0x3a3c44, bodyHi: 0x5c606c, bodyLo: 0x24252b, ring: 0x18191d,
      head: 0x8a8e98, headHi: 0xc4c8d0, headLo: 0x5a5e68,
      lens: lit ? 0xfff2c0 : 0x5a6878, lensHi: lit ? 0xffffff : 0x8a9aac, button: lit ? 0xd84a2a : 0x7a2a1a };
    const fill = [];
    for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
      const h = shape(x + 0.5, y + 0.5);
      if (!h) continue;
      let c;
      if (h.lens) c = h.s < -0.6 ? P.lensHi : P.lens;
      else if (h.head) c = h.s < -1.2 ? P.headHi : h.s > 1.2 ? P.headLo : P.head;
      else {
        c = h.s < -0.5 ? P.bodyHi : h.s > 0.7 ? P.bodyLo : P.body;
        const ringAt = [0.12, 0.24, 0.36];
        if (ringAt.some(r => Math.abs(h.t - r) < 0.035)) c = P.ring;
        if (Math.abs(h.t - 0.5) < 0.05 && h.s < 0.2 && h.s > -1.2) c = P.button;
      }
      fill.push([x, y, c]);
    }
    const filled = new Set(fill.map(([x, y]) => x + ',' + y));
    for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
      if (filled.has(x + ',' + y)) continue;
      if ([[1, 0], [-1, 0], [0, 1], [0, -1]].some(([ox, oy]) => filled.has((x + ox) + ',' + (y + oy)))) img.set(x, y, P.k);
    }
    for (const [x, y, c] of fill) img.set(x, y, c);
    img.save(lit ? 'item/flashlight_on.png' : 'item/flashlight.png');
  }
}
