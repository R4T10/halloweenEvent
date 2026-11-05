// Builds the 3D flashlight item: geometry (Java item model elements), a 64x64 PSX-style texture atlas
// and its lit variant. Shape follows the Blockbench blockout (project "flashlight"): octagonal bezel with a
// recessed lens and red-orange reflector ring, a knurled grip that narrows towards the back, octagonal end cap.
// Usage: node tools/gen_flashlight.mjs   (run from the project root)
// Writes models/item/flashlight.json (+ override to flashlight_on), models/item/flashlight_on.json,
// textures/item/flashlight.png and textures/item/flashlight_on.png.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

const ASSETS = 'src/main/resources/assets/halloweenevent';
const S = 64;           // texture size in pixels
const PX = S / 16;      // pixels per UV unit

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
    if (x < 0 || y < 0 || x >= this.w || y >= this.h) return;
    const i = (y * this.w + x) * 4;
    this.d[i] = (c >> 16) & 255; this.d[i + 1] = (c >> 8) & 255; this.d[i + 2] = c & 255; this.d[i + 3] = 255;
  }
  save(file) {
    const raw = Buffer.alloc((this.w * 4 + 1) * this.h);
    for (let y = 0; y < this.h; y++) {
      raw[y * (this.w * 4 + 1)] = 0;
      Buffer.from(this.d.buffer, y * this.w * 4, this.w * 4).copy(raw, y * (this.w * 4 + 1) + 1);
    }
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(this.w, 0); ihdr.writeUInt32BE(this.h, 4);
    ihdr[8] = 8; ihdr[9] = 6;
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
      chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]));
  }
}

// deterministic noise
let seed = 1337;
const rnd = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);
const hash = (x, y) => { let h = (x * 374761393 + y * 668265263) ^ 0x5bd1e995; h = (h ^ (h >>> 13)) * 1274126177; return ((h ^ (h >>> 16)) >>> 0) / 4294967295; };
const shade = (c, f) => {
  const ch = s => Math.max(0, Math.min(255, Math.round(((c >> s) & 255) * f)));
  return (ch(16) << 16) | (ch(8) << 8) | ch(0);
};
const mix = (a, b, t) => {
  const ch = s => Math.round(((a >> s) & 255) * (1 - t) + ((b >> s) & 255) * t);
  return (ch(16) << 16) | (ch(8) << 8) | ch(0);
};

// ---------- texture regions (pixels) ----------
const R = {
  metal: [0, 0, 32, 24],     // scuffed black/dark-grey metal: bezel, neck, end cap
  rim:   [32, 0, 32, 12],    // front ring of the bezel, more worn
  grip:  [0, 24, 48, 16],    // black knurled body
  lens:  [0, 44, 18, 18],    // lens disk, radial (x 5.8..10.2)
  ring:  [20, 42, 21, 21],   // red-orange reflector ring, radial (x 5.4..10.6)
  rub:   [44, 44, 8, 8],     // switch rubber
  edge:  [32, 12, 32, 12],   // metal with a worn bright front edge (bezel sides)
};

