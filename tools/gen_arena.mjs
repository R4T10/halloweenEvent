// Writes the empty test arena used by the game tests: a 41 x 8 x 41 template with a stone floor at y = 0.
// Usage: node tools/gen_arena.mjs   (from the project root)
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

const SIZE = [41, 8, 41];
const OUT = 'src/main/resources/data/halloweenevent/structures/arena.nbt';

// ---- tiny NBT writer (big endian) ----
const T = { END: 0, INT: 3, STRING: 8, LIST: 9, COMPOUND: 10 };
const parts = [];
const u8 = v => { const b = Buffer.alloc(1); b.writeUInt8(v); parts.push(b); };
const i32 = v => { const b = Buffer.alloc(4); b.writeInt32BE(v); parts.push(b); };
const str = s => { const d = Buffer.from(s, 'utf8'); const b = Buffer.alloc(2); b.writeUInt16BE(d.length); parts.push(b, d); };
const named = (type, name) => { u8(type); str(name); };
const intList = (name, values) => { named(T.LIST, name); u8(T.INT); i32(values.length); values.forEach(i32); };

named(T.COMPOUND, '');
named(T.INT, 'DataVersion'); i32(3465);                      // Minecraft 1.20.1
intList('size', SIZE);
named(T.LIST, 'entities'); u8(T.COMPOUND); i32(0);
named(T.LIST, 'palette'); u8(T.COMPOUND); i32(1);
named(T.STRING, 'Name'); str('minecraft:stone'); u8(T.END);
named(T.LIST, 'blocks'); u8(T.COMPOUND); i32(SIZE[0] * SIZE[2]);
for (let x = 0; x < SIZE[0]; x++) for (let z = 0; z < SIZE[2]; z++) {
  intList('pos', [x, 0, z]);
  named(T.INT, 'state'); i32(0);
  u8(T.END);
}
u8(T.END);

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, zlib.gzipSync(Buffer.concat(parts)));
console.log('wrote', OUT, fs.statSync(OUT).size, 'bytes');
