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

// The jumpscare face, figure skin atlas, mist, vignette, static, screen hand and eyes are painted by
// tools/gen_realistic.mjs (smooth shaded textures instead of pixel art).