function paint(lit) {
  seed = 1337;
  const img = new Img(S, S);
  const fill = ([x0, y0, w, h], f) => { for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) img.set(x0 + x, y0 + y, f(x, y, w, h)); };

  // scuffed metal: dark grey with coarse 2px mottling, light scratches and a few bright scuffs
  const metal = (x, y) => {
    const m = hash(x >> 1, y >> 1);
    let c = mix(0x1f2023, 0x2e2f33, m);
    if (hash(x, y) > 0.975) c = 0x4a4c50;
    if (hash(x + 7, (y + x) >> 2) > 0.985) c = 0x606266;
    return c;
  };
  fill(R.metal, metal);
  // scratches
  for (let i = 0; i < 10; i++) {
    let x = Math.floor(rnd() * 32), y = Math.floor(rnd() * 24); const len = 2 + Math.floor(rnd() * 5), dx = rnd() > 0.5 ? 1 : -1;
    for (let k = 0; k < len; k++) img.set(R.metal[0] + ((x + k * dx + 32) % 32), R.metal[1] + ((y + (k >> 1)) % 24), 0x5e6064);
  }
  fill(R.rim, (x, y) => {
    let c = mix(0x2a2b2f, 0x3c3d42, hash(x >> 1, (y >> 1) + 50));
    if (hash(x + 3, y + 9) > 0.93) c = 0x6a6c70;
    return c;
  });
  // bezel sides: same metal, top 2 rows (the front edge) worn to bright metal
  fill(R.edge, (x, y) => {
    let c = metal(x + 11, y + 5);
    if (y === 0) c = hash(x, 99) > 0.25 ? 0x8a8c90 : 0x5a5c60;
    else if (y === 1 && hash(x, 98) > 0.5) c = 0x66686c;
    return c;
  });
  // knurled grip: diamond cross-hatch at 2px pitch
  fill(R.grip, (x, y) => {
    const a = (x + y) % 3 === 0, b = (x - y + 300) % 3 === 0;
    let c = 0x1a1b1e;
    if (a && b) c = 0x0d0d0f; else if (a || b) c = 0x131416;
    else if (hash(x, y + 40) > 0.8) c = 0x2a2b2f;
    if (hash(x >> 2, (y >> 2) + 70) > 0.85 && !(a || b)) c = 0x34363a; // worn patches
    return c;
  });
  // lens: radial
  fill(R.lens, (x, y, w) => {
    const cx = (w - 1) / 2, r = Math.hypot(x - cx, y - cx) / (w / 2); // 0 centre .. 1 edge
    if (lit) {
      if (r < 0.28) return 0xffffff;
      if (r < 0.5) return (Math.round(r * 14) % 2) ? 0xfff6dc : 0xffffff;
      if (r < 0.8) return (Math.round(r * 14) % 2) ? 0xffe9b8 : 0xfff4d8;
      return 0xffd890;
    }
    let c = (Math.round(r * 14) % 2) ? 0x30363a : 0x3c4448;  // dark glass over reflector rings
    if (r < 0.2) c = 0x5a6064;                              // bulb
    if (r > 0.88) c = 0x24282a;
    const hx = x - cx + 3, hy = y - cx + 3;                 // specular streak, upper-left
    if (Math.abs(hx - hy) < 1.2 && Math.hypot(hx, hy) < 3.5) c = 0x9aa6aa;
    return c;
  });
  fill(R.ring, (x, y, w) => {
    const cx = (w - 1) / 2, r = Math.hypot(x - cx, y - cx) / (w / 2);
    const base = lit ? (r < 0.9 ? 0xff8a3c : 0xe0582a) : (r < 0.9 ? 0xb8401e : 0x7a2614);
    return hash(x, y + 200) > 0.85 ? shade(base, 1.15) : base;
  });
  fill(R.rub, (x, y) => (y === 0 ? 0x3a3c40 : hash(x, y + 300) > 0.7 ? 0x26272a : 0x1c1d20));
  return img;
}

// ---------- geometry ----------
const T = Math.tan(Math.PI / 8);
const r3 = v => Math.round(v * 1000) / 1000;
const elements = [];
const cube = (name, from, to, rot, kind, faces) =>
  elements.push({ name, from: from.map(r3), to: to.map(r3), rot, kind, faces });

// Octagonal prism around the model axis (x=y=8) from four boxes: two axis-aligned bars and two bars
// rotated ±45° about Z; their union is a regular octagon. dz staggers the end faces so none are coplanar.
// Only outward-facing sides are emitted.
function oct(name, a, z0, z1, kind, endKind = kind) {
  const h = a * T + 0.01;
  const parts = [
    ['a', [8 - a, 8 - h], [8 + a, 8 + h], 0, 0, ['east', 'west']],
    ['b', [8 - h, 8 - a], [8 + h, 8 + a], 0, 0.02, ['up', 'down']],
    ['c', [8 - a, 8 - h], [8 + a, 8 + h], 45, 0.04, ['east', 'west']],
    ['d', [8 - a, 8 - h], [8 + a, 8 + h], -45, 0.06, ['east', 'west']],
  ];
  for (const [s, f, t, rot, dz, sides] of parts) {
    const faces = {};
    for (const side of sides) faces[side] = kind;
    faces.north = endKind; faces.south = endKind === 'lens' || endKind === 'ring' ? kind : endKind;
    cube(`${name}_${s}`, [f[0], f[1], z0 + dz], [t[0], t[1], z1 + dz], rot, kind, faces);
  }
}

