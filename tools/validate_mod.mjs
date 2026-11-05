// Static checks for everything the headless game tests cannot load (client code, assets, shaders).
// Usage: node tools/validate_mod.mjs   (from the project root; exits 1 when something is wrong)
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const MODID = 'halloweenevent';
const SRC = 'src/main';
const JAVA = `${SRC}/java/com/${MODID}`;
const RES = `${SRC}/resources`;
const ASSETS = `${RES}/assets/${MODID}`;

let errors = 0, warnings = 0;
const fail = msg => { errors++; console.log('  FAIL  ' + msg); };
const warn = msg => { warnings++; console.log('  warn  ' + msg); };
const section = name => console.log('\n' + name);

const walk = (dir, out = []) => {
  for (const e of fs.existsSync(dir) ? fs.readdirSync(dir, { withFileTypes: true }) : []) {
    const p = path.join(dir, e.name).replaceAll('\\', '/');
    e.isDirectory() ? walk(p, out) : out.push(p);
  }
  return out;
};
const read = p => fs.readFileSync(p, 'utf8');
const javaFiles = walk(JAVA).filter(f => f.endsWith('.java') && !f.includes('/gametest/'));
const javaSrc = Object.fromEntries(javaFiles.map(f => [f, read(f)]));
const allJava = Object.values(javaSrc).join('\n');

// ---------------------------------------------------------------- JSON
section('JSON files parse');
const jsonFiles = walk(RES).filter(f => f.endsWith('.json') || f.endsWith('.mcmeta'));
const json = {};
for (const f of jsonFiles) {
  try { json[f] = JSON.parse(read(f)); } catch (e) { fail(`${f}: ${e.message}`); }
}
console.log(`  ${Object.keys(json).length} files`);

// ---------------------------------------------------------------- language
section('Language files');
const th = json[`${ASSETS}/lang/th_th.json`] ?? {};
const en = json[`${ASSETS}/lang/en_us.json`] ?? {};
for (const k of new Set([...Object.keys(th), ...Object.keys(en)])) {
  if (!(k in th)) fail(`th_th.json is missing "${k}"`);
  else if (!(k in en)) fail(`en_us.json is missing "${k}"`);
  else if (th[k] !== en[k]) fail(`th_th.json and en_us.json differ for "${k}" (English clients must also see Thai)`);
}
const used = new Set();
for (const m of allJava.matchAll(/"((?:gui|msg|hud|cmd|horror|item|subtitles)\.halloweenevent\.[a-z0-9_.]*[a-z0-9_])"/g)) used.add(m[1]);
for (const k of used) if (!(k in th)) fail(`language key used in code but missing: ${k}`);
// keys built at runtime
const HORROR_LINES = +(allJava.match(/HORROR_LINES = (\d+)/)?.[1] ?? 0);
for (let i = 0; i < HORROR_LINES; i++) if (!(`horror.${MODID}.${i}` in th)) fail(`horror line ${i} is missing (HORROR_LINES = ${HORROR_LINES})`);
if (`horror.${MODID}.${HORROR_LINES}` in th) warn(`horror.${MODID}.${HORROR_LINES} exists but HORROR_LINES is ${HORROR_LINES}: it is never shown`);
for (const tab of ['event', 'candy', 'players', 'settings', 'horror']) if (!(`gui.${MODID}.tab.${tab}` in th)) fail(`tab label missing: ${tab}`);
for (let l = 1; l <= 3; l++) if (!(`gui.${MODID}.level.tip.${l}` in th)) fail(`scare level tooltip missing: ${l}`);
const unused = Object.keys(th).filter(k => !used.has(k) && !k.startsWith('item.') && !k.startsWith('subtitles.') && !k.startsWith(`horror.${MODID}.`) && !k.includes('.tab.') && !k.includes('.level.tip.'));
for (const k of unused) warn(`language key never referenced in code: ${k}`);
for (const [k, v] of Object.entries(th)) {
  if (/§/.test(v)) fail(`"${k}" contains a § colour code`);
}

