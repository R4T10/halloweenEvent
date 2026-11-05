// Generates the realistic textures: jumpscare face, figure skin atlas, mist, vignette, screen hand and eyes.
// Usage: node tools/gen_realistic.mjs [face|figure|effects]   (from the project root; no argument = everything)
import { makeFace } from './realistic/face.mjs';

const OUT = 'src/main/resources/assets/halloweenevent/textures';
const only = process.argv[2];
const want = name => !only || only === name;

if (want('face')) makeFace(`${OUT}/misc/horror_face.png`);
if (want('figure')) (await import('./realistic/figure.mjs')).makeFigureAtlas(`${OUT}/entity/figure_skin.png`);
if (want('effects')) (await import('./realistic/effects.mjs')).makeEffects(OUT);