// bezel front ring: eight bars around the lens
{
  const A = 3.4, t = 0.85, D = 1.6, L = 2 * A * T + 0.02;
  const bars = [
    ['top', [8 - L / 2, 8 + A - t], [8 + L / 2, 8 + A], 0, 0],
    ['bottom', [8 - L / 2, 8 - A], [8 + L / 2, 8 - A + t], 0, 0.03],
    ['right', [8 + A - t, 8 - L / 2], [8 + A, 8 + L / 2], 0, 0.06],
    ['left', [8 - A, 8 - L / 2], [8 - A + t, 8 + L / 2], 0, 0.09],
    ['top_r45', [8 - L / 2, 8 + A - t], [8 + L / 2, 8 + A], 45, 0.02],
    ['top_l45', [8 - L / 2, 8 + A - t], [8 + L / 2, 8 + A], -45, 0.05],
    ['bottom_r45', [8 - L / 2, 8 - A], [8 + L / 2, 8 - A + t], 45, 0.08],
    ['bottom_l45', [8 - L / 2, 8 - A], [8 + L / 2, 8 - A + t], -45, 0.11],
  ];
  for (const [n, f, to, rot, dz] of bars) {
    const vertical = n === 'left' || n === 'right';
    const faces = { north: 'rim', south: 'metal', up: 'edge', down: 'edge', east: 'edge', west: 'edge' };
    if (!vertical) { faces.east = 'metal'; faces.west = 'metal'; } else { faces.up = 'metal'; faces.down = 'metal'; }
    cube(`rim_${n}`, [f[0], f[1], dz], [to[0], to[1], D + dz], rot, 'rim', faces);
  }
}
oct('lens', 2.2, 0.55, 0.9, 'metal', 'lens');
oct('lens_ring', 2.6, 0.75, 1.4, 'metal', 'ring');
oct('bezel', 3.3, 1.3, 4.4, 'edge2', 'metal');
oct('bezel_step', 2.9, 4.2, 5.2, 'metal');
oct('neck', 2.3, 5.0, 6.0, 'metal');
oct('grip_front', 1.85, 5.8, 10.0, 'grip', 'metal');
oct('grip_back', 1.6, 9.8, 13.8, 'grip', 'metal');
oct('cap', 1.9, 13.6, 15.4, 'metal');
oct('cap_back', 1.45, 15.3, 15.9, 'metal');
cube('switch_base', [7.1, 9.5, 6.3], [8.9, 9.95, 8.7], 0, 'metal',
  { north: 'metal', south: 'metal', east: 'metal', west: 'metal', up: 'metal' });
cube('switch', [7.35, 9.6, 6.6], [8.65, 10.25, 8.4], 0, 'rub',
  { north: 'rub', south: 'rub', east: 'rub', west: 'rub', up: 'rub' });