// ---------------------------------------------------------------- sounds
section('Sounds');
const soundsJson = json[`${ASSETS}/sounds.json`] ?? {};
const registered = [...read(`${JAVA}/registry/ModSounds.java`).matchAll(/sound\("([a-z_]+)"\)/g)].map(m => m[1]);
for (const name of registered) if (!(name in soundsJson)) fail(`sound "${name}" is registered in ModSounds but missing in sounds.json`);
for (const name of Object.keys(soundsJson)) if (!registered.includes(name)) warn(`sounds.json entry "${name}" is not registered in ModSounds`);
let vanillaSounds = null;
const indexDir = path.join(os.homedir(), '.gradle/caches/forge_gradle/assets/indexes');
if (fs.existsSync(indexDir)) {
  const idx = fs.readdirSync(indexDir).filter(f => f.endsWith('.json')).map(f => path.join(indexDir, f));
  if (idx.length) vanillaSounds = new Set(Object.keys(JSON.parse(read(idx[0])).objects));
}
if (!vanillaSounds) warn('vanilla asset index not found: sample names are not checked');
for (const [name, def] of Object.entries(soundsJson)) {
  if (def.subtitle && !(def.subtitle in th)) fail(`sound "${name}": subtitle key ${def.subtitle} missing from the language file`);
  if (!def.sounds?.length) fail(`sound "${name}" has no samples`);
  for (const s of def.sounds ?? []) {
    const id = typeof s === 'string' ? s : s.name;
    const [ns, p] = id.includes(':') ? id.split(':') : ['minecraft', id];
    if (ns === 'minecraft' && vanillaSounds && !vanillaSounds.has(`minecraft/sounds/${p}.ogg`)) fail(`sound "${name}": vanilla sample does not exist: ${id}`);
    if (ns === MODID && !fs.existsSync(`${ASSETS}/sounds/${p}.ogg`)) fail(`sound "${name}": missing file ${p}.ogg`);
  }
}