// ---------- UVs ----------
// Radial regions (lens, ring) are mapped by the face's x/y position inside the disk so the four boxes of
// an octagon show one continuous image; tiled regions get a pixel-sized window at a per-face offset.
const RADIAL = { lens: [5.8, 10.2], ring: [5.4, 10.6] };
function faceSize(f, t, dir) {
  const dx = t[0] - f[0], dy = t[1] - f[1], dz = t[2] - f[2];
  return dir === 'north' || dir === 'south' ? [dx, dy] : dir === 'east' || dir === 'west' ? [dz, dy] : [dx, dz];
}
function uvFor(el, dir, region, idx) {
  const [rx, ry, rw, rh] = R[region === 'edge2' ? 'edge' : region];
  const k = PX;
  if (RADIAL[region] && (dir === 'north' || dir === 'south')) {
    const [lo, hi] = RADIAL[region], span = hi - lo, scale = rw / span;
    const u1 = rx + (el.from[0] - lo) * scale, u2 = rx + (el.to[0] - lo) * scale;
    const v1 = ry + (hi - el.to[1]) * scale, v2 = ry + (hi - el.from[1]) * scale;
    return [u1, v1, u2, v2].map(v => r3(v / k));
  }
  let [w, h] = faceSize(el.from, el.to, dir).map(v => Math.max(1, Math.round(v * k)));
  w = Math.min(w, rw); h = Math.min(h, rh);
  let ox = Math.floor(hash(idx, 11) * (rw - w + 1)), oy = Math.floor(hash(idx, 23) * (rh - h + 1));
  if (region === 'edge' || region === 'edge2') oy = 0; // keep the worn edge on the front rim
  // The worn edge sits at the top of the region: rotate so it lands on the face's front (low z) edge.
  return [rx + ox, ry + oy, rx + ox + w, ry + oy + h].map(v => r3(v / k));
}
// Faces whose long axis is z (east/west) need the worn 'edge' row along z=0 -> rotate 90°.
function buildElements(lit) {
  return elements.map((el, i) => {
    const faces = {};
    let n = 0;
    for (const [dir, region0] of Object.entries(el.faces)) {
      let region = region0;
      if (region === 'edge2') region = (dir === 'north' || dir === 'south') ? 'metal' : 'edge';
      const face = { uv: uvFor(el, dir, region, i * 7 + n++), texture: '#0' };
      const sideways = dir === 'east' || dir === 'west';
      if (region === 'edge' && sideways) {
        // u runs along z on east/west faces; rotate the window so its bright top row lies on the front edge
        const [w, h] = faceSize(el.from, el.to, dir).map(v => Math.max(1, Math.round(v * PX)));
        const [rx, ry] = R.edge;
        const ox = Math.floor(hash(i, 31) * (R.edge[2] - h + 1));
        face.uv = [rx + ox, ry, rx + ox + h, ry + Math.min(w, R.edge[3])].map(v => r3(v / PX));
        face.rotation = dir === 'east' ? 90 : 270;
      }
      if (region === 'edge' && dir === 'down') face.rotation = 180;
      if (lit && (region === 'lens' || region === 'ring') && dir === 'north')
        face.forge_data = { block_light: 15, sky_light: 15 };
      faces[dir] = face;
    }
    const out = { name: el.name, from: el.from, to: el.to };
    if (el.rot) out.rotation = { angle: el.rot, axis: 'z', origin: [8, 8, 8] };
    out.faces = faces;
    return out;
  });
}

// ---------- display ----------
const display = {
  thirdperson_righthand: { rotation: [80, 0, 0], translation: [0, 3, 1.5], scale: [0.7, 0.7, 0.7] },
  firstperson_righthand: { rotation: [10, 8, 0], translation: [1.5, 3.2, -1], scale: [0.42, 0.42, 0.42] },
  ground: { rotation: [0, 0, 0], translation: [0, 2, 0], scale: [0.45, 0.45, 0.45] },
  gui: { rotation: [40, 150, 0], translation: [0, 0, 0], scale: [0.95, 0.95, 0.95] },
  head: { rotation: [0, 0, 0], translation: [0, 13, 7], scale: [1, 1, 1] },
  fixed: { rotation: [0, 90, -45], translation: [0, 0, 0], scale: [0.8, 0.8, 0.8] },
};

for (const lit of [false, true]) {
  const name = lit ? 'flashlight_on' : 'flashlight';
  paint(lit).save(`${ASSETS}/textures/item/${name}.png`);
  const model = {
    credit: 'Generated by tools/gen_flashlight.mjs (shape blocked out in Blockbench)',
    texture_size: [S, S],
    textures: { 0: `halloweenevent:item/${name}`, particle: `halloweenevent:item/${name}` },
    elements: buildElements(lit),
    display,
  };
  if (!lit) model.overrides = [{ predicate: { 'halloweenevent:on': 1 }, model: 'halloweenevent:item/flashlight_on' }];
  fs.writeFileSync(`${ASSETS}/models/item/${name}.json`, JSON.stringify(model, null, 2) + '\n');
}
console.log(`flashlight: ${elements.length} elements, ${S}x${S} textures`);