// ---------------------------------------------------------------- textures
section('Textures');
const pngSize = f => {
  const b = fs.readFileSync(f);
  if (b.toString('latin1', 1, 4) !== 'PNG') throw new Error('not a PNG');
  return [b.readUInt32BE(16), b.readUInt32BE(20)];
};
// These sizes must match the texture-size arguments of the blit() calls (and FigureModels' atlas size).
const EXPECTED = {
  'misc/horror_face.png': [512, 512],
  'misc/mist.png': [256, 256],
  'misc/vignette.png': [256, 256],
  'misc/static.png': [64, 64],
  'misc/screen_hand.png': [512, 512],
  'misc/eye_sclera.png': [256, 128],
  'misc/eye_iris.png': [128, 128],
  'misc/eye_lids.png': [256, 512],
  'misc/eye_spiral.png': [256, 256],
  'gui/admin.png': [256, 256],
  'gui/hud.png': [128, 32],
};
const referenced = new Set();
for (const m of allJava.matchAll(/"(textures\/[a-z0-9_\/]+\.png)"/g)) referenced.add(m[1]);
for (const [file, def] of Object.entries(json)) {
  for (const v of Object.values(def.textures ?? {})) {
    const m = String(v).match(/^halloweenevent:(.+)$/);
    if (m) referenced.add(`textures/${m[1]}.png`);
  }
}
for (const t of referenced) {
  const f = `${ASSETS}/${t}`;
  if (!fs.existsSync(f)) { fail(`texture referenced but missing: ${t}`); continue; }
  const want = EXPECTED[t.replace('textures/', '')];
  if (want) {
    const got = pngSize(f);
    if (got[0] !== want[0] || got[1] !== want[1]) fail(`${t} is ${got.join('x')} but the code expects ${want.join('x')}`);
  }
}
const items = [...read(`${JAVA}/registry/ModItems.java`).matchAll(/(?:candy|register)\("([a-z_]+)"/g)].map(m => m[1]);
for (const item of items) {
  if (!fs.existsSync(`${ASSETS}/models/item/${item}.json`)) fail(`item "${item}" has no model`);
  if (!fs.existsSync(`${ASSETS}/textures/item/${item}.png`)) fail(`item "${item}" has no texture`);
  if (!(`item.${MODID}.${item}` in th)) fail(`item "${item}" has no name in the language file`);
}
for (const f of walk(`${ASSETS}/textures`).filter(f => f.endsWith('.png'))) {
  const rel = f.slice(ASSETS.length + 1);
  if (!referenced.has(rel) && rel !== 'textures/entity/figure_skin.png') warn(`texture is never referenced: ${rel}`);
}
{
  const atlas = pngSize(`${ASSETS}/textures/entity/figure_skin.png`);
  const declared = +(read(`${JAVA}/client/FigureModels.java`).match(/LayerDefinition\.create\(mesh, (\d+), (\d+)\)/)?.[1] ?? 0);
  if (atlas[0] !== atlas[1] || atlas[0] % declared !== 0) fail(`figure atlas ${atlas.join('x')} must be a whole multiple of the declared UV size ${declared}`);
  else console.log(`  figure atlas ${atlas[0]}px for a ${declared} unit UV space (${atlas[0] / declared}x detail)`);
}

// ---------------------------------------------------------------- shaders
section('Shaders');
// Uniforms vanilla fills in itself before every draw, with the element count it writes.
const VANILLA = { ProjMat: 16, ModelViewMat: 16, IViewRotMat: 9, TextureMat: 16, ScreenSize: 2, ColorModulator: 4,
  Light0_Direction: 3, Light1_Direction: 3, FogStart: 1, FogEnd: 1, FogColor: 4, FogShape: 1, LineWidth: 1,
  GameTime: 1, ChunkOffset: 3, GlintAlpha: 1 };
const POST_FILLED = { ProjMat: 16, InSize: 2, OutSize: 2, Time: 1, ScreenSize: 2 };
const shaderJsons = Object.keys(json).filter(f => /\/shaders\/(core|program)\/[^/]+\.json$/.test(f));
for (const f of shaderJsons) {
  const def = json[f];
  const dir = path.dirname(f);
  const isPost = f.includes('/program/');
  const resolve = (ref, ext) => `${ASSETS}/shaders/${isPost ? 'program' : 'core'}/${ref.replace(`${MODID}:`, '')}.${ext}`;
  const glsl = { vsh: null, fsh: null };
  for (const [key, ext] of [['vertex', 'vsh'], ['fragment', 'fsh']]) {
    const ref = def[key];
    if (!ref) { fail(`${f}: no "${key}"`); continue; }
    if (!ref.startsWith(`${MODID}:`)) continue;          // vanilla stage, e.g. "sobel" or "position"
    const g = resolve(ref, ext);
    if (!fs.existsSync(g)) fail(`${f}: ${g} does not exist`); else glsl[ext] = read(g);
  }
  const code = Object.values(glsl).filter(Boolean).join('\n');
  const declared = new Set([...code.matchAll(/^\s*uniform\s+\w+\s+(\w+)/gm)].map(m => m[1]));
  const filled = isPost ? POST_FILLED : VANILLA;
  for (const u of def.uniforms ?? []) {
    if (filled[u.name] !== undefined && filled[u.name] !== u.count) {
      fail(`${f}: uniform "${u.name}" is overwritten by the game with ${filled[u.name]} values but declared with ${u.count} (this crashed a render earlier as FogColor)`);
    }
    if (glsl.fsh && glsl.vsh && !declared.has(u.name) && !code.includes(u.name)) warn(`${f}: uniform "${u.name}" is not used by the GLSL`);
  }
  for (const s of def.samplers ?? []) if (glsl.fsh && !new RegExp(`uniform\\s+sampler2D\\s+${s.name}\\b`).test(code)) warn(`${f}: sampler ${s.name} is not declared in the GLSL`);
  for (const [stage, src] of Object.entries(glsl)) {
    if (!src) continue;
    const opens = (src.match(/[{(]/g) ?? []).length, closes = (src.match(/[})]/g) ?? []).length;
    if (opens !== closes) fail(`${f}: unbalanced brackets in the ${stage}`);
    if (!/#version 150/.test(src)) fail(`${f}: the ${stage} must start with #version 150`);
    if (!/void\s+main\s*\(/.test(src)) fail(`${f}: the ${stage} has no main()`);
  }
  if (glsl.vsh && glsl.fsh) {
    const outs = new Set([...glsl.vsh.matchAll(/^\s*out\s+\w+\s+(\w+)/gm)].map(m => m[1]));
    for (const m of glsl.fsh.matchAll(/^\s*in\s+\w+\s+(\w+)/gm)) if (!outs.has(m[1])) fail(`${f}: fragment input "${m[1]}" is not written by the vertex shader`);
  }
}
console.log(`  ${shaderJsons.length} shader programs`);
for (const f of Object.keys(json).filter(f => /shaders\/post\/[^/]+\.json$/.test(f))) {
  for (const pass of json[f].passes ?? []) {
    const name = pass.name.replace(`${MODID}:`, '');
    if (pass.name.startsWith(`${MODID}:`) && !fs.existsSync(`${ASSETS}/shaders/program/${name}.json`)) fail(`${f}: pass ${pass.name} has no program`);
  }
}

// ---------------------------------------------------------------- network and scares
section('Network and scares');
const constants = file => Object.fromEntries([...read(file).matchAll(/public static final int (\w+) = (\d+);/g)].map(m => [m[1], +m[2]]));
for (const [file, label, handler, pattern] of [
  [`${JAVA}/network/AdminAction.java`, 'AdminAction', `${JAVA}/server/AdminActions.java`, 'AdminAction'],
  [`${JAVA}/network/S2CScare.java`, 'S2CScare', `${JAVA}/client/ScareEffects.java`, 'S2CScare'],
]) {
  const c = constants(file), seen = new Map();
  for (const [name, v] of Object.entries(c)) {
    if (seen.has(v)) fail(`${label}: ${name} and ${seen.get(v)} both use id ${v}`);
    seen.set(v, name);
  }
  const src = read(handler);
  for (const name of Object.keys(c)) {
    if (!new RegExp(`\\b${pattern}\\.${name}\\b`).test(src)) fail(`${label}.${name} is never handled in ${path.basename(handler)}`);
  }
  console.log(`  ${label}: ${Object.keys(c).length} ids, all unique and handled`);
}
const netSrc = read(`${JAVA}/network/Net.java`);
for (const f of walk(`${JAVA}/network`).filter(f => /\/(S2C|C2S)\w+\.java$/.test(f))) {
  const cls = path.basename(f, '.java');
  if (!new RegExp(`messageBuilder\\(${cls}\\.class`).test(netSrc)) fail(`packet ${cls} is not registered in Net.register()`);
  const src = read(f);
  for (const need of ['encode', 'decode', 'handle']) if (!new RegExp(`\\b${need}\\(`).test(src)) fail(`packet ${cls} has no ${need}()`);
}
const ids = [...netSrc.matchAll(/messageBuilder\((\w+)\.class/g)].map(m => m[1]);
if (new Set(ids).size !== ids.length) fail('a packet is registered twice');

// ---------------------------------------------------------------- code hygiene
section('Code hygiene');
for (const [f, src] of Object.entries(javaSrc)) {
  if (/§/.test(src)) fail(`${f}: § colour code (use ChatFormatting and language keys)`);
  if (/\b(TODO|FIXME|XXX)\b/.test(src)) warn(`${f}: leftover TODO/FIXME`);
  if (/System\.out\.print/.test(src)) fail(`${f}: System.out (use the logger)`);
}
for (const f of walk(RES).filter(f => f.endsWith('.toml'))) {
  if (!read(f).includes(MODID) && !read(f).includes('${mod_id}')) fail(`${f}: does not mention the mod id`);
}

console.log(`\n${errors === 0 ? 'OK' : 'FAILED'}: ${errors} error(s), ${warnings} warning(s)`);
process.exit(errors === 0 ? 0 : 1);
